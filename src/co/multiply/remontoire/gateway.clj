(ns co.multiply.remontoire.gateway
  "The gateway role (§15): one address through which a fleet of instances
   is reached. Followers attach here under a name, and a session opened on
   one of them is routed to it for as long as it lives — by the handle
   every tool call names, every task id begins with and every upload's
   path carries.

   A follower holds one request open, `POST /gateway/attach`, whose event
   stream delivers what the gateway has for it: request envelopes, a ping
   when its name is contested, a handle to drop. It answers a request with
   `POST /gateway/answer/<rid>`, one for each write its handler makes,
   which is answered only once those bytes are written to the waiting
   client — so a write on the follower succeeds or fails as it would on a
   direct connection. A request body too large to ride the envelope is
   fetched from `/gateway/body/<rid>`, in frames, so that one cut short on
   its way in is never taken for a whole one.

   The gateway persists nothing. Its routes are what followers register
   as they mint and report as they attach and with every beat, so a
   gateway started again at the same address is told everything it needs
   by followers redialling.
   Requests that name nothing routed are served by this JVM, exactly as on
   an instance alone."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.fleet :as fleet]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session])
  (:import
    [com.sun.net.httpserver Headers HttpExchange]
    [java.io DataOutputStream IOException InputStream OutputStream]
    [java.net URI]
    [java.nio.charset StandardCharsets]
    [java.util Date]
    [java.util.concurrent LinkedBlockingQueue TimeUnit]
    [java.util.concurrent.atomic AtomicLong]))


(def beat-ms
  "The cadence both ends keep: the gateway writes at least a comment to
   each attach stream this often, and a follower says it is alive this
   often."
  5000)


(def quiet-ms
  "How long a follower may go unheard before it is taken for gone. A
   closed socket is noticed at once; sleep and a partition close nothing,
   and only silence shows them."
  15000)


(def ^:private challenge-ms
  "How long the holder of a contested name has to answer a ping."
  2000)


(def ^:private grace-ms
  "How long after starting a gateway will not call an unknown handle dead:
   until its followers have redialled and reported, it cannot tell a dead
   session from one it has not heard of yet."
  10000)


;; A run of the gateway, from `start!` to `stop!`: the name it runs under
;; and every table it routes by. A server is started for one run and its
;; handlers are given that run, so what a server accepted is its run's
;; still when it comes to be served — where it goes is decided by that
;; run's name and nodes — and reaches no table of the run after it. Once
;; its run has ended it goes nowhere: not to the next run, nor to this JVM.
;;
;; :id      str
;; :name    str, what the gateway is called for as long as the run lasts
;; :lock    Held wherever a handle changes hands: a follower registering
;;          or reporting one, and this JVM taking one for itself. Two atoms
;;          answer "is this handle taken" — the routes here and the session
;;          table — and without one lock over both, each side can see the
;;          other's as free.
;; :state   atom of
;;   :ended?   true from `stop!` on, and never false again
;;   :nodes    name → {:id str, :queue LinkedBlockingQueue, :since ms,
;;                     :heard AtomicLong, :version str, :seq AtomicLong}
;;             `:id` names this attachment, and a follower says it with
;;             everything it says: a name outlives the JVM that held it.
;;             `:seq` is the number of the latest thing the node said of
;;             its sessions that was acted on (`fresh?`).
;;   :departed name → ms it was last heard, for a node whose routes are kept
;;   :routes   handle → name
;;   :retired  #{handle}: the handles found on two instances. Two
;;             conversations may name such a handle, so it is routed
;;             nowhere and given to nobody for as long as the run lasts.
;;   :started  ms
;; :pending atom of rid → {:exchange HttpExchange, :done promise,
;;                         :entry node-entry, :answering? atom,
;;                         :answered atom, :request-ids #{id}, :peer host,
;;                         :cid str}
;;          `:done` is delivered `:answered`, or `:gone` when the answer
;;          will not be finished. `:answered` holds the ids of the
;;          responses an event stream has carried to the client so far.
;;          Whatever is written to `:exchange` is written holding its
;;          monitor: a part of the answer and the gateway's own last word
;;          can be on their way at once.
;; :waiters atom of name → [quiescent promise …], settled when a node of
;;          that name attaches. Settled ones are swept at every wait and
;;          attach: a wait can also end by being cancelled, which nothing
;;          here is told of.
;; :pings   atom of nonce → promise, delivered by the pong
(defn- new-run
  [name]
  {:id      (str (random-uuid))
   :name    name
   :lock    (Object.)
   :state   (atom {:ended? false :nodes {} :departed {} :routes {} :retired #{} :started (System/currentTimeMillis)})
   :pending (atom {})
   :waiters (atom {})
   :pings   (atom {})})


;; The run this JVM is the gateway of now, nil when it is of none: for
;; what is asked outside any request — the roster, a wait for a node, a
;; handle this JVM takes for itself.
(defonce ^:private current (atom nil))


(defn- now
  []
  (System/currentTimeMillis))


;; # The roster

(defn- roster-of
  "The fleet as the gateway's run `run` sees it: itself first, then the
   attached nodes, then nodes that went away with sessions still routed to
   them."
  [run]
  (let [{:keys [nodes departed routes]} (some-> run :state deref)
        counts (frequencies (vals routes))
        t      (now)
        ago    #(quot (- t (long %)) 1000)]
    (-> [{:name     (or (:name run) (fleet/node-name))
          :gateway  true
          :attached true
          :sessions (session/live-count)}]
      (into (map (fn [[name {:keys [since heard version]}]]
                   {:name     name
                    :attached true
                    :since    (Date. (long since))
                    :heard-s  (ago (AtomicLong/.get heard))
                    :version  version
                    :sessions (get counts name 0)}))
        (sort-by key nodes))
      (into (keep (fn [[name heard]]
                    (when (contains? counts name)
                      {:name name :attached false :heard-s (ago heard) :sessions (counts name)})))
        (sort-by key departed)))))


(defn roster
  "The fleet as this gateway sees it now, as `roster-of` has it."
  []
  (roster-of @current))


(defn- roster-line
  [run name]
  (some #(when (and (= name (:name %)) (:attached %)) %) (roster-of run)))


(defn- unsettled
  "`waiting` without the waits that are over."
  [waiting]
  (into {}
    (keep (fn [[name ps]]
            (when-let [left (seq (remove realized? ps))]
              [name (vec left)])))
    waiting))


(defn- stopped
  []
  (ex-info "The gateway this wait was made on has stopped." {}))


(defn await-node
  "A task settling with `name`'s roster line once a node of that name is
   attached to this gateway — at once when it already is. It fails when
   the gateway stops first: a wait is its run's, and the gateway started
   next knows nothing of it."
  [name]
  (let [p (q/promise)]
    (if-let [{:keys [state waiters] :as run} @current]
      (if-let [line (roster-line run name)]
        (do (swap! waiters unsettled)
          (p line))
        (do (swap! waiters #(update (unsettled %) name (fnil conj []) p))
          ;; It may have attached between the look and the wait,
          (some-> (roster-line run name) p)
          ;; or the run may have ended, its waits already told so.
          (when (:ended? @state)
            (q/fail p (stopped)))))
      (q/fail p (ex-info "This instance is not a gateway." {})))
    p))


;; # Attaching

(defn- write!
  [out ^String s]
  (OutputStream/.write out (String/.getBytes s StandardCharsets/UTF_8))
  (OutputStream/.flush out))


(defn- plain!
  [exchange status body]
  (http/respond! exchange status "text/plain; charset=utf-8" body))


(defn- read-json
  [exchange]
  (json/read-str (slurp (HttpExchange/.getRequestBody exchange) :encoding "UTF-8") :key-fn keyword))


(defn- detach!
  "Forget `entry` as `name`'s node, and end what it still owed: a request
   it had not begun to answer is failed, an answer under way is cut short.
   Its routes are kept — a machine that went to sleep still has its
   sessions, and says which when it is back."
  [{:keys [state lock pending]} name entry]
  (locking lock
    (when (identical? entry (get-in @state [:nodes name]))
      (swap! state #(-> %
                      (update :nodes dissoc name)
                      (assoc-in [:departed name] (AtomicLong/.get (:heard entry)))))))
  (LinkedBlockingQueue/.offer (:queue entry) ::gone)
  (doseq [[_ {:keys [done] owner :entry}] @pending
          :when (identical? entry owner)]
    (deliver done :gone)))


(defn- stream?
  "Whether the response head a follower sent opens an event stream."
  [headers]
  (boolean (some (fn [[k vs]]
                   (and (= "content-type" (str/lower-case (name k)))
                     (some #(str/starts-with? % "text/event-stream") vs)))
             headers)))


(defn- answers-ping?
  "Whether the node behind `entry` answers a ping in time. A machine that
   wakes from sleep arrives as a claimant for its own name; without the
   challenge it would be refused until its stale entry went quiet."
  [{:keys [state pings]} entry]
  (let [nonce  (str (random-uuid))
        ponged (promise)]
    (swap! pings assoc nonce ponged)
    (try
      (LinkedBlockingQueue/.put (:queue entry) {:type "ping" :nonce nonce})
      ;; In slices, so that a holder found gone meanwhile — the ping's own
      ;; write may be what finds it — ends the wait early.
      (loop [left (long challenge-ms)]
        (cond
          (true? (deref ponged (min left 100) false)) true
          (not-any? #(identical? entry %) (vals (:nodes @state))) false
          (<= left 100) false
          :else (recur (- left 100))))
      (finally
        (swap! pings dissoc nonce)))))


(defn- fresh?
  "Whether what the node behind `entry` says under number `n` is newer
   than everything of its that was acted on — which it then is. A follower
   numbers what it says of its sessions in the order it read them, and one
   message can overtake another: a reply is lost, the follower goes on, and
   the first arrives after all. Acted on then, it would put back a state
   the later one replaced. Call holding the run's lock."
  [entry n]
  (let [latest (:seq entry)]
    (when (and (integer? n) (< (AtomicLong/.get latest) (long n)))
      (AtomicLong/.set latest (long n))
      true)))


(defn- settle!
  "Settle the handles node `name` says it holds — at an attach, and at
   every beat after it (§15). A handle nobody else holds is routed to it.
   One that another node holds, or this JVM, is retired on every holder:
   it reached a client before the gateway could check it, so two
   conversations may be naming it. A retired handle stays retired: whoever
   reports it is told to drop it, now and for as long as it goes on
   reporting it, and a node that was away is told when it is back. Routes
   the node no longer reports are dropped. Returns the handles this node
   must drop. Call holding the run's lock."
  [{:keys [state]} name reported]
  (let [{:keys [nodes routes retired]} @state
        verdict   (fn [h]
                    (let [holder (routes h)]
                      (cond
                        (contains? retired h) :told
                        (session/live? h) :here
                        (and holder (not= holder name)) :elsewhere
                        :else :adopt)))
        {:keys [told here elsewhere adopt]} (group-by verdict (set reported))
        conflicts (set (concat told here elsewhere))]
    (swap! state
      #(-> %
         (update :routes (fn [rs]
                           (-> (into {} (remove (fn [[h n]] (or (= n name) (conflicts h)))) rs)
                             (into (map (fn [h] [h name])) adopt))))
         (update :retired into conflicts)))
    (run! registry/release-session! here)
    (doseq [h elsewhere]
      (when-let [other (get nodes (routes h))]
        (LinkedBlockingQueue/.put (:queue other) {:type "retire" :handle h})))
    (vec conflicts)))


(defn- admit!
  "Make `entry` the node `name` if the name is free, and settle what it
   reported. Returns the handles the node must drop; `:held` when the name
   is another's; `:ended` when the run has ended, which nobody joins."
  [{:keys [state lock] :as run} name entry reported]
  (locking lock
    (cond
      (:ended? @state) :ended
      (contains? (:nodes @state) name) :held
      :else
      (let [retire (settle! run name reported)]
        (swap! state #(-> %
                        (assoc-in [:nodes name] entry)
                        (update :departed dissoc name)))
        retire))))


(defn- prune-departed!
  "Forget nodes gone longer than a session survives silence, and what was
   routed to them: a session nobody named for that long is gone wherever
   it was. Retirements are not forgotten with them: a holder that missed
   one may have been kept alive through its own endpoint all the while,
   and would be given the handle back."
  [{:keys [state lock]}]
  (let [cutoff (- (now) session/expiry-ms)]
    (locking lock
      (let [stale (into #{} (keep (fn [[name heard]] (when (< heard cutoff) name))) (:departed @state))]
        (swap! state #(-> %
                        (update :departed (fn [d] (apply dissoc d stale)))
                        (update :routes (fn [rs] (into {} (remove (comp stale val)) rs)))))))))


(defn- handle-attach!
  "The held request: take the name, answer with the welcome in the
   response's head — the wire's revision, the attachment's id and the
   cadence, which are of a size — then write the verdict on what the
   follower reported, a `retire` event for each handle it must drop, and
   then each queued item as an event until a write fails or the follower
   goes quiet. From the moment the name is taken, however this ends gives
   it up again."
  [{:keys [state lock waiters] :as run} exchange]
  (let [{:keys [name version sessions wire seq]} (read-json exchange)
        entry {:id      (str (random-uuid))
               :queue   (LinkedBlockingQueue.)
               :since   (now)
               :heard   (AtomicLong. (now))
               :version version
               :seq     (AtomicLong. (if (integer? seq) (long seq) 0))}
        admit #(admit! run name entry sessions)]
    (prune-departed! run)
    (cond
      (or (not (string? name)) (str/blank? name))
      (plain! exchange 400 "An attach names the node: {\"name\": …}\n")

      (= name (:name run))
      (plain! exchange 409 (str (pr-str name) " is the gateway's own name\n"))

      ;; Before anything is taken from it: what follows is read as this
      ;; revision has it, whatever the follower meant.
      (not= fleet/wire wire)
      (plain! exchange 409
        (str "This gateway speaks revision " fleet/wire " of the wire between followers and "
          "gateways, and the attach named " (pr-str wire) ": the two need versions of "
          "Remontoire that agree on it\n"))

      :else
      (let [retire (let [verdict (admit)
                         ;; Held: the holder keeps the name if it answers.
                         holder  (when (= :held verdict)
                                   (get-in @state [:nodes name]))]
                     (if (and holder (not (answers-ping? run holder)))
                       (do (detach! run name holder) (admit))
                       verdict))]
        (case retire
          :ended (plain! exchange 503 "The gateway this attach was made to has stopped\n")
          :held (plain! exchange 409 (str "A node named " (pr-str name) " is attached and answering\n"))
          (try
            (doto (HttpExchange/.getResponseHeaders exchange)
              (Headers/.set "Content-Type" http/sse-content-type)
              (Headers/.set "X-Welcome" (json/write-str {:wire       fleet/wire
                                                         :attachment (:id entry)
                                                         :beat-ms    beat-ms})))
            (HttpExchange/.sendResponseHeaders exchange 200 0)
            (let [out   (HttpExchange/.getResponseBody exchange)
                  queue (:queue entry)
                  event #(str "data: " (json/write-str %) "\n\n")]
              ;; The server keeps a head back until there is a byte to
              ;; send with it.
              (write! out ": attached\n\n")
              ;; Ahead of anything queued, and a request may be by now:
              ;; the stream is read in order, so a follower drops these
              ;; before it serves anything this attachment brings.
              (doseq [handle retire]
                (write! out (event {:type "retire" :handle handle})))
              ;; Whoever waited for this name is told, if this is still
              ;; the node of that name: looked at and collected under the
              ;; lock a node is forgotten under.
              (let [[line waiting] (locking lock
                                     (when (identical? entry (get-in @state [:nodes name]))
                                       [(roster-line run name)
                                        (get (first (swap-vals! waiters #(dissoc (unsettled %) name))) name)]))]
                (doseq [p waiting] (p line)))
              (loop []
                (let [item (LinkedBlockingQueue/.poll queue beat-ms TimeUnit/MILLISECONDS)]
                  (when-not (or (= ::gone item)
                              (< quiet-ms (- (now) (AtomicLong/.get (:heard entry)))))
                    (write! out (if item (event item) ": hb\n\n"))
                    (recur)))))
            (catch IOException _ nil)
            (finally
              (detach! run name entry))))))))


;; # What a follower says

(defn- handle-note!
  "The small things a follower says between requests. `register`: a handle
   it has just drawn, refused when anyone else holds it. `beat`: that it
   is alive, and the sessions it holds, settled as an attach's are and
   answered with the handles it must drop. `pong`: its answer to a ping.
   Anything but `pong` says which attachment it speaks for, and from any
   but the one its name holds when it is acted on it is 410, which tells
   a follower to attach again: a name passes from one JVM to the next,
   and what the first said on its way out is not the second's."
  [{:keys [state lock pings] :as run} exchange what]
  (let [{:keys [name attachment handle nonce sessions seq]} (read-json exchange)
        entry    (when-let [entry (get-in @state [:nodes name])]
                   (when (= attachment (:id entry)) entry))
        ;; Asked holding the lock: the name may have passed on since the
        ;; entry was found.
        attached? #(identical? entry (get-in @state [:nodes name]))
        gone!     #(plain! exchange 410 (str "No node named " (pr-str name) " is attached as that\n"))]
    (cond
      (= "pong" what)
      (do (some-> (get @pings nonce) (deliver true))
        (plain! exchange 204 nil))

      (nil? entry) (gone!)

      :else
      (do
        (AtomicLong/.set (:heard entry) (now))
        (case what
          ;; What arrives out of order from an attachment still held is
          ;; answered as heard and not acted on.
          "beat"
          (let [retire (locking lock
                         (cond
                           (not (attached?)) ::gone
                           (fresh? entry seq) (settle! run name sessions)))]
            (if (= ::gone retire)
              (gone!)
              (http/respond! exchange 200 http/json-content-type
                (http/encode {:retire (vec retire)}))))

          "register"
          (case (locking lock
                  (let [{:keys [routes retired]} @state
                        holder (routes handle)]
                    (cond
                      (not (attached?)) ::gone

                      (and (fresh? entry seq)
                        (not (or (session/live? handle)
                               (contains? retired handle)
                               (and holder (not= holder name)))))
                      (do (swap! state assoc-in [:routes handle] name)
                        ::granted))))
            ::gone (gone!)
            ::granted (plain! exchange 204 nil)
            (plain! exchange 409 "Taken\n"))

          (plain! exchange 404 "No such note\n"))))))


(def ^:private ended-text
  "The gateway this request was sent to has stopped. Nothing in it was run.")


(defn- claim-local?
  "Take `handle` for a session of this JVM's own if nobody in the fleet
   holds it, and say whether it was taken: the gateway mints like any
   instance, against the whole fleet. The session is made under the lock
   a follower's registration is settled under, so neither can find the
   handle free while the other is taking it.

   A run that has ended gives nobody a session, and throws: its routes
   are not the fleet's any more, and the session table it would write to
   is the JVM's, which a later run answers for."
  [{:keys [state lock]} handle take!]
  (locking lock
    (let [{:keys [ended? routes retired]} @state]
      (cond
        ended? (throw (ex-info ended-text {:handle handle}))
        (or (contains? routes handle) (contains? retired handle)) false
        :else (do (take!) true)))))


;; # Forwarding

(defn- forward!
  "Queue `exchange`'s request for the node behind `entry` and wait until
   its answer has been piped back, or until it is known that it will not
   be finished: the node went away, or said it gave the answer up. Then
   `gone!` is told how far the answer had come: `:unsent` when not a byte
   of it was written, so the gateway can answer instead; `:stream` when
   an event stream was open and still owed its responses, so that it can
   carry one last message; `:cut` when nothing is to be added — a plain
   body was under way, or the stream has given the client every response
   and has only to end."
  [{:keys [state pending]} exchange name entry extra gone!]
  (let [rid       (str (random-uuid))
        done      (promise)
        answering (atom false)
        answered  (atom false)]
    (try
      ;; In one step for the attachment: a request among those in flight
      ;; is one a cancellation can be routed after, and a cancellation
      ;; that finds it there must come after it in the queue.
      (locking (:queue entry)
        (swap! pending assoc rid
          (merge {:exchange exchange :done done :entry entry :answering? answering :answered answered}
            (select-keys extra [:request-ids :peer :cid])))
        (LinkedBlockingQueue/.put (:queue entry)
          (merge {:type    "request"
                  :rid     rid
                  :method  (HttpExchange/.getRequestMethod exchange)
                  :path    (str (HttpExchange/.getRequestURI exchange))
                  :headers (into {} (HttpExchange/.getRequestHeaders exchange))}
            (dissoc extra :request-ids :cid))))
      ;; The node may have gone between the lookup and the put.
      (when-not (identical? entry (get-in @state [:nodes name]))
        (deliver done :gone))
      (when (= :gone @done)
        ;; How far it came is read where parts are written, so a part on
        ;; its way in lands whole before this, or not at all.
        (locking exchange
          (gone! (cond
                   (not @answering) :unsent
                   (and (= :stream @answering) (not @answered)) :stream
                   :else :cut))))
      (finally
        (swap! pending dissoc rid)))))


(defn- handle-answer!
  "One part of a follower's answer to request `rid`: the status and
   headers when `X-Head` carries them, then the body's bytes, written to
   the waiting client and flushed before this is answered — 204 for bytes
   that reached the client's socket, 499 when the client was gone, 404
   when nobody waits for them any more. `X-Answers: all` says the part
   carries every response the request is owed, which counts as answered
   once the part is with the client. Told and not read: what a follower
   writes is bytes to a gateway, whose framing is the follower's own
   business; and said in a word, since the ids are the client's to
   choose and a header has a size. `X-End` closes the answer; `X-End:
   abort` gives it up unfinished — a part of it was lost on the way here,
   and the follower cannot say which — leaving the gateway to tell the
   client."
  [{:keys [pending]} node-exchange rid]
  (let [headers (HttpExchange/.getRequestHeaders node-exchange)
        end     (Headers/.getFirst headers "X-End")
        {:keys [exchange done answering? answered]} (get @pending rid)
        bytes   (InputStream/.readAllBytes (HttpExchange/.getRequestBody node-exchange))
        status  (if (nil? exchange)
                  404
                  (locking exchange
                    (cond
                      (realized? done) 404

                      (= "abort" end) (do (deliver done :gone) 204)

                      :else
                      (try
                        (swap! answering? #(or % :cut))
                        (when-let [head (Headers/.getFirst headers "X-Head")]
                          (let [{:keys [status length headers]} (json/read-str head :key-fn keyword)]
                            (when (stream? headers) (reset! answering? :stream))
                            (doseq [[k vs] headers, v vs]
                              (Headers/.add (HttpExchange/.getResponseHeaders exchange) (name k) v))
                            (HttpExchange/.sendResponseHeaders exchange (int status) (long length))))
                        (when (pos? (alength bytes))
                          (doto (HttpExchange/.getResponseBody exchange)
                            (OutputStream/.write bytes)
                            (OutputStream/.flush)))
                        (when (= "all" (Headers/.getFirst headers "X-Answers"))
                          (reset! answered true))
                        (when end (deliver done :answered))
                        204
                        (catch IOException _
                          (deliver done :answered)
                          499)))))]
    (plain! node-exchange status (case (int status)
                                   404 "No such request waiting\n"
                                   499 "The client was gone\n"
                                   nil))))


(defn- handle-body!
  "The body of waiting request `rid`, streamed to the follower that asked
   — for requests whose body did not ride the envelope. In frames: a
   four-byte length, then that many bytes, and a zero length last. The
   body may be cut short on its way in from the client, when the response
   is already under way and can only end; a follower that reads no last
   frame knows it was not given the whole."
  [{:keys [pending]} node-exchange rid]
  (if-let [{:keys [exchange]} (get @pending rid)]
    (do (HttpExchange/.sendResponseHeaders node-exchange 200 0)
      (with-open [out (DataOutputStream. (HttpExchange/.getResponseBody node-exchange))]
        (let [in     (HttpExchange/.getRequestBody exchange)
              buffer (byte-array fleet/frame-bytes)]
          (loop []
            (let [n (InputStream/.read in buffer)]
              (when-not (neg? n)
                (when (pos? n)
                  (DataOutputStream/.writeInt out n)
                  (DataOutputStream/.write out buffer 0 n))
                (recur))))
          (DataOutputStream/.writeInt out 0))))
    (plain! node-exchange 404 "No such request waiting\n")))


;; # Routing

(defn- handle-target
  "Where a request naming `handle` goes: `{:node name}` when the handle is
   routed, nil when this JVM is to answer — it holds the session, or will
   refuse it — and `{:refuse text}` for a retired handle, and while a
   freshly started gateway cannot yet tell a dead handle from one it has
   not been told of. A retirement is `:final?`: asking again changes
   nothing. `fleet` is the run's state as it was read when the request
   was found to be a live run's, the one reading its place is decided by."
  [fleet handle]
  (let [{:keys [routes retired started]} fleet]
    (when (string? handle)
      (cond
        ;; Before anything else is looked up under it — a result this JVM
        ;; retains under the handle may be the other conversation's.
        (contains? retired handle)
        {:refuse (str "Session " (pr-str handle) " was retired: its id turned up on two instances, so "
                   "two conversations may hold it, and the gateway carries it to neither. Call "
                   "create-session for a new one.")
         :final? true}

        (routes handle) {:node (routes handle)}
        (session/live? handle) nil
        (< (- (now) (long started)) grace-ms)
        ;; Two cases the gateway cannot tell apart, so the text names both:
        ;; a node's session not yet reported, and a session that lived in
        ;; the previous gateway's own JVM, which nobody will ever report.
        {:refuse (str "Session " (pr-str handle) " is not known here yet: this gateway started "
                   "moments ago, and the nodes it routes to are still attaching. If the session "
                   "was opened on a node, try again in a few seconds. If it was opened without "
                   "naming one, it lived in the gateway itself and is gone with the restart: call "
                   "create-session for a new one.")}))))


(defn- message-target
  "Where one JSON-RPC message goes, as `handle-target` answers. The
   session is the route (§15): a `create-session` naming a node opens one
   there, a tool call goes where its `session-id` lives, a task method
   where the handle its task id begins with does, and a cancellation where
   the request it cancels went.

   A cancellation names its request by an id only its sender makes unique,
   and a sender is known by its connection, which only the legacy wire
   has. One whose request runs in this JVM is `{:here true}`. When the
   connection has more than one such request in flight — on nodes or here
   — the answer is `{:decline true}`: nothing is cancelled, rather than
   somebody else's work.

   `run` is the run the request was sent to: a node named as it is, is
   this JVM, whatever name the JVM goes by now. `fleet` is as
   `handle-target` has it."
  [{:keys [pending] self :name} fleet {:keys [method params]} {:keys [cid modern?]}]
  (let [{:keys [arguments]} params]
    (case method
      "tools/call"
      (if (= "create-session" (:name params))
        (let [node (:node arguments)]
          (when (and (string? node) (not= node self))
            {:node node :create? true}))
        (handle-target fleet (:session-id arguments)))

      ("tasks/get" "tasks/update" "tasks/cancel")
      (handle-target fleet (second (re-matches #"([a-z]+)-\d+" (str (:taskId params)))))

      "notifications/cancelled"
      ;; As an instance alone has it (§5): a notice without a connection
      ;; names nothing, and is left to this JVM to ignore.
      (when (and cid (not modern?))
        (let [id   (:requestId params)
              sent (filterv (fn [p] (and (contains? (:request-ids p) id) (= cid (:cid p))))
                     (vals @pending))
              here (mcp/waiting cid id)]
          (cond
            (and (empty? sent) (zero? here)) nil
            (empty? sent) {:here true}
            (and (= 1 (count sent)) (zero? here)) {:entry (:entry (first sent))}
            :else {:decline true})))

      nil)))


(defn- node-of
  "The name `entry` is attached under, or nil when it no longer is."
  [{:keys [state]} entry]
  (some (fn [[name e]] (when (identical? e entry) name)) (:nodes @state)))


(defn- away-text
  [{:keys [state] :as run} node create?]
  (let [attached (mapv :name (filter #(and (:attached %) (not (:gateway %))) (roster-of run)))
        heard    (get-in @state [:departed node])]
    (if (or create? (nil? heard))
      (str "No node named " (pr-str node) " is attached. Attached: " (pr-str attached)
        ". `(__r.gw/node " (pr-str node) ")`, evaluated in a session on the gateway, waits for one.")
      (str "Node " (pr-str node) ", where this session lives, is not attached — last heard "
        (quot (- (now) (long heard)) 1000) " s ago. The session may still be live there: try again "
        "when the node is back. `(__r.gw/node " (pr-str node) ")`, evaluated in a session on the "
        "gateway, waits for it."))))


(defn- refusals
  "The gateway's own answer to the requests among `messages`, encoded — as
   the batch they came in when `batch?` — or nil when none of them is a
   request."
  [messages batch? modern? text]
  (let [said (into []
               (comp (filter #(some? (:id %)))
                 (map #(mcp/tool-refusal % modern? text)))
               messages)]
    (when (seq said)
      (http/encode (if batch? said (first said))))))


(defn- refuse!
  "Answer the requests among `messages` in the gateway's own words."
  [exchange messages batch? modern? text]
  (if-let [said (refusals messages batch? modern? text)]
    (http/respond! exchange 200 http/json-content-type said)
    (http/respond! exchange 202 nil nil)))


(defn- dispatch!
  "Decide where a request of the live run `run` goes, by `fleet`, the
   reading of its state it was found live in — which node a handle is on,
   and which attachment is that node: answers truthy when it was the
   fleet's and has been answered — forwarded, or refused here — and nil
   when this JVM is to serve it."
  [run fleet exchange {:keys [kind payload body modern? peer session]}]
  (case kind
    :upload
    (when-let [{:keys [node refuse final?]} (handle-target fleet session)]
      (if-let [entry (and node (get-in fleet [:nodes node]))]
        (forward! run exchange node entry {:peer peer}
          (fn [sent]
            (when (= :unsent sent)
              (plain! exchange 502 (str "Node " (pr-str node) " went away before answering\n")))))
        (plain! exchange (if final? 410 503) (str (or refuse (away-text run node false)) "\n")))
      true)

    :mcp
    (let [cid      (http/header exchange "Mcp-Session-Id")
          batch?   (sequential? payload)
          messages (filterv map? (if batch? payload [payload]))
          targets  (mapv #(message-target run fleet % {:cid cid :modern? modern?}) messages)
          ;; Where each message has to go: an attachment, or here. A name
          ;; is not a place: it passes from one JVM to the next, and a
          ;; cancellation follows its request to the JVM that has it while
          ;; a handle is found on the one this reading shows. A
          ;; notification that names nothing goes wherever the rest does.
          places   (into #{}
                     (keep (fn [[m t]]
                             (cond
                               (:here t) ::here
                               (:entry t) (:entry t)
                               (:node t) (get-in fleet [:nodes (:node t)] (:node t))
                               t t
                               (some? (:id m)) ::here)))
                     (map vector messages targets))
          refuse   #(refuse! exchange messages batch? modern? %)]
      (when-let [{:keys [node entry decline] unknown :refuse} (first (remove #(or (nil? %) (:here %)) targets))]
        ;; The connection is the gateway's to keep: it answered the
        ;; handshake, and the follower never saw it.
        (some-> cid session/connected!)
        (let [create? (boolean (some :create? targets))
              node    (or node (some :node targets) (node-of run entry))
              ;; The attachment the handle was found on, from the reading
              ;; that found it: the name may have passed to another JVM
              ;; since, one that holds no such session. An attachment
              ;; that has gone is found gone where the request is queued.
              entry   (or entry (get-in fleet [:nodes node]))]
          (cond
            ;; A request is carried to one place, whole. Sorting a batch's
            ;; calls by machine and stitching the answers back together is
            ;; a second transport; none of it is run instead.
            (< 1 (count places))
            (refuse (str "This batch holds messages for more than one JVM, and the gateway carries a "
                      "request to one. Nothing in it was run: send them separately."))

            decline (http/respond! exchange 202 nil nil)

            unknown (refuse unknown)

            (nil? entry) (refuse (away-text run node create?))

            :else
            (forward! run exchange node entry
              (cond-> {:body        body
                       :peer        peer
                       :cid         cid
                       :request-ids (into #{} (keep :id) messages)}
                ;; A follower never saw the handshake, so what the client
                ;; said in it rides along with the session's creation.
                (and create? cid) (assoc :client (session/connection-client cid)))
              (fn [sent]
                (case sent
                  :unsent (refuse (str "Node " (pr-str node) " did not answer: it went away, or its "
                                    "answer was lost on the way here. If the machine only became "
                                    "unreachable, the call may still run there when it is back."))
                  ;; The client is mid-stream and owed one message, to every
                  ;; request the stream still owed, in the shape they were
                  ;; asked in. What became of the call is not the gateway's
                  ;; to know, and the message does not guess: never begun,
                  ;; when the stream opened and the node was not told so;
                  ;; stopped by the same loss; gone with a JVM that died;
                  ;; still running on a machine that only dropped off the
                  ;; network.
                  :stream (when-let [said (refusals messages batch? modern?
                                            (str "The answer from node " (pr-str node) " was cut short: it went "
                                              "away, or part of its answer was lost on the way here. What "
                                              "became of the call is not known. It may not have started, "
                                              "may have been stopped, or may have run on with its result "
                                              "waiting there unread; and if the node's JVM is gone, so is "
                                              "this session. Look before running it again."))]
                            (try
                              (write! (HttpExchange/.getResponseBody exchange) (str "data: " said "\n\n"))
                              (catch IOException _ nil)))
                  nil)))))
        true))))


(defn- route!
  "The router of the gateway's run `run` (§15), as the transport calls
   it. A request the run accepted and comes to serve after it has ended is
   refused whole: it is the next run's to carry no more than it is this
   JVM's to serve in a node's place, or in its own."
  [run exchange {:keys [kind payload modern?] :as request}]
  ;; Read once: whether the run is live and where its handles go are one
  ;; answer, with no stop between them.
  (let [fleet @(:state run)]
    (if-not (:ended? fleet)
      (dispatch! run fleet exchange request)
      (do
        (if (= :upload kind)
          (plain! exchange 503 (str ended-text "\n"))
          (let [batch? (sequential? payload)]
            (refuse! exchange (filterv map? (if batch? payload [payload])) batch? modern? ended-text)))
        true))))


;; # The role

(defn- handle!
  "Everything under `/gateway`: what followers say to the gateway's run
   `run`. The transport serves it as it serves its own doors, from the
   moment the port opens."
  [run exchange {:keys [allowed-origins]}]
  (try
    (let [method (HttpExchange/.getRequestMethod exchange)
          [_ what arg] (re-matches #"/gateway/([a-z]+)(?:/(.+))?"
                         (URI/.getPath (HttpExchange/.getRequestURI exchange)))]
      (cond
        (not (http/origin-allowed? exchange allowed-origins))
        (plain! exchange 403 "Forbidden origin")

        (and (= "POST" method) (= "attach" what)) (handle-attach! run exchange)
        (and (= "POST" method) (= "answer" what) arg) (handle-answer! run exchange arg)
        (and (= "GET" method) (= "body" what) arg) (handle-body! run exchange arg)
        (and (= "POST" method) (#{"register" "beat" "pong"} what)) (handle-note! run exchange what)
        :else (plain! exchange 404 "Not found\n")))
    (catch IOException _ nil)
    (catch Exception e
      (try (plain! exchange 400 (str (Class/.getSimpleName (class e)) ": " (ex-message e) "\n"))
        (catch IOException _ nil)))
    (finally
      (HttpExchange/.close exchange))))


(defn start!
  "Make this instance a gateway: handles are minted against the whole
   fleet from here on. Returns what the transport is to be started with,
   `{:route fn, :contexts {path fn}, :fleet hooks}` — the router, the
   followers' door and the claim of the run that starts here, which stay
   that run's for as long as the server holding them serves anything.
   Before the port opens."
  []
  (let [run   (new-run (fleet/node-name))
        hooks {:name  (:name run)
               :claim (fn [handle take!] (claim-local? run handle take!))}]
    (reset! current run)
    (session/fleet-hooks! hooks)
    {:fleet    hooks
     :route    (fn [exchange request] (route! run exchange request))
     :contexts {"/gateway" (fn [exchange door] (handle! run exchange door))}}))


(defn stop!
  "End this gateway's run and let every follower go. Its routes go with
   it: a gateway started again is told them afresh. A request the run's
   transport accepted and has not yet routed is refused when its turn
   comes: never served here, and never carried to a node of a later run.
   Whoever waits for a node is told the gateway stopped."
  []
  (session/fleet-hooks! nil)
  (when-let [{:keys [state lock waiters] :as run} (first (reset-vals! current nil))]
    ;; First, and under the lock an attach is admitted under: nobody joins
    ;; a run that is ending.
    (locking lock
      (swap! state assoc :ended? true))
    (doseq [[name entry] (:nodes @state)]
      (detach! run name entry))
    (doseq [p (mapcat val (first (reset-vals! waiters {})))]
      (q/fail p (stopped))))
  nil)
