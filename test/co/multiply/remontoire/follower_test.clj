(ns co.multiply.remontoire.follower-test
  "The follower role against a scripted gateway: a bare HTTP server the
   test owns, which records what the follower says and sends it what the
   test chooses. The follower itself is real — `init!` with `:follow` —
   and serves what it is sent through the ordinary handlers."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent.impl.executor :as q.exec]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.remontoire.fleet :as fleet]
    [co.multiply.remontoire.follower :as follower]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.test-util :as tu])
  (:import
    [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
    [java.io DataOutputStream IOException OutputStream]
    [java.nio.file Files LinkOption]
    [java.net InetSocketAddress URI]
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpRequest$Builder HttpResponse
     HttpResponse$BodyHandlers]
    [java.util.concurrent LinkedBlockingQueue TimeUnit]))


(def ^:dynamic ^String *base* nil)


;; What the scripted gateway heard, in order: [what message] for the small
;; messages, [:attach message] for each attach, [:answer {:rid :head :end :end? :body}].
(def ^:private heard (LinkedBlockingQueue.))


;; The streams of the attaches still open: {:send! (fn [event]), :close! (fn [])}.
(def ^:private attached (LinkedBlockingQueue.))


;; How the scripted gateway answers a registration: handle → status.
(def ^:private register-status (atom (constantly 204)))


;; The number on each thing a follower said of its sessions, as it arrived.
(def ^:private numbered (atom []))


;; When set, a promise a registration waits on before it is answered.
(def ^:private register-gate (atom nil))


;; What it answers a report with: the report → the handles to drop.
(def ^:private beat-retire (atom (constantly [])))


;; How it answers a part of an answer: the part, as `heard` records it → status.
(def ^:private answer-status (atom (constantly 204)))


;; What its welcome retires: the attach message → handles.
(def ^:private welcome-retire (atom (constantly [])))


;; When set, a promise an attach waits on before it is answered at all.
(def ^:private attach-gate (atom nil))


;; When true, an attach is answered with a stream whose head carries no
;; welcome, as anything else listening at the address might.
(def ^:private no-welcome (atom false))


;; When set, a promise: an attach is refused, and the refusal's body kept
;; open, and written to, until it is delivered.
(def ^:private refusal-gate (atom nil))


;; Delivered when a refusal's body could be written no further.
(def ^:private refusal-cut (atom nil))


;; Delivered when a refusal has been answered, its body begun.
(def ^:private refusing (atom nil))


;; What its welcome says beside what it retires: the attach message → a map.
;; The attachments are named a1, a2, … in the order they were welcomed.
(def ^:private welcome-with (atom nil))


(def ^:private welcomes (atom 0))


;; When set, a promise a part of an answer waits on before it is answered.
(def ^:private answer-gate (atom nil))


;; Delivered when a body being fetched could be written no further.
(def ^:private body-cut (atom nil))


;; What an eval of a test's waits on, for as long as the test wants it running.
(def hold (atom nil))


;; How it answers a fetch of a request's body: the exchange → nothing.
(def ^:private body-response (atom nil))


(defn- frames!
  "Answer `exchange` with `chunks` as the gateway frames a body, and the
   last frame only when `whole?`."
  [exchange chunks whole?]
  (HttpExchange/.sendResponseHeaders exchange 200 0)
  (with-open [out (DataOutputStream. (HttpExchange/.getResponseBody exchange))]
    (doseq [^bytes chunk chunks]
      (.writeInt out (alength chunk))
      (.write out chunk 0 (alength chunk)))
    (when whole? (.writeInt out 0))))


(defn- respond!
  [exchange status]
  (HttpExchange/.sendResponseHeaders exchange status -1)
  (HttpExchange/.close exchange))


(defn- gateway-handler
  ^HttpHandler []
  (reify HttpHandler
    (handle
      [_ exchange]
      (let [[_ what arg] (re-matches #"/gateway/([a-z]+)(?:/(.+))?" (str (HttpExchange/.getRequestURI exchange)))
            body    (slurp (HttpExchange/.getRequestBody exchange))
            message #(json/read-str body :key-fn keyword)]
        (case what
          "attach"
          (if-let [gate @refusal-gate]
            (let [_   (LinkedBlockingQueue/.put heard [:attach (message)])
                  out (do (HttpExchange/.sendResponseHeaders exchange 503 0)
                        (HttpExchange/.getResponseBody exchange))]
              (try
                (while (= ::held (deref gate 20 ::held))
                  (OutputStream/.write out (.getBytes "busy "))
                  (OutputStream/.flush out)
                  (deliver @refusing true))
                (catch IOException _ (deliver @refusal-cut true))
                (finally (HttpExchange/.close exchange))))
            (let [_      (swap! numbered conj (:seq (message)))
                  _      (LinkedBlockingQueue/.put heard [:attach (message)])
                  _      (some-> @attach-gate deref)
                  _      (when-not @no-welcome
                           (.set (HttpExchange/.getResponseHeaders exchange) "X-Welcome"
                             (json/write-str
                               (merge {:wire       fleet/wire
                                       :attachment (str "a" (swap! welcomes inc))
                                       :beat-ms    100}
                                 (some-> @welcome-with (apply [(message)]))))))
                  out    (do (HttpExchange/.sendResponseHeaders exchange 200 0)
                           (HttpExchange/.getResponseBody exchange))
                  send!  (fn [event]
                           (locking out
                             (OutputStream/.write out (.getBytes (str "data: " (json/write-str event) "\n\n")))
                             (OutputStream/.flush out)))
                  closed (promise)]
              ;; The server keeps the headers back until there is a byte to
              ;; send with them.
              (locking out
                (OutputStream/.write out (.getBytes ": attached\n\n"))
                (OutputStream/.flush out))
              ;; What the follower must drop, ahead of anything else.
              (when-not @no-welcome
                (doseq [handle (@welcome-retire (message))]
                  (send! {:type "retire" :handle handle})))
              ;; A gateway is never quiet for long; one that is, is taken for gone.
              (Thread/startVirtualThread
                #(try
                   (while (not (realized? closed))
                     (Thread/sleep 50)
                     (locking out
                       (OutputStream/.write out (.getBytes ": hb\n\n"))
                       (OutputStream/.flush out)))
                   (catch Exception _ nil)))
              (LinkedBlockingQueue/.put attached {:send! send! :close! #(deliver closed true)})
              @closed
              (HttpExchange/.close exchange)))

          "answer"
          (let [headers (HttpExchange/.getRequestHeaders exchange)
                part    {:rid  arg
                         :head (some-> (.getFirst headers "X-Head") (json/read-str :key-fn keyword))
                         :end  (.getFirst headers "X-End")
                         :end? (some? (.getFirst headers "X-End"))
                         :answers (.getFirst headers "X-Answers")
                         :body body}]
            (LinkedBlockingQueue/.put heard [:answer part])
            (some-> @answer-gate deref)
            (respond! exchange (@answer-status part)))

          "body" (@body-response exchange)

          "register"
          (let [m (message)]
            (swap! numbered conj (:seq m))
            (LinkedBlockingQueue/.put heard [:register m])
            (some-> @register-gate deref)
            (respond! exchange (@register-status (:handle m))))

          "beat"
          (let [m    (message)
                said (.getBytes (json/write-str {:retire (@beat-retire m)}))]
            (swap! numbered conj (:seq m))
            (LinkedBlockingQueue/.put heard [:beat m])
            (HttpExchange/.sendResponseHeaders exchange 200 (alength said))
            (with-open [out (HttpExchange/.getResponseBody exchange)]
              (.write out said)))

          (do (LinkedBlockingQueue/.put heard [(keyword what) (message)])
            (respond! exchange 204)))))))


(defn- hear
  "The next thing the scripted gateway heard of kind `what` — and that
   `pred` holds of, when given — skipping the rest: reports arrive
   whenever they like. nil when three seconds bring none."
  ([what] (hear what (constantly true)))
  ([what pred]
   (let [deadline (+ (System/currentTimeMillis) 3000)]
     (loop []
       (let [left (- deadline (System/currentTimeMillis))]
         (when (pos? left)
           (when-let [[kind m] (LinkedBlockingQueue/.poll heard left TimeUnit/MILLISECONDS)]
             (if (and (= what kind) (pred m)) m (recur)))))))))


(defn- attachment
  "The next attach stream, once the follower has taken its welcome."
  []
  (let [stream (LinkedBlockingQueue/.poll attached 3 TimeUnit/SECONDS)]
    (loop [n 0]
      (when (and (not (:attached (follower/status))) (< n 150))
        (Thread/sleep 20)
        (recur (inc n))))
    stream))


(use-fixtures :each
  (fn [f]
    (tu/reset-state!)
    (LinkedBlockingQueue/.clear heard)
    (LinkedBlockingQueue/.clear attached)
    (reset! register-status (constantly 204))
    (reset! register-gate nil)
    (reset! numbered [])
    (reset! beat-retire (constantly []))
    (reset! answer-status (constantly 204))
    (reset! welcome-retire (constantly []))
    (reset! attach-gate nil)
    (reset! no-welcome false)
    (reset! refusal-gate nil)
    (reset! refusal-cut (promise))
    (reset! refusing (promise))
    (reset! welcome-with nil)
    (reset! welcomes 0)
    (reset! answer-gate nil)
    (reset! body-cut (promise))
    (reset! hold (promise))
    (reset! body-response nil)
    (let [gateway (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                    (.createContext "/gateway" (gateway-handler))
                    (.setExecutor q.exec/virtual-executor)
                    (.start))]
      (try
        (with-redefs [follower/redial-ms 50
                      follower/opening-beat-ms 100]
          (binding [*base* (str "http://127.0.0.1:" (.getPort (.getAddress gateway)))]
            (f)))
        (finally
          (deliver @hold nil)
          (remontoire/stop!)
          (.stop gateway 0))))))


(defn- follow!
  [base]
  (remontoire/init! {:port 0 :follow base :name "f1"}))


(defn- request-event
  "An envelope for a legacy-wire `tools/call`, as a gateway forwards one."
  [rid tool arguments]
  {:type    "request"
   :rid     rid
   :method  "POST"
   :path    "/mcp"
   :headers {"Content-type" ["application/json"] "Host" ["gateway.example:7888"]}
   :peer    "10.0.0.9"
   :body    (json/write-str {:jsonrpc "2.0" :id 3 :method "tools/call"
                             :params  {:name tool :arguments arguments}})})


(defn- tool-call
  [id tool arguments]
  {:jsonrpc "2.0" :id id :method "tools/call" :params {:name tool :arguments arguments}})


(defn- direct!
  "POST `body`, as JSON, to the follower's own endpoint at `port`, as a
   client that reaches it without its gateway does. The response's body."
  [port body]
  (HttpResponse/.body
    (HttpClient/.send (HttpClient/newHttpClient)
      (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/mcp")))
        (HttpRequest$Builder/.header "Content-Type" "application/json")
        (HttpRequest$Builder/.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
        (HttpRequest$Builder/.build))
      (HttpResponse$BodyHandlers/ofString))))


(defn- answer-of
  "The whole answer to request `rid`, its parts joined."
  [rid]
  (loop [answer {:body ""}]
    (let [{:keys [head end? body] :as part} (hear :answer)]
      (cond
        (nil? part) answer
        (not= rid (:rid part)) (recur answer)
        :else (let [answer (cond-> (update answer :body str body) head (assoc :head head))]
                (if end? answer (recur answer)))))))


(defn- eventually
  [pred]
  (loop [n 0]
    (cond (pred) true (< 150 n) false :else (do (Thread/sleep 20) (recur (inc n))))))


(deftest a-follower-names-itself-and-reports-its-sessions
  (let [base *base*]
    (let [before (session/mint!)]
      (follow! base)
      (let [{:keys [name sessions wire]} (hear :attach)]
        (is (= "f1" name))
        (is (= [before] sessions) "what it holds when it attaches is what a gateway routes by")
        (is (= fleet/wire wire) "and it says which revision of the wire it speaks")))))


(deftest what-the-gateway-sends-is-served-and-answered
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)]
      (send! (request-event "r1" "create-session" {}))
      (let [{:keys [handle]} (hear :register)
            {:keys [head body]} (answer-of "r1")]
        (is (session/live? handle) "the session is minted here")
        (is (= 200 (:status head)))
        (is (str/includes? body handle) "and claimed from the gateway before the client is given it")
        (send! (request-event "r2" "eval" {:code "(+ 1 2)" :session-id handle}))
        (is (str/includes? (:body (answer-of "r2")) "3") "the ordinary handlers serve the envelope")))))


(deftest a-handle-the-fleet-holds-is-redrawn
  (let [base *base*]
    (let [refused (atom nil)]
      ;; The first handle offered is taken; whatever comes after is free.
      (reset! register-status #(if (compare-and-set! refused nil %) 409 (if (= % @refused) 409 204)))
      (follow! base)
      (attachment)
      (let [kept (session/mint!)]
        (is (some? @refused))
        (is (not= kept @refused))
        (is (not (session/live? @refused)) "the refused handle never was a session")
        (is (session/live? kept))))))


(deftest a-session-is-minted-while-the-gateway-is-away
  (do
    ;; Nothing listens at the address followed.
    (remontoire/init! {:port 0 :follow "http://127.0.0.1:9" :name "f1"})
    (is (session/live? (session/mint!)) "a gateway outage is when a direct session is most needed")))


(deftest a-handle-the-gateway-retires-is-dropped
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid (session/mint!)]
      (is (some? (hear :beat #(some #{sid} (:sessions %)))))
      (send! {:type "retire" :handle sid})
      (is (some? (hear :beat #(not-any? #{sid} (:sessions %))))
        "and it is reported no more, which is what ends the retirement")
      (is (not (session/live? sid))))))


(deftest a-handle-retired-at-the-attach-is-dropped-and-said-so
  (let [base *base*
        sid  (session/mint!)]
    (reset! welcome-retire :sessions)
    (follow! base)
    (attachment)
    (is (some? (hear :beat #(= [] (:sessions %)))) "a report says it is held no more")
    (is (not (session/live? sid)))))


(deftest a-handle-retired-twice-is-retired-once
  ;; The gateway says what to drop behind the welcome, and again in its
  ;; answer to a report that still names the handle. Which of the two a
  ;; follower acts on first is not settled, and neither order may refresh
  ;; what the session left behind.
  (let [base    *base*
        release registry/release-session!
        after   (atom [])
        left    (atom nil)
        ;; A session with a result it has read, which is what it leaves.
        told    #(let [sid (session/mint!)]
                   (reset! left (tu/id-of (tu/call! sid "eval" {:code ":kept"})))
                   sid)]
    ;; When each retirement was over, and how lately its result was touched.
    (with-redefs [registry/release-session! (fn [handle]
                                              (release handle)
                                              (swap! after conj [handle (:touched (registry/entry @left))]))]
      (follow! base)
      (let [{:keys [send!]} (attachment)
            sid (told)]
        ;; By a report's answer, then by an event.
        (reset! beat-retire (fn [m] (filterv #{sid} (:sessions m))))
        (is (eventually #(= 1 (count @after))) "the answer to a report retired it")
        (send! {:type "retire" :handle sid})
        (is (eventually #(= 2 (count @after))) "and an event asked again")
        (is (apply = (map second @after)) "to no effect on what it left")
        (is (not (session/live? sid)))
        ;; By an event, then by a report's answer.
        (reset! beat-retire (constantly []))
        (reset! after [])
        (let [other (told)]
          (is (some? (hear :beat #(some #{other} (:sessions %)))))
          (send! {:type "retire" :handle other})
          (is (eventually #(= 1 (count @after))) "an event retired it")
          (reset! beat-retire (constantly [other]))
          (is (eventually #(<= 2 (count @after))) "and the answer to a report asked again")
          (is (apply = (map second @after)) "to no effect on what the first left")
          (is (not (session/live? other))))))))


(deftest a-handle-given-out-without-the-gateways-word-is-settled-by-the-next-report
  (let [base *base*]
    (follow! base)
    (attachment)
    (reset! register-status (constantly 503))
    (let [kept (session/mint!)
          lost (session/mint!)]
      (is (session/live? kept) "a direct session is given out all the same")
      (is (some? (hear :beat #(= #{kept lost} (set (:sessions %))))) "and reported with the rest")
      ;; It turns out one of the two was somebody else's.
      (reset! beat-retire (fn [{:keys [sessions]}] (filterv #{lost} sessions)))
      (is (eventually #(not (session/live? lost))) "which the answer to a report says, and it is dropped")
      (is (session/live? kept)))))


(deftest a-report-is-not-read-while-a-handle-is-asked-for
  ;; A report read before the gateway grants a handle and sent after it
  ;; says the handle is not held, and the gateway stops routing it.
  (let [base *base*
        gate (promise)]
    (follow! base)
    (attachment)
    ;; Reports are coming at the gateway's cadence.
    (hear :beat)
    (hear :beat)
    (hear :beat)
    (reset! register-gate gate)
    (let [minting (future (session/mint!))
          handle  (:handle (hear :register))]
      (LinkedBlockingQueue/.clear heard)
      (Thread/sleep 400)
      (is (nil? (some (fn [[kind]] (when (= :beat kind) kind)) (vec heard)))
        "four beats' time, and none was sent")
      (deliver gate true)
      (is (= handle (deref minting 3000 nil)))
      (is (some #{handle} (:sessions (hear :beat))) "the first one after it holds the handle"))))


(deftest what-a-follower-says-of-its-sessions-is-numbered-in-order
  ;; An attach, a registration and a report each say what the follower
  ;; holds, and a gateway acts on them in the order they were read.
  (let [base *base*]
    (follow! base)
    (let [{:keys [close!]} (attachment)]
      (session/mint!)
      (hear :beat)
      (hear :beat)
      (close!)
      (hear :attach #(< 1 (:seq %)))
      (hear :beat))
    (let [seqs @numbered]
      (is (<= 6 (count seqs)))
      (is (apply < seqs)))))


(deftest a-session-opened-through-the-gateway-needs-its-word
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)]
      (reset! register-status (constantly 503))
      (send! (request-event "r1" "create-session" {}))
      (let [{:keys [body]} (answer-of "r1")]
        (is (tu/tool-error? (json/read-str body :key-fn keyword))
          "a handle the gateway cannot route is not given to a client that comes through it")
        (is (empty? (session/handles)) "and nothing is left behind")))))


(deftest a-lost-part-is-followed-by-giving-the-answer-up
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid (session/mint!)]
      ;; The answer's one part is lost; what comes after is heard.
      (reset! answer-status #(if (= "1" (:end %)) 500 204))
      (send! (request-event "r1" "eval" {:code "(+ 1 2)" :session-id sid}))
      (is (= "abort" (loop []
                       (let [{:keys [rid end] :as part} (hear :answer)]
                         (cond (nil? part) nil
                               (and (= "r1" rid) (not= "1" end)) end
                               :else (recur)))))
        "the gateway is not left waiting on an answer nobody is sending"))))


(deftest an-abort-the-gateway-does-not-confirm-drops-the-attachment
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid (session/mint!)]
      (is (some? (hear :attach)))
      ;; The gateway answers, and never with its word on the request.
      (reset! answer-status (constantly 503))
      (send! (request-event "r1" "eval" {:code "(+ 1 2)" :session-id sid}))
      (is (= "abort" (:end (hear :answer #(= "abort" (:end %))))))
      (is (some? (hear :attach)) "so nothing waits on this attachment for an answer nobody is sending"))))


(deftest an-end-that-was-lost-is-said-again-as-an-end
  (let [base *base*
        lost (atom false)]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid (session/mint!)]
      ;; An event stream, whose last part carries nothing but the end —
      ;; lost once, when every byte of the answer has reached the client.
      (reset! answer-status (fn [{:keys [end head body]}]
                              (if (and (= "1" end) (nil? head) (= "" body) (compare-and-set! lost false true))
                                500
                                204)))
      (send! (assoc-in (request-event "r1" "eval" {:code "(+ 1 2)" :session-id sid})
               [:headers "Accept"] ["application/json, text/event-stream"]))
      (let [ending #(:end (hear :answer (fn [part] (and (= "r1" (:rid part)) (:end part)))))]
        (is (= ["1" "1"] [(ending) (ending)]) "not given up: nothing of it is missing")))))


(defn- parts-of
  "Every part of the answer to request `rid`, to its end."
  [rid]
  (loop [parts []]
    (let [part (hear :answer #(= rid (:rid %)))]
      (cond
        (nil? part) parts
        (:end? part) (conj parts part)
        :else (recur (conj parts part))))))


(deftest a-part-says-it-carries-the-response
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid    (session/mint!)
          asked  (fn [rid id]
                   (send! (-> (request-event rid "eval" {:code "(+ 1 2)" :session-id sid})
                            (assoc-in [:headers "Accept"] ["application/json, text/event-stream"])
                            (update :body str/replace "\"id\":3" (str "\"id\":" (json/write-str id)))))
                   (filterv :answers (parts-of rid)))
          long   (apply str (repeat 2000 "x"))
          short  (asked "r1" 3)
          longer (asked "r2" long)]
      (is (= 1 (count short)) "the request is said to be answered once, by one part")
      (is (= 3 (some-> (first short) :body (subs 6) (json/read-str :key-fn keyword) :id))
        "which is the part its response is in")
      (is (= long (some-> (first longer) :body (subs 6) (json/read-str :key-fn keyword) :id)))
      (is (= (:answers (first short)) (:answers (first longer)))
        "in the same word whatever the client called its request: the id rides in the body"))))


(deftest a-result-whose-acknowledgement-was-lost-stays-unread
  ;; The gateway wrote the response to the client and its word that it
  ;; had never arrived here. Which of the two happened is not known to a
  ;; follower, and it keeps the result as one nobody read.
  (let [base *base*
        told #(let [{:keys [send!]} (attachment)
                    sid (session/mint!)]
                (send! (assoc-in (request-event "r1" "eval" {:code ":kept" :session-id sid})
                         [:headers "Accept"] ["application/json, text/event-stream"]))
                (let [parts (parts-of "r1")]
                  [sid parts]))]
    (follow! base)
    (reset! answer-status #(if (:answers %) 500 204))
    (let [[sid parts] (told)
          until (fn [pred] (loop [n 0] (cond (pred) true (< 300 n) false :else (do (Thread/sleep 10) (recur (inc n))))))]
      (is (= "abort" (:end (peek parts))) "the answer is given up, for the gateway to finish")
      (is (until #(= [1] (mapv :id (registry/owed sid)))) "and the result is owed still")
      (is (nil? (:touched (registry/entry 1)))))))


(deftest a-result-the-gateway-acknowledged-is-read
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid   (session/mint!)
          until (fn [pred] (loop [n 0] (cond (pred) true (< 300 n) false :else (do (Thread/sleep 10) (recur (inc n))))))]
      (send! (assoc-in (request-event "r1" "eval" {:code ":read" :session-id sid})
               [:headers "Accept"] ["application/json, text/event-stream"]))
      (is (= "1" (:end (peek (parts-of "r1")))))
      (is (until #(some? (:touched (registry/entry 1)))))
      (is (= [] (registry/owed sid))))))


(defn- upload-event
  [rid sid]
  {:type "request" :rid rid :method "PUT" :path (str "/upload/" sid "/note.txt") :headers {} :peer "10.0.0.9"})


(defn- staged
  "What is staged as `note.txt` for `sid`, or nil."
  [sid]
  (when-let [dir (session/upload-dir sid)]
    (let [file (.resolve dir "note.txt")]
      (when (Files/exists file (make-array LinkOption 0))
        (String. (Files/readAllBytes file) "UTF-8")))))


(deftest a-stream-the-gateway-could-not-deliver-cancels-its-eval
  ;; On the stateless wire a client cancels by closing its stream (§5).
  ;; Through a gateway it is the gateway's client that closes, and what a
  ;; follower sees of it is a part the gateway answers as undelivered.
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid    (session/mint!)
          status #(try (registry/status (registry/task 1))
                    (catch clojure.lang.ExceptionInfo _ nil))
          until  (fn [pred] (loop [n 0] (cond (pred) true (< 500 n) false :else (do (Thread/sleep 10) (recur (inc n))))))]
      ;; The stream opens for the client, who is gone for all that follows.
      (reset! answer-status #(if (:head %) 204 499))
      (send! {:type    "request"
              :rid     "r1"
              :method  "POST"
              :path    "/mcp"
              :headers {"Content-type"         ["application/json"]
                        "Accept"               ["application/json, text/event-stream"]
                        "Host"                 ["gateway.example:7888"]
                        "Mcp-protocol-version" [mcp/protocol-version]
                        "Mcp-method"           ["tools/call"]
                        "Mcp-name"             ["eval"]}
              :peer    "10.0.0.9"
              :body    (json/write-str
                         {:jsonrpc "2.0" :id 3 :method "tools/call"
                          :params  {:name      "eval"
                                    :arguments {:code "(Thread/sleep 30000)" :await 20000 :session-id sid}
                                    ;; A string: `write-str` drops a keyword's namespace.
                                    :_meta     {"io.modelcontextprotocol/protocolVersion" mcp/protocol-version}}})})
      (is (until #(= :running (status))) "the eval began")
      (is (until #(= :cancelled (status)))))))


(deftest a-cancellation-finds-the-request-that-arrived-before-it
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid      (session/mint!)
          envelope (fn [rid message]
                     {:type    "request"
                      :rid     rid
                      :method  "POST"
                      :path    "/mcp"
                      :headers {"Content-type"   ["application/json"]
                                "Host"           ["gateway.example:7888"]
                                "Mcp-session-id" ["c1"]}
                      :peer    "10.0.0.9"
                      :body    (json/write-str message)})
          status   #(try (registry/status (registry/task 1))
                      (catch clojure.lang.ExceptionInfo _ nil))
          until    (fn [pred] (loop [n 0] (cond (pred) true (< 300 n) false :else (do (Thread/sleep 10) (recur (inc n))))))
          serve!   @#'follower/serve!
          noticed  (promise)]
      ;; Each envelope is served on a thread of its own, and the notice's
      ;; runs to its end before the request's has begun.
      (with-redefs-fn {#'follower/serve! (fn [node {:keys [rid] :as event} & more]
                                           (when (= "r1" rid) (deref noticed 3000 nil))
                                           (try (apply serve! node event more)
                                             (finally (when (= "r2" rid) (deliver noticed true)))))}
        (fn []
          (send! (envelope "r1" {:jsonrpc "2.0" :id 3 :method "tools/call"
                                 :params  {:name      "eval"
                                           :arguments {:code "(Thread/sleep 20000) :slept" :await 15000 :session-id sid}}}))
          (send! (envelope "r2" {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 3}}))
          (is (true? (deref noticed 3000 nil)) "the notice was served first")
          (is (until #(= :cancelled (status)))
            "and stopped the request all the same: it arrived after it, and found it")
          (is (some? (:head (answer-of "r1"))))
          (is (until #(zero? (mcp/waiting "c1" 3))) "and once it is answered, a notice names it no more"))))))


(deftest only-a-whole-body-is-staged
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid (session/mint!)]
      (reset! body-response #(frames! % [(.getBytes "first, ") (.getBytes "whole")] true))
      (send! (upload-event "u1" sid))
      (is (= 201 (:status (:head (answer-of "u1")))))
      (is (= "first, whole" (staged sid)))
      ;; The gateway's own words, where a body was asked for.
      (reset! body-response (fn [exchange]
                              (let [said (.getBytes "No such request waiting\n")]
                                (HttpExchange/.sendResponseHeaders exchange 404 (alength said))
                                (with-open [out (HttpExchange/.getResponseBody exchange)]
                                  (.write out said)))))
      (send! (upload-event "u2" sid))
      (is (not= 201 (:status (:head (answer-of "u2")))))
      (is (= "first, whole" (staged sid)) "a refusal is not a file")
      ;; A body cut short on its way in to the gateway.
      (reset! body-response #(frames! % [(.getBytes "half")] false))
      (send! (upload-event "u3" sid))
      (is (not= 201 (:status (:head (answer-of "u3")))))
      (is (= "first, whole" (staged sid)) "nor is half of one")
      ;; Framed, by something that frames otherwise than a gateway does.
      (reset! body-response #(frames! % [(byte-array (inc fleet/frame-bytes))] true))
      (send! (upload-event "u4" sid))
      (is (not= 201 (:status (:head (answer-of "u4")))))
      (is (= "first, whole" (staged sid)) "nor a frame longer than a gateway writes"))))


(deftest a-follower-that-stopped-serves-nothing-that-arrives-late
  (let [base *base*
        gate (promise)]
    (reset! attach-gate gate)
    (follow! base)
    (is (some? (hear :attach)) "the attach is on its way, unanswered")
    (remontoire/stop!)
    (deliver gate true)
    ;; The welcome may still be written; whatever follows it must not be acted on.
    (when-let [{:keys [send!]} (LinkedBlockingQueue/.poll attached 500 TimeUnit/MILLISECONDS)]
      (try (send! {:type "ping" :nonce "late"}) (catch Exception _ nil)))
    (is (nil? (LinkedBlockingQueue/.poll heard 400 TimeUnit/MILLISECONDS)) "no pong, no beat, no register")
    (is (nil? (follower/status)))))


(deftest a-follower-that-stops-as-its-attach-is-answered-takes-no-welcome
  (let [base       *base*
        welcome-in @#'follower/welcome-in
        read       (promise)
        sid        (session/mint!)]
    (reset! welcome-retire :sessions)
    ;; Stopped with the answer in hand, its welcome read and not yet taken.
    (with-redefs-fn {#'follower/welcome-in (fn [response]
                                             (let [welcome (welcome-in response)]
                                               (follower/stop!)
                                               (deliver read welcome)
                                               welcome))}
      (fn []
        (follow! base)
        (is (some? (hear :attach)))
        (is (some? (deref read 3000 nil)) "the answer carried a welcome")
        (is (nil? (LinkedBlockingQueue/.poll heard 400 TimeUnit/MILLISECONDS)) "and nothing was said under it")
        (is (session/live? sid) "nor was anything read from its stream, which retires a session")
        (is (nil? (follower/status)))))))


(deftest a-stopped-follower-takes-no-welcome
  (let [base *base*]
    (follow! base)
    (attachment)
    (let [node @@#'follower/running]
      (follower/stop!)
      (is (nil? (#'follower/welcome! node {:wire fleet/wire :attachment "late" :beat-ms 100}))
        "one that arrives for it is not taken")
      (is (nil? @(:attached node)) "and attaches it to nothing"))))


(deftest a-retirement-under-way-is-over-before-a-stop-returns
  (let [base    *base*
        release registry/release-session!
        sid     (session/mint!)
        inside  (promise)
        go      (promise)]
    (reset! welcome-retire :sessions)
    ;; Held in the middle of what the gateway asked for.
    (with-redefs [registry/release-session! (fn [handle]
                                              (deliver inside true)
                                              (deref go 3000 nil)
                                              (release handle))]
      (follow! base)
      (is (true? (deref inside 3000 nil)) "the gateway's word is being acted on")
      (let [stopped (future (follower/stop!) (session/live? sid))]
        (is (= ::held (deref stopped 300 ::held)) "a stop waits for it")
        (deliver go true)
        (is (false? (deref stopped 3000 nil)) "and returns to a session that is gone")))))


(deftest a-report-answered-after-a-stop-retires-nothing
  (let [base    *base*
        report! @#'follower/report!
        sid     (session/mint!)
        done    (promise)]
    ;; The gateway answers a report with a retirement, to an instance that
    ;; let it go while the answer was on its way.
    (reset! beat-retire (fn [_] (follower/stop!) [sid]))
    (with-redefs-fn {#'follower/report! (fn [node attachment]
                                          (try (report! node attachment)
                                            (finally (deliver done true))))}
      (fn []
        (follow! base)
        (is (true? (deref done 3000 nil)) "the report was answered, and its answer read")
        (is (session/live? sid) "to no effect")))))


(defn- a-registration-answered-after-a-stop
  "Hold a mint where its follower asks the gateway for the handle, stop
   the follower, let another answer for the JVM's sessions, and have the
   gateway answer `status`. What the mint came to."
  [status]
  (let [base *base*
        gate (promise)]
    (follow! base)
    (attachment)
    (reset! register-gate gate)
    (reset! register-status (constantly status))
    (let [minted (future (try (session/mint!) (catch clojure.lang.ExceptionInfo _ ::refused)))]
      (is (some? (hear :register)) "the handle is being asked for")
      (follower/stop!)
      ;; Whoever answers for the JVM's sessions next, and would give any
      ;; handle out: the mint was not a request of theirs.
      (session/fleet-hooks! {:claim (fn [_ take!] (take!) true)})
      (try
        (deliver gate true)
        (deref minted 3000 nil)
        (finally (session/fleet-hooks! nil))))))


(deftest a-registration-granted-after-a-stop-opens-no-session
  (is (= ::refused (a-registration-answered-after-a-stop 204)) "the mint fails")
  (is (empty? (session/handles)) "and leaves no session of this JVM's behind"))


(deftest a-registration-refused-after-a-stop-opens-no-session
  ;; Refused, the handle is drawn again, and the second draw asks the
  ;; follower the mint began with, not whoever has taken its place.
  (is (= ::refused (a-registration-answered-after-a-stop 409)) "the mint fails")
  (is (empty? (session/handles)) "and leaves no session of this JVM's behind"))


(deftest what-a-follower-takes-off-its-gateway-is-its-own-to-answer
  ;; Whoever answers for the JVM's sessions when the request is served,
  ;; and would give out anything without a word to the gateway.
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)]
      (session/fleet-hooks! {:claim (fn [_ take!] (take!) true)})
      (try
        (send! (request-event "r1" "create-session" {}))
        (let [asked  (:handle (hear :register))
              answer (:body (answer-of "r1"))]
          (is (some? asked) "the follower asked its gateway for the handle")
          (is (str/includes? answer (str asked)) "which is the one the client was given"))
        (finally (session/fleet-hooks! nil))))))


(deftest a-batch-is-served-as-the-follower-that-took-it
  ;; One post, two calls, nothing held: the first lets the gateway go and
  ;; leaves another answering for the JVM's sessions, which would give out
  ;; anything. The second asks for a session, and was asked of the first.
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid   (session/mint!)
          call  (fn [id tool arguments]
                  {:jsonrpc "2.0" :id id :method "tools/call" :params {:name tool :arguments arguments}})
          leave (str "(co.multiply.remontoire.follower/stop!) "
                  "(co.multiply.remontoire.session/fleet-hooks! {:claim (fn [_ take!] (take!) true)}) "
                  ":left")]
      (try
        (send! (assoc (request-event "r1" "eval" {})
                 :body (json/write-str [(call 1 "eval" {:code leave :session-id sid})
                                        (call 2 "create-session" {})])))
        (let [[left opened] (json/read-str (:body (answer-of "r1")) :key-fn keyword)]
          (is (str/starts-with? (str (get-in left [:result :content 0 :text])) ":left") "the first call was served")
          (is (true? (get-in opened [:result :isError])) "the second is refused")
          (is (= [sid] (session/handles)) "and no session was opened for it under anyone else's word"))
        (finally (session/fleet-hooks! nil))))))


(deftest a-batch-at-a-followers-own-port-is-served-as-that-follower
  ;; The same post, made of the follower directly: its port was opened for
  ;; that follower, and what arrives there is asked of it too.
  (let [base *base*
        port (get-in (follow! base) [:opts :port])]
    (attachment)
    (let [sid   (session/mint!)
          leave (str "(co.multiply.remontoire.follower/stop!) "
                  "(co.multiply.remontoire.session/fleet-hooks! {:claim (fn [_ take!] (take!) true)}) "
                  ":left")]
      (try
        (let [[left opened] (json/read-str (direct! port [(tool-call 1 "eval" {:code leave :session-id sid})
                                                          (tool-call 2 "create-session" {})])
                              :key-fn keyword)]
          (is (str/starts-with? (str (get-in left [:result :content 0 :text])) ":left") "the first call was served")
          (is (true? (get-in opened [:result :isError])) "the second is refused")
          (is (= [sid] (session/handles)) "and no session was opened for it under anyone else's word"))
        (finally (session/fleet-hooks! nil))))))


(deftest a-request-that-arrives-before-the-dial-is-the-followers-to-answer
  ;; The port answers before the gateway is dialled. A request for a
  ;; session that arrives then is held at its mint until the follower has
  ;; attached. It asks the gateway for the handle, as anything asked of a
  ;; follower does: accepted by an instance alone, it would give the
  ;; handle out on nobody's word, and the gateway could not route it.
  (let [base    *base*
        start!  http/start!
        attach! follower/attach!
        mint!   session/mint!
        server  (promise)
        dial    (promise)
        arrived (promise)
        go      (promise)]
    (with-redefs-fn {#'http/start!      (fn [& args] (doto (apply start! args) (->> (deliver server))))
                     #'follower/attach! (fn [] @dial (attach!))
                     #'session/mint!    (fn [& args] (deliver arrived true) @go (apply mint! args))}
      (fn []
        (let [starting (future (follow! base))]
          ;; Everything that waits is released on the way out, whichever
          ;; step did not come: a start left held keeps what a stop needs.
          (try
            (let [opened (deref server 3000 nil)]
              (is (some? opened) "the port is opened before the gateway is dialled")
              (when opened
                (let [port  (InetSocketAddress/.getPort (http/address opened))
                      asked (future (direct! port (tool-call 1 "create-session" {})))]
                  (is (true? (deref arrived 3000 nil)) "and answers: a request for a session is in")
                  (is (nil? (LinkedBlockingQueue/.peek heard)) "the gateway having heard nothing")
                  (deliver dial true)
                  (is (some? (deref starting 3000 nil)) "the start returns once it has dialled")
                  (is (some? (attachment)) "the follower attaches")
                  (deliver go true)
                  (let [handle (:handle (hear :register))]
                    (is (some? handle) "and the session is asked of its gateway")
                    (is (str/includes? (str (deref asked 3000 nil)) (str handle))
                      "under the handle the client is given")))))
            (finally
              (deliver dial true)
              (deliver go true)
              (deref starting 3000 nil))))))))


(deftest a-session-is-said-to-be-on-the-node-that-claimed-it
  ;; Held where the gateway is asked for the handle, while the JVM comes
  ;; to go by another name. The gateway routes the handle to the name it
  ;; was asked under, and that is the name the client is told.
  (let [base *base*
        gate (promise)]
    (follow! base)
    (let [{:keys [send!]} (attachment)]
      (reset! register-gate gate)
      (send! (request-event "r1" "create-session" {}))
      (let [{:keys [name handle]} (hear :register)]
        (try
          (fleet/configure! :follower "replacement")
          (deliver gate true)
          (let [answer (:body (answer-of "r1"))]
            (is (= "f1" name) "the handle is asked for under the follower's name")
            (is (str/includes? answer (str handle)))
            (is (str/includes? answer "f1") "which is where the client is told its session is")
            (is (not (str/includes? answer "replacement")) "whatever the JVM is called by then"))
          (finally (deliver gate true)))))))


(deftest a-dial-being-begun-is-begun-before-a-stop-returns
  (let [base    *base*
        version mcp/version
        inside  (promise)
        go      (promise)]
    ;; Held where an attach has read what it reports and not yet dialled:
    ;; the version is the next thing it puts in its message.
    (with-redefs [mcp/version (fn []
                                (deliver inside true)
                                (deref go 3000 nil)
                                (version))]
      (follow! base)
      (is (true? (deref inside 3000 nil)) "an attach is being begun")
      (let [stopping (future (follower/stop!) ::returned)]
        (is (= ::held (deref stopping 300 ::held)) "a stop waits for it")
        (deliver go true)
        (is (= ::returned (deref stopping 3000 nil)) "and returns once it is begun, to cancel it")
        (is (nil? (follower/status)))))))


(defn- said-of
  "Whether anything the scripted gateway has heard so far, of kind `what`,
   names `handle` among a follower's sessions."
  [what handle]
  (boolean (some (fn [[kind m]] (and (= what kind) (some #{handle} (:sessions m)))) (vec heard))))


(deftest a-report-made-after-a-stop-says-nothing
  (let [base    *base*
        report! @#'follower/report!
        gate    (promise)
        entered (atom 0)
        left    (atom 0)]
    ;; Held before it has read anything: the report a welcome schedules.
    (with-redefs-fn {#'follower/report! (fn [node attachment]
                                          (swap! entered inc)
                                          (deref gate 3000 nil)
                                          (try (report! node attachment)
                                            (finally (swap! left inc))))}
      (fn []
        (follow! base)
        (is (eventually #(pos? @entered)) "a report is about to be made")
        (follower/stop!)
        ;; A session of the JVM's, and none of the follower's business.
        (let [sid (session/mint!)]
          (deliver gate true)
          (is (eventually #(= @entered @left)) "the report has run its course")
          (is (not (said-of :beat sid)) "and said nothing of a session opened since the stop"))))))


(deftest an-attach-made-after-a-stop-says-nothing
  (let [base         *base*
        attach-once! @#'follower/attach-once!
        gate         (promise)
        entered      (promise)
        left         (promise)]
    ;; Held between the look at whether to dial and the dialling.
    (with-redefs-fn {#'follower/attach-once! (fn [node]
                                               (deliver entered true)
                                               (deref gate 3000 nil)
                                               (try (attach-once! node)
                                                 (finally (deliver left true))))}
      (fn []
        (follow! base)
        (is (true? (deref entered 3000 nil)) "an attach is about to be made")
        (follower/stop!)
        (let [sid (session/mint!)]
          (deliver gate true)
          (is (true? (deref left 3000 nil)) "the attach has run its course")
          (is (not (said-of :attach sid)) "and reported no session opened since the stop")
          (is (not-any? (fn [[kind]] (= :attach kind)) (vec heard)) "nor was the gateway dialled at all"))))))


(deftest a-session-that-ends-is-reported-no-more
  (let [base *base*]
    (follow! base)
    (attachment)
    (let [sid (session/mint!)]
      (is (some? (hear :beat #(some #{sid} (:sessions %)))))
      (session/drop! sid)
      (is (some? (hear :beat #(not-any? #{sid} (:sessions %))))))))


(deftest a-ping-is-answered
  (let [base *base*]
    (follow! base)
    ((:send! (attachment)) {:type "ping" :nonce "n-1"})
    (is (= "n-1" (:nonce (hear :pong))))))


(deftest a-follower-redials-when-its-stream-ends
  (let [base *base*]
    (follow! base)
    (hear :attach)
    (let [sid (session/mint!)]
      ((:close! (attachment)))
      (is (= [sid] (:sessions (hear :attach))) "and reports afresh, which is all a restarted gateway needs"))))


(deftest what-a-follower-says-names-the-attachment-it-says-it-under
  (let [base *base*]
    (follow! base)
    (let [{:keys [close!]} (attachment)
          sid (session/mint!)]
      (is (= "a1" (:attachment (hear :register #(= sid (:handle %))))))
      (is (= "a1" (:attachment (hear :beat))))
      (close!)
      (attachment)
      (is (= "a2" (:attachment (hear :beat #(not= "a1" (:attachment %)))))
        "attached afresh, it speaks for the new one"))))


(deftest a-welcome-in-another-wire-is-not-an-attachment
  (let [base *base*]
    (reset! welcome-with (constantly {:wire nil}))
    (follow! base)
    (is (some? (hear :attach)))
    (is (some? (hear :attach)) "it dials again")
    (is (not (:attached (follower/status))) "and never took itself for attached")))


(deftest a-welcome-that-names-no-attachment-is-not-acted-on
  (let [base *base*
        sid  (session/mint!)]
    (reset! welcome-with (constantly {:attachment nil}))
    (reset! welcome-retire (constantly [sid]))
    (follow! base)
    (is (some? (hear :attach)))
    (is (some? (hear :attach)) "it dials again")
    (is (session/live? sid) "and has dropped nothing on that gateway's word")))


(deftest an-answer-that-carries-no-welcome-is-not-an-attachment
  (let [base *base*]
    (reset! no-welcome true)
    ;; The watch for a gateway gone quiet is minutes away: whatever drops
    ;; this answer, it is not that.
    (with-redefs [follower/opening-beat-ms 60000]
      (follow! base)
      (is (some? (hear :attach)))
      (let [began (System/currentTimeMillis)]
        ;; A stream is held open all the same, and asks.
        (when-let [{:keys [send!]} (LinkedBlockingQueue/.poll attached 3 TimeUnit/SECONDS)]
          (try (send! {:type "ping" :nonce "early"}) (catch Exception _ nil)))
        (let [deadline (+ began 3000)
              said     (loop [said []]
                         (let [left (- deadline (System/currentTimeMillis))
                               kind (first (LinkedBlockingQueue/.poll heard (max 0 left) TimeUnit/MILLISECONDS))]
                           (if (or (nil? kind) (= :attach kind) (not (pos? left)))
                             (conj said kind)
                             (recur (conj said kind)))))]
          (is (= :attach (peek said)) "it is dialled again as it is read, with no watch to wait out")
          (is (not-any? #{:pong} said) "and nothing it sent was served")
          (is (not (:attached (follower/status)))))))))


(deftest a-refusal-that-does-not-end-is-not-waited-on
  (let [base *base*
        gate (promise)]
    (reset! refusal-gate gate)
    (follow! base)
    (is (some? (hear :attach)))
    (is (some? (hear :attach)) "it dials again, however long the gateway takes over saying no")
    (deliver gate true)))


(deftest a-refusal-being-read-is-dropped-when-the-follower-stops
  (let [base *base*
        gate (promise)]
    (reset! refusal-gate gate)
    ;; Nothing but the stop is left to end it.
    (with-redefs [follower/opening-beat-ms 60000]
      (follow! base)
      (is (true? (deref @refusing 3000 nil)))
      ;; Past the wait for an answer, which a stop ends by other means.
      (let [dialling (:dialling @@#'follower/running)]
        (loop [n 0]
          (when (and @dialling (< n 150))
            (Thread/sleep 20)
            (recur (inc n))))
        (is (nil? @dialling) "the answer has arrived, and its body is what is left"))
      (remontoire/stop!)
      (is (true? (deref @refusal-cut 2000 nil)) "the body it was reading is closed"))
    (deliver gate true)))


(deftest a-part-that-is-lost-gives-the-answer-up-while-its-eval-runs-on
  ;; On the legacy wire a write that fails cancels nothing (§5), and the
  ;; exchange is closed only when the eval has its answer — which the
  ;; client, on a stream nothing more can be written to, must not wait for.
  (let [base *base*]
    (follow! base)
    (let [{:keys [send!]} (attachment)
          sid (session/mint!)]
      ;; The stream opens; the first beat after it is lost, and nothing else.
      (reset! answer-status #(if (or (:head %) (:end %)) 204 500))
      (send! (assoc-in (request-event "r1" "eval"
                         {:code       "(do (println \"said\") @@co.multiply.remontoire.follower-test/hold)"
                          :await      20000
                          :session-id sid})
               [:headers "Accept"] ["application/json, text/event-stream"]))
      (is (= "abort" (:end (hear :answer #(and (= "r1" (:rid %)) (:end %))))))
      (is (not (realized? @hold)) "said while the eval was running still"))))


(deftest an-answer-given-up-late-leaves-a-later-attachment-alone
  (let [base *base*
        gate (promise)]
    (follow! base)
    (let [{:keys [send! close!]} (attachment)
          sid (session/mint!)]
      (is (some? (hear :attach)))
      ;; The answer's first part is on its way when the attachment ends.
      (reset! answer-gate gate)
      (send! (request-event "r1" "eval" {:code "(+ 1 2)" :session-id sid}))
      (is (some? (hear :answer #(= "r1" (:rid %)))))
      (close!)
      (attachment)
      (is (some? (hear :attach)) "attached afresh")
      ;; Now the gateway answers, and never with its word on the request.
      (reset! answer-status (constantly 503))
      (deliver gate true)
      (is (= ["abort" "abort" "abort"] (repeatedly 3 #(:end (hear :answer (fn [part] (= "abort" (:end part)))))))
        "said for as long as it is said at all")
      ;; Long enough for the last of them to go unanswered, and a redial.
      (Thread/sleep 700)
      (is (:attached (follower/status)))
      (is (not-any? #(= :attach (first %)) (seq heard)) "the attachment it did not arrive on is not dropped for it"))))


(deftest a-body-fetched-and-not-read-is-closed-with-its-request
  (let [base *base*
        cut  @body-cut]
    (follow! base)
    (let [{:keys [send!]} (attachment)]
      ;; More than is ever read: the session it is for is not live, and the
      ;; upload is refused before a byte of it is taken.
      (reset! body-response (fn [exchange]
                              (HttpExchange/.sendResponseHeaders exchange 200 0)
                              (let [out   (DataOutputStream. (HttpExchange/.getResponseBody exchange))
                                    frame (byte-array 60000)
                                    until (+ (System/currentTimeMillis) 3000)]
                                (try
                                  (while (< (System/currentTimeMillis) until)
                                    (.writeInt out (alength frame))
                                    (.write out frame 0 (alength frame))
                                    (.flush out)
                                    (Thread/sleep 5))
                                  (deliver cut false)
                                  (catch java.io.IOException _ (deliver cut true))
                                  (finally (HttpExchange/.close exchange))))))
      (send! (upload-event "u1" "nosuch"))
      (is (not= 201 (:status (:head (answer-of "u1")))))
      (is (true? (deref cut 4000 false)) "the gateway is not left writing a body nobody reads"))))
