(ns co.multiply.remontoire.gateway-test
  "The gateway role against a scripted follower: the test attaches to a
   real gateway on an ephemeral port and plays the follower's side of the
   wire by hand. Session state is per JVM, so a real follower cannot share
   a process with the gateway it attaches to — `follower-test` plays the
   other side."
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.remontoire.fleet :as fleet]
    [co.multiply.remontoire.gateway :as gateway]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.test-util :as tu])
  (:import
    [com.sun.net.httpserver Headers HttpExchange HttpServer]
    [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream IOException InputStream]
    [java.net InetSocketAddress URI]
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
     HttpResponse$BodyHandlers]
    [java.util.concurrent LinkedBlockingQueue TimeUnit]))


(def ^:dynamic ^String *base* nil)


(use-fixtures :each
  (fn [f]
    (tu/reset-state!)
    (remontoire/init! {:port 0 :gateway? true :name "gw"})
    (let [server (:server @@#'remontoire/running)
          port   (InetSocketAddress/.getPort (HttpServer/.getAddress server))]
      (binding [*base* (str "http://127.0.0.1:" port)]
        ;; Quick beats: a departed follower is noticed by a failed write.
        (with-redefs [gateway/beat-ms 50]
          (try (f) (finally (remontoire/stop!))))))))


(def ^:private client (HttpClient/newHttpClient))


(defn- run
  "The gateway's current run, as its server was started for."
  []
  @@#'gateway/current)


(defn- fleet-of
  "The state of `run` as a request's router reads it."
  [run]
  @(:state run))


(defn- post
  "POST `body` — a string, or data sent as JSON — to `path`. Returns the
   response, its body a string."
  ^HttpResponse [path body headers]
  (let [builder (HttpRequest/newBuilder (URI/create (str *base* path)))]
    (.header builder "Content-Type" "application/json")
    (doseq [[k v] headers] (.header builder k v))
    (HttpClient/.send client
      (.build (.POST builder (HttpRequest$BodyPublishers/ofString
                               (if (string? body) body (json/write-str body)))))
      (HttpResponse$BodyHandlers/ofString))))


(defn- status
  [path body]
  (HttpResponse/.statusCode (post path body {})))


;; The number on the last thing a scripted follower said of its sessions.
(def ^:private said-seq (atom 0))


;; name → the id the gateway's welcome gave that name's latest attachment.
(def ^:private attachments (atom {}))


(defn- numbered
  "`message` as a follower says something of its sessions: numbered later
   than everything it said before, and naming the attachment its name
   holds."
  [message]
  (assoc message :seq (swap! said-seq inc) :attachment (@attachments (:name message))))


(defn- register
  "Ask for `handle` as `name`; the status the gateway answers with."
  [name handle]
  (status "/gateway/register" (numbered {:name name :handle handle})))


(defn- beat
  "Report `sessions` as `name`: the handles the gateway answers must be
   dropped, or the status of an answer that is not one to a report."
  [name sessions]
  (let [response (post "/gateway/beat" (numbered {:name name :sessions sessions}) {})]
    (if (= 200 (HttpResponse/.statusCode response))
      (:retire (json/read-str (HttpResponse/.body response) :key-fn keyword))
      (HttpResponse/.statusCode response))))


(defn- attach!
  "Attach as `name`, reporting `sessions`. Returns the response status
   and, when attached, the `:attachment` it was welcomed as, a queue of
   what the gateway sends — the welcome first, as the answer's head gave
   it, then the stream's events — and a `:close` that hangs up. `:pong? false` plays a holder
   that has stopped answering, and `:wire` a follower speaking that
   revision of the wire."
  [name sessions & {:keys [pong? wire] :or {pong? true wire fleet/wire}}]
  (let [response (HttpClient/.send client
                   (-> (HttpRequest/newBuilder (URI/create (str *base* "/gateway/attach")))
                     (.POST (HttpRequest$BodyPublishers/ofString
                              (json/write-str (numbered {:name name :version "test" :wire wire :sessions sessions}))))
                     .build)
                   (HttpResponse$BodyHandlers/ofInputStream))
        body     (HttpResponse/.body response)
        events   (LinkedBlockingQueue.)]
    (when (= 200 (HttpResponse/.statusCode response))
      (let [welcome (-> (HttpResponse/.headers response)
                      (.firstValue "X-Welcome")
                      (.orElse "null")
                      (json/read-str :key-fn keyword)
                      (assoc :type "welcome"))]
        (swap! attachments assoc name (:attachment welcome))
        (LinkedBlockingQueue/.put events welcome))
      (Thread/startVirtualThread
        ;; Bound, for `*base*`: the pong is posted from this thread.
        (bound-fn []
          (try
            (with-open [reader (io/reader body)]
              (doseq [line (line-seq reader)
                      :when (str/starts-with? line "data: ")]
                (let [event (json/read-str (subs line 6) :key-fn keyword)]
                  (if (and pong? (= "ping" (:type event)))
                    (post "/gateway/pong" {:nonce (:nonce event)} {})
                    (LinkedBlockingQueue/.put events event)))))
            (catch Exception _ nil)
            (finally
              (LinkedBlockingQueue/.put events {:type "closed"}))))))
    {:status     (HttpResponse/.statusCode response)
     :attachment (@attachments name)
     :events     events
     :close      #(InputStream/.close body)}))


(defn- gone
  "Wait until the gateway has found node `name` gone, which it does at its
   next write to it."
  [name]
  (loop [n 0]
    (when (and (some #(and (= name (:name %)) (:attached %)) (gateway/roster)) (< n 300))
      (Thread/sleep 10)
      (recur (inc n)))))


(defn- quiet?
  "Whether the gateway sends `node` nothing for a moment."
  [node]
  (nil? (LinkedBlockingQueue/.poll (:events node) 200 TimeUnit/MILLISECONDS)))


(defn- next-event
  [{:keys [events]}]
  (LinkedBlockingQueue/.poll events 3 TimeUnit/SECONDS))


(defn- retired
  "Take the welcome off what `node` was sent, and with it the handles the
   gateway told it to drop: the `retire` events right behind the welcome,
   until 200 ms bring nothing or something else."
  [{:keys [events] :as node}]
  (when (= "welcome" (:type (next-event node)))
    (loop [handles []]
      (let [head (loop [n 0]
                   (or (LinkedBlockingQueue/.peek events)
                     (when (< n 20) (Thread/sleep 10) (recur (inc n)))))]
        (if (= "retire" (:type head))
          (recur (conj handles (:handle (LinkedBlockingQueue/.poll events))))
          handles)))))


(defn- call
  "A legacy-wire `tools/call` to the gateway, on a thread of its own: a
   forwarded request waits on its follower. Derefs to the parsed response."
  [tool arguments & {:keys [cid]}]
  (future
    (-> (post "/mcp" {:jsonrpc "2.0" :id 7 :method "tools/call"
                      :params  {:name tool :arguments arguments}}
          (cond-> {} cid (assoc "Mcp-Session-Id" cid)))
      HttpResponse/.body
      (json/read-str :key-fn keyword))))


(defn- answer!
  "Answer request `rid` as a follower would a plain JSON response: one
   part carrying the head, the body and the end."
  [rid response]
  (let [bytes (.getBytes (json/write-str response) "UTF-8")]
    (HttpResponse/.statusCode
      (post (str "/gateway/answer/" rid) (String. bytes "UTF-8")
        {"X-Head" (json/write-str {:status  200
                                   :length  (alength bytes)
                                   :headers {"Content-Type" ["application/json"]}})
         "X-End"  "1"}))))


(defn- said
  [response]
  (deref response 3000 :no-answer))


(deftest a-session-opened-on-a-node-is-served-by-it
  (let [node    (attach! "n1" [])
        _       (is (= "welcome" (:type (next-event node))))
        created (call "create-session" {:node "n1"})
        request (next-event node)]
    (is (= "request" (:type request)))
    (is (= "create-session" (get-in (json/read-str (:body request) :key-fn keyword) [:params :name])))
    ;; The follower mints, and claims the handle before it answers.
    (is (= 204 (register "n1" "bcdfg")))
    (is (= 204 (answer! (:rid request) {:jsonrpc "2.0" :id 7 :result {:from "n1"}})))
    (is (= "n1" (get-in (said created) [:result :from])) "the client is given the follower's answer")
    (let [called  (call "eval" {:code ":x" :session-id "bcdfg"})
          request (next-event node)]
      (is (= "request" (:type request)) "the handle is the route")
      (answer! (:rid request) {:jsonrpc "2.0" :id 7 :result {:from "n1"}})
      (is (= "n1" (get-in (said called) [:result :from]))))))


(deftest what-names-no-node-is-served-here
  (let [node    (attach! "n1" [])
        _       (next-event node)
        created (said (call "create-session" {}))
        sid     (second (re-find #"Session ([a-z]+)" (tu/text-of created)))]
    (is (session/live? sid) "a session without a node lives in the gateway's JVM")
    (is (str/includes? (tu/text-of created) (pr-str "gw")) "and is said to be on the gateway")
    (is (not (tu/tool-error? (said (call "eval" {:code "(+ 1 2)" :session-id sid})))))
    (is (nil? (LinkedBlockingQueue/.poll (:events node) 200 TimeUnit/MILLISECONDS))
      "and its follower hears nothing of it")))


(deftest a-node-that-is-not-attached-is-refused
  (is (tu/tool-error? (said (call "create-session" {:node "nowhere"})))))


(deftest a-handle-is-held-by-one-instance
  (let [a (attach! "n1" [])
        b (attach! "n2" [])
        _ (next-event a)
        _ (next-event b)
        local (session/mint!)]
    (is (= 204 (register "n1" "bcdfg")))
    (is (= 409 (register "n2" "bcdfg")) "another node's")
    (is (= 409 (register "n2" local)) "the gateway's own")
    ;; The session ended, or the gateway's yes never reached n1, which
    ;; then gave nobody the handle: either way n1 reports without it.
    (is (= [] (beat "n1" [])))
    (is (= 204 (register "n2" "bcdfg")) "free again once its holder reports without it")
    (is (= 410 (register "n9" "ghjkl")) "a name not attached")
    (is (= 410 (beat "n9" [])))))


(deftest a-held-name-goes-to-a-claimant-only-when-its-holder-is-silent
  (let [holder (attach! "n1" [])]
    (next-event holder)
    (is (= 409 (:status (attach! "n1" []))) "the holder answered the ping"))
  (let [holder   (attach! "n2" [] :pong? false)
        _        (next-event holder)
        claimant (attach! "n2" [])]
    (is (= 200 (:status claimant)))
    (is (= "welcome" (:type (next-event claimant))))
    (is (= ["ping" "closed"] (mapv :type [(next-event holder) (next-event holder)]))
      "the silent holder was asked, then let go")))


(deftest a-handle-reported-by-two-nodes-is-retired-on-both
  (let [a (attach! "n1" ["bcdfg" "ghjkl"])
        _ (is (= [] (retired a)) "handles nobody else holds are adopted")
        b (attach! "n2" ["bcdfg"])]
    (is (= ["bcdfg"] (retired b)))
    (is (= {:type "retire" :handle "bcdfg"} (next-event a)))
    ;; Unrouted now: the call is served here, and refused as not live.
    (is (tu/tool-error? (said (call "eval" {:code ":x" :session-id "bcdfg"}))))
    (let [called (call "eval" {:code ":x" :session-id "ghjkl"})]
      (is (= "request" (:type (next-event a))) "the handle only one node reported still routes")
      (future-cancel called))))


(deftest a-handle-the-gateway-holds-itself-is-retired-on-both
  (let [local (session/mint!)
        node  (attach! "n1" [local])]
    (is (= [local] (retired node)))
    (is (not (session/live? local)))))


(deftest a-node-is-told-what-to-drop-ahead-of-anything-it-is-sent
  (let [write! @#'gateway/write!
        held   (promise)
        go     (promise)]
    (next-event (attach! "n1" ["bcdfg"]))
    ;; Held where an attach has sent its welcome and done nothing since:
    ;; the node is attached, and a request can be queued for it.
    (with-redefs-fn {#'gateway/write! (fn [out s]
                                        (write! out s)
                                        (when (and (str/includes? s ": attached") (not (realized? held)))
                                          (deliver held true)
                                          @go))}
      (fn []
        (let [node   (attach! "n2" ["bcdfg" "ghjkl"])
              _      (is (true? (deref held 3000 nil)))
              called (call "eval" {:code ":x" :session-id "ghjkl"})
              queue  (get-in (fleet-of (run)) [:nodes "n2" :queue])]
          (is (loop [n 0]
                (cond (pos? (LinkedBlockingQueue/.size queue)) true
                      (< 300 n) false
                      :else (do (Thread/sleep 10) (recur (inc n)))))
            "a request is waiting for the node before it has been told anything")
          (deliver go true)
          (is (= ["welcome" "retire" "request"]
                 (mapv :type [(next-event node) (next-event node) (next-event node)]))
            "and is sent behind what the node must drop")
          (future-cancel called))))))


(deftest a-node-that-goes-away-fails-what-it-owed-and-keeps-its-routes
  (let [node   (attach! "n1" ["bcdfg"])
        _      (next-event node)
        called (call "eval" {:code ":x" :session-id "bcdfg"})]
    (is (= "request" (:type (next-event node))))
    ((:close node))
    (is (tu/tool-error? (said called)) "the waiting client is answered, not left hanging")
    (let [refused (said (call "eval" {:code ":x" :session-id "bcdfg"}))]
      (is (tu/tool-error? refused))
      (is (str/includes? (tu/text-of refused) "n1")
        "a session on a node that is away is not called dead: the refusal names the node"))
    ;; Back, and reporting the handle: routed again.
    (let [back (attach! "n1" ["bcdfg"])
          _    (next-event back)
          _    (call "eval" {:code ":x" :session-id "bcdfg"})]
      (is (= "request" (:type (next-event back)))))))


(deftest a-fresh-gateway-does-not-call-an-unknown-handle-dead
  (let [early (tu/text-of (said (call "eval" {:code ":x" :session-id "bcdfg"})))
        late  (with-redefs-fn {#'gateway/grace-ms 0}
                #(tu/text-of (said (call "eval" {:code ":x" :session-id "bcdfg"}))))]
    (is (not= early late) "while followers may still be attaching, the refusal is a different one")
    (is (str/includes? late "create-session") "afterwards it is the ordinary refusal")))


(deftest a-legacy-clients-handshake-rides-along-with-its-session
  (let [node   (attach! "n1" [])
        _      (next-event node)
        shook  (post "/mcp" {:jsonrpc "2.0" :id 1 :method "initialize"
                             :params  {:protocolVersion "2025-06-18"
                                       :clientInfo      {:name "probe" :version "1"}}}
                 {})
        cid    (-> shook HttpResponse/.headers (.firstValue "Mcp-Session-Id") .get)
        called (call "create-session" {:node "n1"} :cid cid)
        request (next-event node)]
    (is (= "probe" (get-in request [:client :clientInfo :name]))
      "the follower never saw the handshake the gateway answered")
    (future-cancel called)))


(deftest the-roster-lists-the-fleet
  (let [node (attach! "n1" ["bcdfg"])]
    (next-event node)
    (is (= [["gw" true] ["n1" true]] (mapv (juxt :name :attached) (gateway/roster))))
    (is (= 1 (:sessions (second (gateway/roster)))))
    (is (= "n1" (:name (deref (gateway/await-node "n1") 1000 nil))) "a wait for an attached node is over")
    (let [waiting (gateway/await-node "n2")]
      (is (= ::waiting (deref waiting 100 ::waiting)))
      (next-event (attach! "n2" []))
      (is (= "n2" (:name (deref waiting 2000 nil))) "and one for a node to come settles when it does"))))


(deftest a-finished-wait-for-a-node-is-not-kept
  (let [waits (:waiters (run))
        node  (attach! "n1" [])]
    (next-event node)
    (dotimes [_ 3] (gateway/await-node "n1"))
    (is (empty? @waits) "a wait for a node already here holds nothing")
    (let [given-up (gateway/await-node "never")]
      (q/cancel given-up)
      ;; A cancellation lands a moment after it is asked for.
      (loop [n 0] (when (and (not (realized? given-up)) (< n 100)) (Thread/sleep 10) (recur (inc n)))))
    (gateway/await-node "n1")
    (is (empty? @waits) "a wait that was given up is swept at the next, one that returns at once included")
    (gateway/await-node "n2")
    (is (= ["n2"] (keys @waits)))
    (next-event (attach! "n2" []))
    (is (empty? @waits))))


(deftest a-retired-handle-stays-retired
  (let [a (attach! "n1" ["bcdfg"])
        _ (next-event a)
        b (attach! "n2" ["bcdfg"])]
    (is (= ["bcdfg"] (retired b)))
    (is (= [] (beat "n1" [])) "n1 dropped it")
    ;; n2 went away before it did, and another JVM took its name with no
    ;; sessions at all.
    ((:close b))
    (gone "n2")
    (let [stand-in (attach! "n2" [])]
      (is (= [] (beat "n2" [])))
      (is (= 409 (register "n1" "bcdfg")) "that nobody reports it is not nobody naming it")
      ((:close stand-in))
      (gone "n2"))
    ;; Away longer than a session survives silence: the node is forgotten.
    (with-redefs [session/expiry-ms -1]
      (#'gateway/prune-departed! (run)))
    (is (= [["gw" true] ["n1" true]] (mapv (juxt :name :attached) (gateway/roster))))
    ;; The first n2 is back, kept alive through its own endpoint all the
    ;; while, the session still in it.
    (let [back (attach! "n2" ["bcdfg"])]
      (is (= ["bcdfg"] (retired back)) "it is told again, not given the handle back")
      (is (= ["bcdfg"] (beat "n2" ["bcdfg"])) "and for as long as it goes on reporting it")
      (is (tu/tool-error? (said (call "eval" {:code ":x" :session-id "bcdfg"}))))
      (is (quiet? back) "nothing is routed to it")
      ;; Long past the start, when a handle the gateway knows nothing of is
      ;; left to this JVM to answer.
      (swap! (:state (run)) assoc :started 0)
      (is (some? (:refuse (#'gateway/message-target (run) (fleet-of (run))
                                                    {:method "tasks/get" :params {:taskId "bcdfg-1"}} {})))
        "nor is a task under the handle looked up here: it may be the other conversation's"))))


(deftest what-a-run-knew-is-not-emptied-under-a-request-on-its-way
  (attach! "n1" ["bcdfg"])
  (attach! "n2" ["bcdfg"])
  (let [before (run)
        task   {:method "tasks/get" :params {:taskId "bcdfg-1"}}
        placed #(#'gateway/message-target before (fleet-of before) task {})]
    ;; Long past the start, when an unknown handle is this JVM's to answer.
    (swap! (:state before) assoc :started 0)
    (is (:final? (placed)) "retired on two holders")
    ;; The gateway stops with the request on its way.
    (remontoire/stop!)
    (is (:final? (placed))
      "and no less retired to a request of that run, which is not left to this JVM and whatever task it keeps")))


(deftest a-wait-for-a-node-ends-with-its-gateway
  (let [waiting (gateway/await-node "n1")]
    (is (= ::waiting (deref waiting 100 ::waiting)))
    (remontoire/stop!)
    (is (true? @(q/await waiting 2000)))
    (is (and (some? (q/get-ex waiting)) (not (q/cancelled? waiting)))
      "told so, and not left for a node that attaches to another gateway")
    (is (some? (q/get-ex (gateway/await-node "n1"))) "as is a wait made on no gateway at all")))


(deftest a-wait-made-as-its-gateway-stops-ends-too
  (let [unsettled @#'gateway/unsettled
        armed     (atom true)]
    ;; The gateway stops between the look that found no such node and the
    ;; wait being put among the run's.
    (with-redefs-fn {#'gateway/unsettled (fn [waiting]
                                           (when (compare-and-set! armed true false)
                                             (remontoire/stop!))
                                           (unsettled waiting))}
      (fn []
        (let [waiting (gateway/await-node "n1")]
          (is (true? @(q/await waiting 2000)) "not left waiting on a run that told its waits already")
          (is (some? (q/get-ex waiting))))))))


(deftest what-a-former-holder-of-a-name-says-is-not-its-successors
  (let [first-jvm  (attach! "n1" ["bcdfg"])
        _          ((:close first-jvm))
        _          (gone "n1")
        second-jvm (attach! "n1" ["ghjkl"])
        report     (fn [attachment n sessions]
                     (status "/gateway/beat" {:name "n1" :attachment attachment :seq n :sessions sessions}))]
    (is (not= (:attachment first-jvm) (:attachment second-jvm)))
    ;; On its way since before the first JVM went, and numbered by a JVM
    ;; that had said much more than its successor has.
    (is (= 410 (report (:attachment first-jvm) 100000 ["bcdfg"])))
    (is (= 410 (status "/gateway/register"
                 {:name "n1" :attachment (:attachment first-jvm) :seq 100001 :handle "mnpqr"})))
    (is (= 410 (report nil 100002 [])) "nor is what names no attachment anybody's")
    (is (= [["gw" 0] ["n1" 1]] (mapv (juxt :name :sessions) (gateway/roster))))
    (is (= 200 (report (:attachment second-jvm) (swap! said-seq inc) ["ghjkl" "stvwx"]))
      "and its successor is heard still, however low it counts")
    (is (= [["gw" 0] ["n1" 2]] (mapv (juxt :name :sessions) (gateway/roster))))))


(defn- exchange-posting
  "An exchange posting `body` to `path`, its response given to `respond`:
   called with the status when the headers are sent, and what it returns
   is what the exchange does then."
  ^HttpExchange [path body respond]
  (let [request  (Headers.)
        response (Headers.)
        out      (ByteArrayOutputStream.)]
    (proxy [HttpExchange] []
      (getRequestMethod [] "POST")

      (getRequestURI [] (URI. path))

      (getRequestHeaders [] request)

      (getResponseHeaders [] response)

      (getRequestBody [] (ByteArrayInputStream. (.getBytes (json/write-str body) "UTF-8")))

      (getResponseBody [] out)

      (sendResponseHeaders [status _] (respond status))

      (close [] nil))))


(deftest a-name-taken-by-an-attach-that-could-not-be-answered-is-given-up
  ;; A follower that hung up as it was being answered.
  (#'gateway/handle! (run)
                     (exchange-posting "/gateway/attach"
                       {:name "n1" :version "test" :wire fleet/wire :seq 1 :sessions ["bcdfg"]}
                       (fn [_] (throw (IOException. "Broken pipe"))))
                     {:allowed-origins #{}})
  (is (= [["gw" true]] (mapv (juxt :name :attached) (filter :attached (gateway/roster))))
    "nobody is listed as attached with no stream to be reached by")
  (is (= 200 (:status (attach! "n1" []))) "and the name is free"))


(deftest a-handle-found-on-two-nodes-at-a-report-is-retired-on-both
  (let [a (attach! "n1" [])
        b (attach! "n2" [])
        _ (next-event a)
        _ (next-event b)]
    ;; Given out while the gateway could not be asked, and reported since.
    (is (= [] (beat "n1" ["bcdfg"])))
    (let [called (call "eval" {:code ":x" :session-id "bcdfg"})]
      (is (= "request" (:type (next-event a))) "a handle nobody else holds is routed from the report on")
      (future-cancel called))
    (is (= ["bcdfg"] (beat "n2" ["bcdfg"])) "one that another node holds is dropped by the node reporting it")
    (is (= {:type "retire" :handle "bcdfg"} (next-event a)) "and by the node that held it")
    (is (tu/tool-error? (said (call "eval" {:code ":x" :session-id "bcdfg"}))) "and is routed to neither")))


(deftest what-arrives-out-of-order-is-not-acted-on
  (let [node  (attach! "n1" [])
        _     (next-event node)
        ;; Read before the registration below, and arriving after it.
        early (numbered {:name "n1" :sessions []})]
    (is (= 204 (register "n1" "bcdfg")))
    (is (= 200 (status "/gateway/beat" early)))
    (let [called (call "eval" {:code ":x" :session-id "bcdfg"})]
      (is (= "request" (:type (next-event node))) "the handle is routed still")
      (future-cancel called))
    (is (= 409 (status "/gateway/register" (assoc early :handle "ghjkl")))
      "nor is a handle granted to a registration the follower has stopped waiting on")
    (is (= 204 (register "n1" "ghjkl")))))


(deftest what-is-said-as-an-attachment-gone-meanwhile-is-not-its-successors
  (doseq [[what said] [["beat" {:sessions ["bcdfg"]}] ["register" {:handle "bcdfg"}]]]
    (let [first-jvm (attach! "n1" [])
          _         (next-event first-jvm)
          message   (numbered (assoc said :name "n1"))
          successor (atom nil)
          status    (promise)
          now       @#'gateway/now
          saying    (Thread/currentThread)
          armed     (atom true)]
      ;; The name passes on with the note between the look that found its
      ;; attachment and the lock it is acted on under: the clock is read
      ;; there, by the thread the note is handled on.
      (with-redefs [gateway/now (fn []
                                  (when (and (= saying (Thread/currentThread)) (compare-and-set! armed true false))
                                    ((:close first-jvm))
                                    (gone "n1")
                                    (reset! successor (attach! "n1" [])))
                                  (now))]
        (#'gateway/handle-note! (run) (exchange-posting (str "/gateway/" what) message #(deliver status %)) what))
      (is (= 200 (:status @successor)))
      (is (= 410 (deref status 0 nil)) (str "a " what " is told its attachment is gone, as one arriving later is"))
      (is (nil? (get-in (fleet-of (run)) [:routes "bcdfg"])))
      ((:close @successor))
      (gone "n1"))))


(deftest a-follower-speaking-another-wire-is-refused
  (is (= 409 (:status (attach! "n1" [] :wire (inc fleet/wire)))))
  (is (= 409 (:status (attach! "n1" [] :wire nil))) "as is one that names none")
  (is (= [["gw" true]] (mapv (juxt :name :attached) (gateway/roster))))
  (is (= fleet/wire (:wire (next-event (attach! "n1" []))))
    "and a welcome says which revision it was given in"))


(deftest a-stream-cut-short-answers-every-request-it-owed
  (let [node     (attach! "n1" ["bcdfg"])
        _        (next-event node)
        call     (fn [id]
                   {:jsonrpc "2.0" :id id :method "tools/call"
                    :params  {:name "eval" :arguments {:code ":x" :session-id "bcdfg"}}})
        client   (future (HttpResponse/.body (post "/mcp" [(call 30) (call 31)] {})))
        rid      (:rid (next-event node))
        part!    (fn [body headers] (post (str "/gateway/answer/" rid) body headers))]
    (part! ": open\n\n"
      {"X-Head" (json/write-str {:status  200
                                 :length  0
                                 :headers {"Content-Type" ["text/event-stream; charset=utf-8"]}})})
    (part! "" {"X-End" "abort"})
    (let [said (into []
                 (comp (filter #(str/starts-with? % "data: "))
                   (map #(json/read-str (subs % 6) :key-fn keyword)))
                 (str/split-lines (deref client 3000 "")))]
      (is (= 1 (count said)) "one last message")
      (is (sequential? (first said)) "a batch, as it was asked")
      (is (= [30 31] (mapv :id (first said))))
      (is (every? tu/tool-error? (first said))))))


(deftest a-malformed-stateless-request-is-refused-wherever-it-would-have-gone
  (let [sent (fn [arguments]
               (HttpResponse/.statusCode
                 (post "/mcp" {:jsonrpc "2.0" :id 7 :method "tools/call"
                               :params  {:name "create-session" :arguments arguments}}
                   ;; The revision's header, and none of what it requires beside it.
                   {"MCP-Protocol-Version" mcp/protocol-version})))]
    (is (= 400 (sent {})) "one this JVM would have served")
    (is (= 400 (sent {:node "nowhere"})) "and one the gateway would have answered for a node")))


(defn- holding-a-request
  "Send a `create-session` with `arguments`, hold it where the transport
   has read it and not yet routed it, call `(meanwhile)`, and let it go.
   Returns whether it had been served three seconds on."
  [arguments meanwhile]
  (let [header http/header
        held   (promise)
        go     (promise)
        served (promise)
        handle @#'http/handle-exchange!]
    (with-redefs-fn {#'http/header           (fn [exchange name]
                                               (when (and (= "MCP-Protocol-Version" name) (not (realized? held)))
                                                 (deliver held true)
                                                 @go)
                                               (header exchange name))
                     #'http/handle-exchange! (fn [exchange door]
                                               (try (handle exchange door)
                                                 (finally (deliver served true))))}
      (fn []
        (let [client (call "create-session" arguments)]
          (is (true? (deref held 3000 nil)))
          (meanwhile)
          (deliver go true)
          (let [served? (deref served 3000 false)]
            (future-cancel client)
            served?))))))


(defn- restart!
  "Stop the gateway and start it again in this JVM, as `name`. Returns
   where the new one answers."
  [name]
  (remontoire/stop!)
  (str/replace (:url (remontoire/init! {:port 0 :gateway? true :name name})) "/mcp" ""))


(deftest a-request-for-a-node-is-not-served-here-once-its-gateway-has-stopped
  (is (true? (holding-a-request {:node "n1"} remontoire/stop!)))
  (is (empty? (session/handles)) "a session asked for on a node is not opened here instead"))


(deftest a-request-for-the-gateway-itself-is-not-served-once-it-has-stopped
  (is (true? (holding-a-request {} remontoire/stop!)))
  (is (empty? (session/handles)) "nothing a run accepted is run after it"))


(deftest a-request-a-gateway-accepted-is-not-carried-by-the-next
  (let [node (atom nil)]
    (is (true? (holding-a-request {:node "n1"}
                 (fn []
                   (binding [*base* (restart! "gw")]
                     (reset! node (attach! "n1" [])))
                   (is (= "welcome" (:type (next-event @node)))
                     "a node of the name it asked for, on the gateway that came after"))))
      "it is answered, by a gateway with nobody to carry it to")
    (is (nil? (LinkedBlockingQueue/.poll (:events @node) 200 TimeUnit/MILLISECONDS))
      "and that node is sent nothing")
    (is (empty? (session/handles)))))


(deftest a-mint-a-gateway-began-is-not-finished-against-the-next
  ;; A mint holds the claim of the run it began under, from the moment it
  ;; draws a handle until the fleet has had its say.
  (let [claim (:claim @@#'session/fleet-hooks)
        taken (atom [])
        take! #(swap! taken conj %)]
    (is (true? (claim "bcdfg" #(take! "bcdfg"))) "a live run's claim takes a free handle")
    (binding [*base* (restart! "gw")]
      (let [node (attach! "n1" ["ghjkl"])]
        (is (= "welcome" (:type (next-event node))))
        (is (thrown? clojure.lang.ExceptionInfo (claim "ghjkl" #(take! "ghjkl")))
          "the run that ended does not answer for a handle the next one routes")
        (is (thrown? clojure.lang.ExceptionInfo (claim "klmnp" #(take! "klmnp")))
          "nor for one that is free there")))
    (is (= ["bcdfg"] @taken) "and nothing is taken on its word")))


(deftest a-request-naming-the-next-gateway-is-not-served-by-it
  (is (true? (holding-a-request {:node "gw2"} #(restart! "gw2"))))
  (is (empty? (session/handles)) "the name was nobody's when the request was accepted"))


(deftest a-node-is-named-against-the-gateway-the-request-was-sent-to
  (let [create {:method "tools/call" :params {:name "create-session" :arguments {:node "gw2"}}}
        placed #(#'gateway/message-target % (fleet-of %) create {})
        before (run)]
    (restart! "gw2")
    (is (= "gw2" (:node (placed before)))
      "somebody else to the run that went by gw, whatever this JVM goes by since")
    (is (nil? (placed (run))) "and this JVM to the run that goes by it")))


(deftest a-request-goes-to-the-attachment-its-handle-was-found-on
  (let [first-jvm (attach! "n1" ["bcdfg"])
        _         (next-event first-jvm)
        ;; As the router read it, with the request on its way from there.
        fleet     (fleet-of (run))
        _         ((:close first-jvm))
        _         (gone "n1")
        ;; Another JVM under the name, holding no such session.
        successor (attach! "n1" [])
        _         (next-event successor)
        payload   {:jsonrpc "2.0" :id 7 :method "tools/call"
                   :params  {:name "eval" :arguments {:code ":x" :session-id "bcdfg"}}}
        routed    (fn [request]
                    (let [status (promise)]
                      [(deref (future
                                (#'gateway/dispatch! (run) fleet
                                                     (exchange-posting "/mcp" payload (fn [said] (deliver status said) nil))
                                                     request))
                         3000 ::waiting)
                       (deref status 0 nil)]))]
    (is (= [true 200] (routed {:kind :mcp :payload payload :body (json/write-str payload) :peer "127.0.0.1"}))
      "a call is answered by the gateway, in its own words")
    (is (= [true 502] (routed {:kind :upload :session "bcdfg"})) "and so is an upload")
    (is (quiet? successor) "the JVM that has the name now is sent neither")))


(deftest a-batch-is-carried-to-one-attachment
  (let [first-jvm (attach! "n1" ["bcdfg"])
        _         (next-event first-jvm)
        ;; As the router read it, with the batch on its way from there.
        fleet     (fleet-of (run))
        _         ((:close first-jvm))
        _         (gone "n1")
        ;; Another JVM under the name, with a request of the batch's
        ;; connection in flight on it.
        successor (attach! "n1" ["ghjkl"])
        _         (next-event successor)
        cid       (session/connect!)
        waiting   (call "eval" {:code ":x" :session-id "ghjkl"} :cid cid)
        rid       (:rid (next-event successor))
        ;; A notice for the JVM that has the name now, and a call for a
        ;; session of the one that had it.
        cancel    {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 7}}
        stale     {:jsonrpc "2.0" :id 8 :method "tools/call"
                   :params  {:name "eval" :arguments {:code ":x" :session-id "bcdfg"}}}
        routed    (fn [payload]
                    (let [status   (promise)
                          exchange (exchange-posting "/mcp" payload (fn [said] (deliver status said) nil))]
                      (Headers/.add (HttpExchange/.getRequestHeaders exchange) "Mcp-Session-Id" cid)
                      [(deref (future
                                (#'gateway/dispatch! (run) fleet exchange
                                                     {:kind :mcp :payload payload :body (json/write-str payload)
                                                      :peer "127.0.0.1"}))
                         2000 ::waiting)
                       (deref status 0 nil)]))]
    (is (some? rid))
    (is (= [true 200] (routed [cancel stale])) "two JVMs under one name are two places: refused here")
    (is (= [true 200] (routed [stale cancel])) "in either order")
    (is (quiet? successor) "and the JVM that has the name now is sent none of it")
    (answer! rid {:jsonrpc "2.0" :id 7 :result {}})
    (said waiting)))


(deftest a-welcome-of-one-run-settles-no-wait-of-the-next
  (let [write! @#'gateway/write!
        held   (promise)
        go     (promise)]
    ;; Held where an attach has sent its welcome and done nothing since.
    (with-redefs-fn {#'gateway/write! (fn [out s]
                                        (write! out s)
                                        (when (and (str/includes? s ": attached") (not (realized? held)))
                                          (deliver held true)
                                          @go))}
      (fn []
        (attach! "n1" [])
        (is (true? (deref held 3000 nil)))
        (let [base    (restart! "gw")
              waiting (gateway/await-node "n1")]
          (deliver go true)
          (is (= ::waiting (deref waiting 300 ::waiting))
            "the wait is for a node of this run, and the last run's attach does not end it")
          (binding [*base* base] (attach! "n1" []))
          (is (= "n1" (:name (deref waiting 3000 nil))) "the node that attaches to this run does"))))))


(deftest a-welcome-of-an-attachment-gone-meanwhile-settles-no-wait
  (let [write!  @#'gateway/write!
        held    (promise)
        go      (promise)
        waiting (gateway/await-node "n1")]
    ;; Held where an attach has sent its welcome and done nothing since.
    (with-redefs-fn {#'gateway/write! (fn [out s]
                                        (write! out s)
                                        (when (and (str/includes? s ": attached") (not (realized? held)))
                                          (deliver held true)
                                          @go))}
      (fn []
        (attach! "n1" [])
        (is (true? (deref held 3000 nil)))
        ;; The name is given up meanwhile, as when its holder loses a challenge.
        (#'gateway/detach! (run) "n1" (get-in (fleet-of (run)) [:nodes "n1"]))
        (deliver go true)
        (is (= ::waiting (deref waiting 300 ::waiting))
          "nobody is told of a node that is not there")
        (next-event (attach! "n1" []))
        (is (= "n1" (:name (deref waiting 3000 nil))) "the node that holds the name is what a wait is for")))))


(deftest an-attach-a-gateway-accepted-is-none-of-the-next-ones-business
  (let [before (run)
        base   (restart! "gw")
        ;; On the gateway that came after, and one that would lose a challenge.
        holder (binding [*base* base] (attach! "n1" [] :pong? false))
        _      (is (= "welcome" (:type (next-event holder))))
        attach (fn [name]
                 (let [status (promise)]
                   ;; On a thread of its own: an attach that is admitted is held.
                   (future
                     (#'gateway/handle! before
                                        (exchange-posting "/gateway/attach"
                                          {:name name :version "test" :wire fleet/wire :seq 1 :sessions []}
                                          (fn [said] (deliver status said) nil))
                                        {:allowed-origins #{}}))
                   (deref status 5000 nil)))]
    (is (= 503 (attach "n2")) "a name that is free there is not taken")
    (is (= 503 (attach "n1")) "and one that is held there is not contested")
    (is (nil? (LinkedBlockingQueue/.poll (:events holder) 200 TimeUnit/MILLISECONDS))
      "its holder is not asked whether it is there")
    (is (= [["gw" true] ["n1" true]] (mapv (juxt :name :attached) (filter :attached (gateway/roster))))
      "and stays attached")))


(deftest a-stream-that-gave-its-response-is-not-given-a-refusal
  (let [node     (attach! "n1" ["bcdfg"])
        _        (next-event node)
        part!    (fn [rid body headers] (post (str "/gateway/answer/" rid) body headers))
        open!    #(part! % ": open\n\n"
                    {"X-Head" (json/write-str {:status  200
                                               :length  0
                                               :headers {"Content-Type" ["text/event-stream; charset=utf-8"]}})})
        calling  #(future
                    (HttpResponse/.body
                      (post "/mcp" {:jsonrpc "2.0" :id 7 :method "tools/call"
                                    :params  {:name "eval" :arguments {:code ":x" :session-id "bcdfg"}}}
                        {})))
        messages (fn [body]
                   (into []
                     (comp (filter #(str/starts-with? % "data: "))
                       (map #(json/read-str (subs % 6) :key-fn keyword)))
                     (str/split-lines body)))]
    ;; Given up before its response: the client is owed one, and the
    ;; gateway gives it.
    (let [client (calling)
          rid    (:rid (next-event node))]
      (open! rid)
      (part! rid "" {"X-End" "abort"})
      (let [said (messages (deref client 3000 ""))]
        (is (= [7] (mapv :id said)))
        (is (tu/tool-error? (first said)))))
    ;; Given up after it: the client has its answer.
    (let [client (calling)
          rid    (:rid (next-event node))]
      (open! rid)
      (part! rid (str "data: " (json/write-str {:jsonrpc "2.0" :id 7 :result {:from "n1"}}) "\n\n")
        {"X-Answers" "all"})
      (part! rid "" {"X-End" "abort"})
      (is (= [{:from "n1"}] (mapv :result (messages (deref client 3000 ""))))
        "one answer to one request"))
    ;; A gateway is told which part answers the request, and how the node
    ;; frames its bytes is the node's business: a response in two writes
    ;; is answered by the one that says so.
    (let [client (calling)
          rid    (:rid (next-event node))
          frame  (str "data: " (json/write-str {:jsonrpc "2.0" :id 7 :result {:from "n1"}}) "\n\n")]
      (open! rid)
      (part! rid (subs frame 0 20) {})
      (part! rid (subs frame 20) {"X-Answers" "all"})
      (part! rid "" {"X-End" "abort"})
      (is (= [{:from "n1"}] (mapv :result (messages (deref client 3000 ""))))
        "and is given no second answer"))))


(deftest a-cancellation-for-a-request-here-is-not-carried-to-a-node
  (let [node  (attach! "n1" ["bcdfg"])
        _     (next-event node)
        cid   (session/connect!)
        local (session/mint!)
        slow  (call "eval" {:code "(Thread/sleep 1200) :done" :await 5000 :session-id local} :cid cid)
        _     (loop [n 0] (when (and (zero? (mcp/waiting cid 7)) (< n 200)) (Thread/sleep 10) (recur (inc n))))
        batch (future
                (-> (post "/mcp"
                      [{:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 7}}
                       {:jsonrpc "2.0" :id 8 :method "tools/call"
                        :params  {:name "eval" :arguments {:code ":x" :session-id "bcdfg"}}}]
                      {"Mcp-Session-Id" cid})
                  HttpResponse/.body
                  (json/read-str :key-fn keyword)))]
    (is (= 1 (mcp/waiting cid 7)) "the request the notice names runs here")
    (is (= [true] (mapv tu/tool-error? (deref batch 3000 nil)))
      "the notice is for this JVM and the call for a node: two machines")
    (is (nil? (LinkedBlockingQueue/.poll (:events node) 200 TimeUnit/MILLISECONDS)) "so the node is sent neither")
    (is (str/includes? (str (tu/text-of (said slow))) ":done") "and nothing in the batch was run")))


(deftest an-answer-given-up-is-not-waited-for
  (let [node   (attach! "n1" ["bcdfg"])
        _      (next-event node)
        called (call "eval" {:code ":x" :session-id "bcdfg"})
        rid    (:rid (next-event node))]
    (is (= 204 (HttpResponse/.statusCode (post (str "/gateway/answer/" rid) "" {"X-End" "abort"}))))
    (is (tu/tool-error? (said called)) "the client is answered by the gateway, with the node still attached")
    (is (= 404 (HttpResponse/.statusCode (post (str "/gateway/answer/" rid) "" {"X-End" "abort"})))
      "said twice, it finds nothing waiting")
    (is (= [["gw" true] ["n1" true]] (mapv (juxt :name :attached) (gateway/roster))))))


(deftest a-batch-for-more-than-one-machine-runs-nowhere
  (let [a     (attach! "n1" [])
        b     (attach! "n2" [])
        _     (next-event a)
        _     (next-event b)
        open  (fn [id node]
                {:jsonrpc "2.0" :id id :method "tools/call"
                 :params  {:name "create-session" :arguments (cond-> {} node (assoc :node node))}})
        batch (fn [& messages]
                (future (-> (post "/mcp" (vec messages) {}) HttpResponse/.body (json/read-str :key-fn keyword))))]
    (let [answered (said (batch (open 1 "n1") (open 2 "n2")))]
      (is (= [1 2] (mapv :id answered)))
      (is (every? tu/tool-error? answered)))
    (is (every? tu/tool-error? (said (batch (open 1 "n1") (open 2 nil)))) "the gateway's own JVM is a machine too")
    (is (zero? (session/live-count)) "nothing in a refused batch ran")
    (is (nil? (LinkedBlockingQueue/.poll (:events a) 200 TimeUnit/MILLISECONDS)))
    (is (nil? (LinkedBlockingQueue/.poll (:events b) 200 TimeUnit/MILLISECONDS)))
    (let [sent    (batch (open 1 "n1") (open 2 "n1"))
          request (next-event a)]
      (is (= 2 (count (json/read-str (:body request)))) "a batch for one node is carried whole")
      (future-cancel sent))))


(deftest a-cancellation-follows-its-request-or-nobody
  (let [a      (attach! "n1" ["bcdfg"])
        b      (attach! "n2" ["ghjkl"])
        _      (next-event a)
        _      (next-event b)
        cid    (session/connect!)
        cancel (fn [cid]
                 (HttpResponse/.statusCode
                   (post "/mcp" {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 7}}
                     (cond-> {} cid (assoc "Mcp-Session-Id" cid)))))
        first  (call "eval" {:code ":x" :session-id "bcdfg"} :cid cid)
        rid    (:rid (next-event a))]
    ;; A notice names a request by the connection it arrives on (§5): from
    ;; another connection, or from none, it names nothing.
    (is (= 202 (cancel nil)))
    (is (= 202 (cancel (session/connect!))))
    (is (quiet? a))
    ;; One request in flight on this connection under id 7: the cancellation is its.
    (let [told (future (cancel cid))]
      (is (= "notifications/cancelled" (:method (json/read-str (:body (next-event a)) :key-fn keyword))))
      (future-cancel told))
    ;; The same id in flight a second time on the connection, on another node.
    (let [second (call "eval" {:code ":x" :session-id "ghjkl"} :cid cid)]
      (is (= "request" (:type (next-event b))))
      (is (= 202 (cancel cid)) "ambiguous, so accepted and acted on by nobody")
      (is (quiet? a))
      (is (quiet? b))
      (future-cancel second))
    (answer! rid {:jsonrpc "2.0" :id 7 :result {}})
    (said first)))


(deftest a-cancellation-is-queued-behind-the-request-it-names
  (let [node   (attach! "n1" ["bcdfg"])
        _      (next-event node)
        cid    (session/connect!)
        armed  (atom true)
        told   (atom nil)
        ;; Bound, for `*base*`: it is posted from a thread of the gateway's.
        notice (bound-fn []
                 (post "/mcp" {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 7}}
                   {"Mcp-Session-Id" cid}))
        method #(some-> (:body %) (json/read-str :key-fn keyword) :method)]
    ;; The notice arrives the moment its request is among those in flight,
    ;; which is as early as the gateway will route it after that request.
    (add-watch (:pending (run)) ::notice
      (fn [_ _ _ in-flight]
        (when (and (seq in-flight) (compare-and-set! armed true false))
          (reset! told (future (notice)))
          ;; Long enough for it to be routed and queued, were nothing to hold it back.
          (Thread/sleep 300))))
    (let [called (call "eval" {:code ":x" :session-id "bcdfg"} :cid cid)
          sent   [(next-event node) (next-event node)]]
      (is (= ["tools/call" "notifications/cancelled"] (mapv method sent))
        "the node is sent them in the order the gateway took them")
      (future-cancel called)
      (some-> @told future-cancel))))


(deftest a-large-body-is-fetched-in-frames
  (let [node    (attach! "n1" ["bcdfg"])
        _       (next-event node)
        bytes   (byte-array (repeatedly 200000 #(rand-int 127)))
        put     (future
                  (HttpClient/.send client
                    (-> (HttpRequest/newBuilder (URI/create (str *base* "/upload/bcdfg/big.bin")))
                      (.PUT (HttpRequest$BodyPublishers/ofByteArray bytes))
                      .build)
                    (HttpResponse$BodyHandlers/ofString)))
        request (next-event node)
        fetched (HttpClient/.send client
                  (.build (HttpRequest/newBuilder (URI/create (str *base* "/gateway/body/" (:rid request)))))
                  (HttpResponse$BodyHandlers/ofInputStream))
        frames  (with-open [in (DataInputStream. (HttpResponse/.body fetched))]
                  (loop [frames []]
                    (let [n (.readInt in)]
                      (if (zero? n)
                        frames
                        (let [frame (byte-array n)]
                          (.readFully in frame)
                          (recur (conj frames frame)))))))]
    (is (= "PUT" (:method request)))
    (is (nil? (:body request)) "an upload's body does not ride the envelope")
    (is (= (seq bytes) (mapcat seq frames)) "every byte, and a last frame saying that was all")
    (is (= 204 (HttpResponse/.statusCode
                 (post (str "/gateway/answer/" (:rid request)) "{}"
                   {"X-Head" (json/write-str {:status 201 :length 2 :headers {}}) "X-End" "1"}))))
    (is (= 201 (HttpResponse/.statusCode (deref put 3000 nil))))
    (is (= 404 (HttpResponse/.statusCode
                 (HttpClient/.send client
                   (.build (HttpRequest/newBuilder (URI/create (str *base* "/gateway/body/nothing"))))
                   (HttpResponse$BodyHandlers/ofString))))
      "a body nobody waits with is not a body")))
