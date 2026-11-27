(ns co.multiply.remontoire.follower
  "The follower role (§15): an ordinary instance that also attaches to a
   gateway, under a name, and serves what the gateway sends it. Its own
   endpoint stays as it was — when the gateway is the thing that is
   broken, the direct connection is how anyone finds out what this JVM is
   doing.

   The follower dials, and redials for ever: it is the one that comes and
   goes, and may not be addressable from the gateway at all. Each request
   envelope is handed to the transport's ordinary handlers as a synthetic
   exchange, whose every write travels back as a POST the gateway answers
   only once the bytes are written to the client.

   Sessions are minted here, as on any instance, and claimed from the
   gateway before anyone is given them. Every live handle is reported at
   each attach and with every beat, which is all a restarted gateway needs
   to route again, and what settles a handle given out without the
   gateway's word — it was away, or did not say."
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [co.multiply.quiescent.impl.executor :as q.exec]
    [co.multiply.remontoire.fleet :as fleet]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session])
  (:import
    [clojure.lang ExceptionInfo]
    [com.sun.net.httpserver Headers HttpExchange]
    [java.io ByteArrayInputStream DataInputStream IOException InputStream OutputStream
     SequenceInputStream]
    [java.net InetSocketAddress URI]
    [java.net.http HttpClient HttpClient$Version HttpHeaders HttpRequest HttpRequest$BodyPublishers
     HttpResponse HttpResponse$BodyHandlers]
    [java.nio.charset StandardCharsets]
    [java.time Duration]
    [java.util Arrays Enumeration Optional]
    [java.util.concurrent CompletableFuture Executor TimeUnit TimeoutException]
    [java.util.concurrent.atomic AtomicLong]
    [java.util.concurrent.locks ReentrantLock]))


(def ^:private redial-ms
  "The pause between attempts to attach. Short and constant: the gateway
   is one address on the same network, and a fleet that reassembles within
   seconds of a gateway restart is the point."
  1000)


(def ^:private say-ms
  "How long a small message to the gateway may go unanswered."
  2000)


(def ^:private attach-ms
  "How long the gateway may take to answer an attach. It answers at once
   unless the name is contested, which costs a ping's wait. Without a
   bound, an attach sent into a network that drops packets would wait for
   as long as TCP does, and nothing else here would notice."
  10000)


(def ^:private opening-beat-ms
  "The cadence kept while not attached, a welcome saying the gateway's:
   the first report is not a whole period late for it, and a gateway that
   has not finished refusing an attach three of these after answering it
   is dropped when the watch next looks."
  1000)


(def ^:private part-ms
  "How long one part of an answer may take to reach the client through
   the gateway before the write counts as failed."
  30000)


;; {:name str, :base url, :origins set, :client HttpClient, :stopped atom,
;;  :attached atom, :stream atom, :dialling atom, :heard AtomicLong,
;;  :beat-ms atom, :lock ReentrantLock, :seq AtomicLong}
;; `:dialling` holds the attach still waiting for its answer, `:stream` the
;; body of the one that got it — a refusal's too, until it has been read —
;; and `:attached` the id its welcome gave this attachment, which goes
;; with everything said under it — nil until an attach is answered with
;; one, and again once the stream ends. `:lock` is held while a handle is asked for and while
;; the sessions are reported, and `:seq` numbers each of those as it reads
;; what it says: the gateway acts on them in that order or not at all.
;; What is served for the gateway is given the node with the stream its
;; request arrived on, as `:via`.
(defonce ^:private running (atom nil))


(def ^:private ^:dynamic *routed*
  "True while a request the gateway sent is being served. A session opened
   for one is useless unless the gateway routes to it."
  false)


(defn- request
  ^HttpRequest [base path timeout-ms headers ^bytes body]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str base path)))
                  (.timeout (Duration/ofMillis (long timeout-ms))))]
    (doseq [[k v] headers] (.header builder k v))
    (.build (if body
              (.POST builder (HttpRequest$BodyPublishers/ofByteArray body))
              (.GET builder)))))


(defn- say!
  "POST `message` to the gateway at `/gateway/<what>` and return the
   status it answered with."
  [{:keys [client base]} what message]
  (HttpResponse/.statusCode
    (HttpClient/.send client
      (request base (str "/gateway/" what) say-ms {"Content-Type" "application/json"}
        (String/.getBytes (json/write-str message) StandardCharsets/UTF_8))
      (HttpResponse$BodyHandlers/discarding))))


;; # Handles

(defmacro ^:private holding
  "Evaluate `body` holding `lock`. A lock of `java.util.concurrent`, not a
   monitor: what is held across is a request to the gateway, made on
   virtual threads."
  [lock & body]
  `(let [lock# ~lock]
     (ReentrantLock/.lock lock#)
     (try ~@body
       (finally (ReentrantLock/.unlock lock#)))))


(defmacro ^:private unless-stopped
  "Evaluate `body` for `node` unless it has stopped, under what `stop!` is
   made under: what a gateway asks of an instance is done by the time
   `stop!` returns, or is never done. A monitor, held across nothing that
   waits on the network."
  [node & body]
  `(locking running
     (when-not @(:stopped ~node)
       ~@body)))


(defn- retire!
  "Drop sessions the gateway found on another instance too (§15). Their
   results survive in the registry; the next call naming one is refused
   and told to create a new session. Not by an instance that has let its
   gateway go."
  [node handles]
  (unless-stopped node
    (run! registry/release-session! handles)))


(defn- register
  "Ask the gateway for `handle`, as `attachment`: `:ok` when it is this
   instance's to give out, `:taken` when anyone else holds it, `:unsure`
   when the gateway did not say — no answer, an error, or a gateway that
   does not know this instance as attached. Call holding the node's lock."
  [{:keys [name seq] :as node} attachment handle]
  (try
    (case (int (say! node "register" {:name       name
                                      :attachment attachment
                                      :handle     handle
                                      :seq        (AtomicLong/.incrementAndGet seq)}))
      204 :ok
      409 :taken
      :unsure)
    (catch Exception _ :unsure)))


(defn- claim?
  "Whether the fleet lets this instance have `handle`, the session made
   with `take!` when it does. Attached, the gateway is asked, and says no
   when anyone else holds it. Detached — or when the gateway does not say
   — the answer for a session opened directly is still yes: a gateway
   outage is when a direct session is most needed, and the next report
   settles the handle (`report!`). A session opened through the gateway is
   not given out on those terms: its next call would arrive at a gateway
   that cannot route it, so the request fails instead, and says why.

   Asked and taken under the lock reports are made under. A report read
   before the gateway's yes and sent after it would say the handle is not
   held, and the gateway would stop routing it.

   The claim is `node`'s, the follower that installed it, and no session
   is made on it once that follower has stopped: the gateway's yes was
   said to an instance that has let it go, and whoever answers for the
   JVM's sessions by then was not asked. The request fails, and says so."
  [{:keys [attached lock] :as node} handle take!]
  (let [take! #(or (unless-stopped node (take!) true)
                 (throw (ex-info (str "This instance let its gateway go while the session was being "
                                   "opened. Nothing was opened: try again.")
                          {:handle handle})))]
    (holding lock
      (case (if-let [attachment @attached] (register node attachment handle) :unsure)
        :ok (take!)
        :taken false
        (if *routed*
          (throw (ex-info (str "The gateway did not confirm this session, so it would not be routed here. "
                            "Nothing was opened: try again.")
                   {:handle handle}))
          (take!))))))


(defn- hooks
  "What `node` is to the fleet for a mint (`session/fleet-hooks!`):
   installed while it runs, and given to each request it takes — at its
   own port or off its gateway — which is served on its terms to the end.
   The name is the one its handles are registered under."
  [{:keys [name] :as node}]
  {:name  name
   :claim (fn [handle take!] (claim? node handle take!))})


(defn- sessions-now
  "What `node` says of its sessions at this moment, `{:seq n :sessions
   handles}` — numbered before they are read, so that what is read later
   is numbered later — or nil when it has stopped. Read under what `stop!`
   is made under: a follower that has let its gateway go says nothing
   more of the JVM's sessions, which are no longer its to speak for.
   Sending what was read is the caller's, and is not done under it."
  [{:keys [seq] :as node}]
  (unless-stopped node
    (let [n (AtomicLong/.incrementAndGet seq)]
      {:seq n :sessions (session/handles)})))


(defn- report!
  "Say, as `attachment`, that this instance is alive and which sessions it
   holds, and drop the ones the gateway answers it must — and whether the
   gateway took it. Nothing is said by an instance that has stopped.
   The gateway settles a report as it settles an attach (§15), so whatever
   a single message left uncertain is put right by the next of these: a
   handle given out while the gateway was away or silent, a registration
   whose reply was lost, a session that ended, a handle another instance
   turned out to hold."
  [{:keys [client base name lock] :as node} attachment]
  (holding lock
    (when-let [said (sessions-now node)]
      (let [message  (assoc said :name name :attachment attachment)
            response (HttpClient/.send client
                       (request base "/gateway/beat" say-ms {"Content-Type" "application/json"}
                         (String/.getBytes (json/write-str message) StandardCharsets/UTF_8))
                       (HttpResponse$BodyHandlers/ofString))]
        (when (= 200 (HttpResponse/.statusCode response))
          (retire! node (:retire (json/read-str (HttpResponse/.body response) :key-fn keyword)))
          true)))))


;; # Serving what the gateway sends

(defn- part
  "Send one part of the answer to request `rid`, and return what the
   gateway said of it once it has dealt with it: 204 when it wrote the
   part to the client, 499 when the client had hung up, 404 when it no
   longer holds the request. nil when it did not say."
  [{:keys [client base]} rid headers ^bytes body]
  (try
    (HttpResponse/.statusCode
      (HttpClient/.send client
        (request base (str "/gateway/answer/" rid) part-ms headers body)
        (HttpResponse$BodyHandlers/discarding)))
    (catch Exception _ nil)))


(defn- conclude!
  "Tell the gateway that the answer to request `rid` is over: `how` is
   `\"1\"` for one that is finished, and `\"abort\"` for one given up — a
   part of it failed on the way, and whether the gateway has it is not
   known here. Only the gateway's word settles it: that it took this, that
   it no longer holds the request, or that the client is gone. An error or
   no answer is neither, and it is said again. When its word never comes
   the attachment the request arrived on is dropped, which ends everything
   this instance owed through it: a client must not wait on an answer
   nobody is sending. That attachment and no other — by now the instance
   may be attached afresh, with nothing wrong."
  [{:keys [client base via]} rid how]
  (when-not (some (fn [_]
                    (or (try
                          (contains? #{204 404 499}
                            (HttpResponse/.statusCode
                              (HttpClient/.send client
                                (request base (str "/gateway/answer/" rid) say-ms {"X-End" how} (byte-array 0))
                                (HttpResponse$BodyHandlers/discarding))))
                          (catch Exception _ false))
                      (do (Thread/sleep 250) false)))
              (range 3))
    (some-> via InputStream/.close)))


(defn- framed
  "A request body as the gateway sends it — frames of a four-byte length
   and that many bytes, a zero length last — as a plain stream. A body
   that ends before its last frame was cut short somewhere on the way, and
   reading it throws rather than ends."
  ^InputStream [^InputStream in]
  (let [data  (DataInputStream. in)
        ahead (atom nil)
        over  (atom false)
        pull! (fn []
                (when-not @over
                  (let [n (DataInputStream/.readInt data)]
                    (cond
                      ;; Whatever is not a length a gateway writes is not a
                      ;; frame, and is not allocated to find out.
                      (or (neg? n) (< fleet/frame-bytes n)) (throw (IOException. "Not a frame"))
                      (zero? n) (do (reset! over true) nil)
                      :else (let [frame (byte-array n)]
                              (DataInputStream/.readFully data frame)
                              frame)))))]
    (SequenceInputStream.
      (reify Enumeration
        (hasMoreElements [_] (some? (or @ahead (reset! ahead (pull!)))))

        (nextElement
          [_]
          (let [[frame] (reset-vals! ahead nil)]
            (ByteArrayInputStream. frame)))))))


(defn- tunnelled
  "A synthetic exchange for one request envelope: the transport's handlers
   read the envelope as a request, and each write of their response is a
   part sent back to the gateway. The status and headers wait for the
   first write and travel with it, and a body of declared length ends with
   its last byte, so a plain response is one part.

   A part the gateway did not write to the client ends the answer for the
   handler, as a failed write does. When the gateway said nothing of it,
   the answer is given up there and then, without waiting for the handler
   to close: what it computes may be a long time coming, and a client
   holding a stream nothing more will be written to should not wait that
   out. Closing the exchange settles whatever is not settled by then. An
   end that carries nothing but itself is finished however often it has to
   be said; one lost with the head or the last bytes on it is given up."
  ^HttpExchange [{:keys [client base] :as node} {:keys [rid method path headers body peer]} admitted]
  (let [request-headers  (Headers.)
        response-headers (Headers.)
        status           (atom -1)
        head             (atom nil)
        ;; Bytes still owed of a body whose length was declared; nil for a
        ;; chunked one, which ends when it is closed.
        owed             (atom nil)
        ;; No further write is taken.
        ended            (atom false)
        ;; The gateway knows the answer is over, or is being told.
        settled          (atom false)
        ;; The request body as it arrives from the gateway, when fetched.
        fetched          (atom nil)
        ;; The ids of the responses the next write carries, when the
        ;; transport has said so.
        answers          (atom nil)
        none             (byte-array 0)
        send!            (fn [^bytes bytes end?]
                           (when @ended (throw (IOException. "Stream closed")))
                           (let [[h] (reset-vals! head nil)
                                 [all?] (reset-vals! answers nil)
                                 bare? (and end? (nil? h) (zero? (alength bytes)))
                                 _     (when end? (reset! ended true))
                                 said  (part node rid
                                         (cond-> {}
                                           h (assoc "X-Head" h)
                                           all? (assoc "X-Answers" "all")
                                           end? (assoc "X-End" "1"))
                                         bytes)]
                             (if (= 204 said)
                               (when end? (reset! settled true))
                               (do (reset! ended true)
                                 (cond
                                   ;; Its word that nobody waits any more.
                                   (#{404 499} said) (reset! settled true)
                                   ;; Said again as an end, where it is closed.
                                   bare? nil
                                   (compare-and-set! settled false true)
                                   (Thread/startVirtualThread #(conclude! node rid "abort")))
                                 (throw (IOException. (str "Not delivered: the gateway answered " said)))))))
        end!             (fn []
                           (let [bare? (and (not @ended) (nil? @head))]
                             (when-not @ended
                               (try (send! none true) (catch Exception _ nil)))
                             (when (compare-and-set! settled false true)
                               (conclude! node rid (if bare? "1" "abort")))
                             ;; Read to its end or not: an upload refused
                             ;; early leaves the rest unread.
                             (some-> @fetched InputStream/.close)))
        out              (proxy [OutputStream] []
                           (write
                             ([x]
                              (if (bytes? x)
                                (OutputStream/.write this ^bytes x 0 (alength ^bytes x))
                                (OutputStream/.write this (byte-array [(unchecked-byte x)]) 0 1)))
                             ([b off len]
                              (let [left (some-> @owed (- (long len)))]
                                (when left (reset! owed left))
                                (send! (Arrays/copyOfRange ^bytes b (int off) (int (+ (long off) (long len))))
                                  (boolean (some-> left (<= 0)))))))

                           (flush [])

                           (close [] (end!)))
        request-body     (delay
                           (if body
                             (ByteArrayInputStream. (String/.getBytes ^String body StandardCharsets/UTF_8))
                             (let [response (HttpClient/.send client
                                              (request base (str "/gateway/body/" rid) part-ms {} nil)
                                              (HttpResponse$BodyHandlers/ofInputStream))
                                   stream   (HttpResponse/.body response)]
                               (reset! fetched stream)
                               ;; Anything but the body is the gateway's own
                               ;; words, which must never be staged as a file.
                               (when-not (= 200 (HttpResponse/.statusCode response))
                                 (throw (IOException. (str "The request's body could not be fetched: the "
                                                        "gateway answered " (HttpResponse/.statusCode response)))))
                               (framed stream))))]
    (doseq [[k vs] headers, v vs]
      (Headers/.add request-headers (name k) v))
    (proxy [HttpExchange] []
      (getRequestHeaders [] request-headers)

      (getResponseHeaders [] response-headers)

      (getRequestURI [] (URI. path))

      (getRequestMethod [] method)

      (getRequestBody [] @request-body)

      (getResponseBody [] out)

      (sendResponseHeaders
        [code length]
        (reset! status code)
        (reset! head (json/write-str {:status code :length length :headers (into {} response-headers)}))
        (cond
          (neg? (long length)) (send! none true)
          (pos? (long length)) (reset! owed (long length))))
      ;; A real exchange's close never throws; a client already gone is not
      ;; news here.
      (close [] (end!))

      (getRemoteAddress [] (InetSocketAddress/createUnresolved (str peer) 0))

      (getResponseCode [] @status)

      (getLocalAddress [] nil)

      (getProtocol [] "HTTP/1.1")

      (getAttribute
        [k]
        (condp = k
          http/tunnelled-attribute true
          http/admitted-attribute admitted
          nil))

      (setAttribute
        [k v]
        (when (= http/answers-attribute k) (reset! answers v))
        nil)

      (setStreams [_ _] nil)

      (getHttpContext [] nil)

      (getPrincipal [] nil))))


(defn- header-of
  "The first value of header `k`, given in lower case, among an
   envelope's."
  [headers k]
  (some (fn [[h vs]] (when (= k (str/lower-case (name h))) (first vs))) headers))


(defn- admit
  "Register the requests of a legacy-wire POST for cancellation (§5), and
   return the context holding their records; nil for an envelope that has
   none a notice could name. For the thread that reads the attachment's
   stream, before the envelope is given a thread of its own: envelopes
   arrive in the order the gateway queued them, and a cancellation queued
   after its request has to find that request, however the two threads
   are scheduled. A stop that finds a request registered and not yet
   waiting is acted on as its wait begins."
  [{:keys [method path headers body]}]
  (let [cid (header-of headers "mcp-session-id")]
    (when (and cid body
            (= "POST" method)
            (str/starts-with? (str path) "/mcp")
            (not= mcp/protocol-version (header-of headers "mcp-protocol-version")))
      (try
        (mcp/admit {:connection cid} (json/read-str body :key-fn keyword))
        ;; Not a request, and its handler is the one to say so.
        (catch Exception _ nil)))))


(defn- serve!
  "Serve one request envelope, its requests registered already as
   `admitted` when they could be (`admit`)."
  [{:keys [origins] :as node} {:keys [headers client] :as envelope} admitted]
  (try
    ;; What the client said at `initialize`, which the gateway answered and
    ;; this JVM never saw: remembered under the connection id the request
    ;; carries, where `create-session` looks for it.
    (when client
      (when-let [cid (header-of headers "mcp-session-id")]
        (session/connection-client! (session/connected! cid) client)))
    (let [exchange (tunnelled node envelope admitted)]
      ;; A write that fails is a client that hung up, or a gateway that gave
      ;; the request up for lost while this instance was away. The JDK's
      ;; server swallows the same failure on a direct connection.
      (try
        (binding [*routed* true]
          (http/serve-tunnelled! exchange {:allowed-origins origins :fleet (hooks node)}))
        (catch IOException _ nil)
        ;; The handlers close what they serve; this is for whatever threw
        ;; before one did, so the gateway is never left waiting.
        (finally
          (HttpExchange/.close exchange))))
    (finally
      (some-> admitted mcp/dismiss))))


(defn- welcome-in
  "The welcome in the head of `response`, the answer to an attach, or nil
   when it carries none that can be read."
  [response]
  (try
    (let [said (some-> (HttpHeaders/.firstValue (HttpResponse/.headers response) "X-Welcome")
                 (Optional/.orElse nil)
                 (json/read-str :key-fn keyword))]
      (when (map? said) said))
    (catch Exception _ nil)))


(defn- welcome!
  "Take the gateway's welcome, which is what makes an attach an
   attachment, and say whether it was taken. One that does not name this
   instance's revision of the wire, or names no attachment, is refused
   before a word of it is acted on: the gateway refuses another revision
   at the attach, and one that welcomes otherwise did not read what it
   was sent as it was meant — or is no gateway, and said nothing of the
   kind.

   Taken whole or not at all: an instance that has stopped takes none,
   and one that stops while a welcome is being taken stops when it has
   been."
  [{:keys [name attached] :as node} {:keys [wire attachment] :as event}]
  (when-not (= fleet/wire wire)
    (throw (ex-info "another wire"
             {:refused (str "it speaks revision " (pr-str wire) " of the wire between followers and "
                         "gateways, and this instance revision " fleet/wire)})))
  (when-not (and (string? attachment) (not (str/blank? attachment)))
    (throw (ex-info "no attachment" {:refused "its welcome names no attachment"})))
  (unless-stopped node
    (some->> (:beat-ms event) (reset! (:beat-ms node)))
    (reset! attached attachment)
    ;; Sessions minted while the attach was on its way were not in what it
    ;; reported.
    (Thread/startVirtualThread #(try (report! node attachment) (catch Exception _ nil)))
    (println "remontoire attached to" (:base node) "as" (pr-str name))
    true))


(defn- event!
  "Act on what an attachment's stream brought."
  [node {:keys [type] :as event}]
  (case type
    ;; Registered here, in the order the stream brought it, and served on
    ;; a thread of its own.
    "request" (let [admitted (admit event)]
                (try
                  (Executor/.execute q.exec/virtual-executor #(serve! node event admitted))
                  (catch Throwable t
                    (some-> admitted mcp/dismiss)
                    (throw t))))

    "ping" (Thread/startVirtualThread
             #(try (say! node "pong" {:nonce (:nonce event)}) (catch Exception _ nil)))

    "retire" (retire! node [(:handle event)])

    nil))


(defn- dial!
  "Begin an attach, and return the answer that is on its way — or nil,
   beginning nothing, when this instance has stopped. What it says of its
   sessions is read, the request sent and its future published in one step
   under what `stop!` is made under: a stop either finds the dial and
   cancels it, or is found by it. Sending waits on nothing; waiting for
   the answer is the caller's, and is not done under it."
  [{:keys [client base name dialling] :as node}]
  (unless-stopped node
    (let [message (assoc (sessions-now node) :name name :version (mcp/version) :wire fleet/wire)
          pending (HttpClient/.sendAsync client
                    (-> (HttpRequest/newBuilder (URI. (str base "/gateway/attach")))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString (json/write-str message)))
                      .build)
                    (HttpResponse$BodyHandlers/ofInputStream))]
      (reset! dialling pending)
      pending)))


(defn- attend!
  "Wait for the answer to an attach, `pending`, and serve until the stream
   ends. Returns `{:refused why}` when the gateway would not have this
   instance, nil otherwise."
  [{:keys [base attached stream dialling heard beat-ms stopped] :as node} ^CompletableFuture pending]
  (let [;; Bounded by waiting on the future rather than by a request
        ;; timeout, which must not come to cover the held body.
        response (try (CompletableFuture/.get pending (long attach-ms) TimeUnit/MILLISECONDS)
                   (catch TimeoutException e
                     (CompletableFuture/.cancel pending true)
                     (throw e))
                   (finally (reset! dialling nil)))
        status   (HttpResponse/.statusCode response)
        body     (HttpResponse/.body response)]
    (try
      ;; Heard from, as the body is held: a welcome arrived with it, or
      ;; the end of a refusal is due from now.
      (AtomicLong/.set heard (System/currentTimeMillis))
      ;; Published whatever the answer was — a refusal is read from a body
      ;; that can be kept open as long as a stream — and before `stopped`
      ;; is read: `stop!` sets one and then closes the other, so whichever
      ;; order the two run in, a body that arrives as this instance stops
      ;; is closed by one of them.
      (reset! stream body)
      (when @stopped (InputStream/.close body))
      (if (= 200 status)
        (with-open [reader (io/reader body)]
          (let [node    (assoc node :via body)
                welcome (welcome-in response)]
            ;; The answer is the welcome or it is nothing: there is no
            ;; attach answered and not yet welcomed, and nothing is read
            ;; from a body whose head did not make it an attachment. Nor
            ;; is one taken by an instance that stopped as it arrived.
            (when (welcome! node welcome)
              (doseq [line (line-seq reader)
                      :while (not @stopped)]
                (when (str/starts-with? line "data: ")
                  (event! node (json/read-str (subs line 6) :key-fn keyword)))
                (AtomicLong/.set heard (System/currentTimeMillis))))))
        {:refused (str status " " (str/trim (slurp body)))})
      (catch ExceptionInfo e
        (or (not-empty (select-keys (ex-data e) [:refused]))
          (throw e)))
      (finally
        (reset! stream nil)
        ;; The cadence was this attachment's: the opening one is kept
        ;; until the next welcome says another.
        (reset! beat-ms opening-beat-ms)
        (when (first (reset-vals! attached nil))
          (when-not @stopped
            (println "remontoire lost its gateway at" base "— redialling")))))))


(defn- attach-once!
  "Attach, and serve until the stream ends, as `attend!` has it — or not
   at all, when this instance has stopped."
  [node]
  (when-let [pending (dial! node)]
    (attend! node pending)))


(defn- attach-loop!
  [{:keys [stopped base name] :as node}]
  (loop [told nil]
    (when-not @stopped
      (let [outcome (try (attach-once! node)
                      (catch Exception e {:unreachable (ex-message e)}))
            ;; Said once per change, not once per attempt.
            news    (or (:refused outcome) (when (:unreachable outcome) "it does not answer"))]
        (when (and news (not= news told) (not @stopped))
          (println "remontoire could not attach to" base "as" (pr-str name) "—" news))
        (Thread/sleep (long redial-ms))
        (recur news)))))


(defn- beat-loop!
  "Report at the gateway's cadence, and watch that the gateway is there:
   when a report fails, is disowned, or the gateway has not been heard
   from for three beats — its answer to the attach being the first thing
   heard, and whatever it answered with being what is held — the body is
   closed, which sends the attach loop round again. The body that was
   watched, and no other."
  [{:keys [stopped attached stream heard beat-ms] :as node}]
  (loop []
    (when-not @stopped
      (Thread/sleep (long @beat-ms))
      (when-let [watched @stream]
        (let [attachment @attached
              alive?     (or (nil? attachment)
                           (try (report! node attachment)
                             (catch Exception _ false)))
              quiet?     (< (* 3 (long @beat-ms)) (- (System/currentTimeMillis) (AtomicLong/.get heard)))]
          (when (or (not alive?) quiet?)
            (InputStream/.close watched))))
      (recur))))


(defn start!
  "Make this instance a follower of the gateway at `follow` — its base
   address, `http://host:port` — as `name`. Returns what the transport is
   to be started with, `{:fleet hooks}`: the claim every session opened
   here is made on. Before the port opens, so that no request finds the
   endpoint an instance alone. Nothing is dialled yet (`attach!`): until
   it is, a session is opened as on a follower whose gateway is away."
  [{:keys [follow name allowed-origins]}]
  (locking running
    (when-not @running
      (let [node {:name        name
                  :base        (str/replace follow #"/+$" "")
                  :origins     (set allowed-origins)
                  :client      (-> (HttpClient/newBuilder)
                                 (.version HttpClient$Version/HTTP_1_1)
                                 (.connectTimeout (Duration/ofMillis say-ms))
                                 .build)
                  :stopped     (atom false)
                  :dialled     (atom false)
                  :attached    (atom nil)
                  :stream      (atom nil)
                  :dialling    (atom nil)
                  :heard       (AtomicLong. 0)
                  :beat-ms     (atom opening-beat-ms)
                  :lock        (ReentrantLock.)
                  :seq         (AtomicLong. 0)}]
        (reset! running node)
        (session/fleet-hooks! (hooks node))))
    {:fleet (hooks @running)}))


(defn attach!
  "Dial the gateway the follower was started for, and keep attached.
   Returns at once. Once the port is open: what the gateway sends is
   served like anything that arrives there."
  []
  (locking running
    (when-let [{:keys [dialled] :as node} @running]
      (when (compare-and-set! dialled false true)
        (Thread/startVirtualThread #(attach-loop! node))
        (Thread/startVirtualThread #(beat-loop! node))))
    nil))


(defn stop!
  "Let go of the gateway, for good: the attach under way is abandoned, the
   body of one that was answered is closed, and nothing that arrives
   afterwards is served. A follower that never dialled has its client
   closed: nothing was said with it, and one dropped unclosed keeps its
   descriptors until it is collected — a start that fails at its port is
   such a follower. One that dialled keeps its client for the replies it
   still owes."
  []
  (let [unused (locking running
                 (when-let [{:keys [stopped attached stream dialling dialled client]} @running]
                   (reset! stopped true)
                   (session/fleet-hooks! nil)
                   (some-> @dialling (CompletableFuture/.cancel true))
                   (some-> @stream InputStream/.close)
                   (reset! attached nil)
                   (reset! running nil)
                   (when-not @dialled client)))]
    (some-> unused HttpClient/.close)
    nil))


(defn status
  "Whether this follower is attached right now, and to what."
  []
  (when-let [{:keys [name base attached]} @running]
    {:name name :gateway base :attached (some? @attached)}))
