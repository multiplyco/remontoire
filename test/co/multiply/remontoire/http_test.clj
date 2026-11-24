(ns co.multiply.remontoire.http-test
  "Real roundtrips: `init!` binds an ephemeral loopback port and the JDK's
   own HttpClient speaks actual MCP at it — handshake, origin policy,
   session discipline, async work spanning requests, and the SSE shape.
   The legacy wire first, the stateless one after."
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.test-util :as tu]
    [co.multiply.remontoire.upload :as upload])
  (:import
    [java.io File InputStream]
    [java.net URI]
    [java.net.http HttpClient HttpHeaders HttpRequest HttpRequest$Builder
     HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
    [java.util Optional]))


(def ^:dynamic ^String *url* nil)


(use-fixtures :once
  (fn [f]
    (tu/reset-state!)
    (binding [*url* (:url (remontoire/init! {:port            0
                                             :describe        "the test rig"
                                             :allowed-origins #{"https://ok.example"}}))]
      (try (f) (finally (remontoire/stop!))))))


(def ^:private client (HttpClient/newHttpClient))


(defn- build
  ^HttpRequest [body {:keys [cid accept origin method headers]}]
  (let [builder (HttpRequest/newBuilder (URI/create *url*))]
    (case (or method :post)
      :post (-> builder
              (HttpRequest$Builder/.header "Content-Type" "application/json")
              (HttpRequest$Builder/.POST
                (HttpRequest$BodyPublishers/ofString
                  ;; Namespaced keys kept, as a 2026-07-28 client's `_meta` has them.
                  (json/write-str body :key-fn #(if (keyword? %) (subs (str %) 1) (str %))))))
      :get (HttpRequest$Builder/.GET builder)
      :delete (HttpRequest$Builder/.method builder "DELETE"
                (HttpRequest$BodyPublishers/noBody)))
    (when cid (HttpRequest$Builder/.header builder "Mcp-Session-Id" cid))
    (when accept (HttpRequest$Builder/.header builder "Accept" accept))
    (when origin (HttpRequest$Builder/.header builder "Origin" origin))
    (doseq [[k v] headers] (HttpRequest$Builder/.header builder k v))
    (HttpRequest$Builder/.build builder)))


(defn- send!
  (^HttpResponse [body] (send! body nil))
  (^HttpResponse [body opts]
   (HttpClient/.send client (build body opts) (HttpResponse$BodyHandlers/ofString))))


(defn- parsed
  [resp]
  (json/read-str (HttpResponse/.body resp) :key-fn keyword))


(defn- header-of
  [resp k]
  (Optional/.orElse (HttpHeaders/.firstValue (HttpResponse/.headers resp) k) nil))


(defn- init-body
  []
  {:jsonrpc "2.0" :id 0 :method "initialize"
   :params  {:protocolVersion "2025-03-26"
             :clientInfo      {:name "http-suite" :version "0"}}})


(defn- initialize!
  "The client handshake; returns the server-minted connection id."
  []
  (let [resp (send! (init-body))
        cid  (header-of resp "Mcp-Session-Id")]
    (is (= 200 (HttpResponse/.statusCode resp)))
    (is (some? cid))
    cid))


(defn- text-of
  [response]
  (get-in response [:result :content 0 :text]))


(defn- stream-messages
  "The JSON messages an SSE response body carried."
  [resp]
  (into []
    (comp (filter #(str/starts-with? % "data: "))
      (map #(json/read-str (subs % 6) :key-fn keyword)))
    (str/split-lines (HttpResponse/.body resp))))


(defn- call-body
  [sid tool args]
  {:jsonrpc "2.0" :id 1 :method "tools/call"
   :params  {:name tool :arguments (cond-> args sid (assoc :session-id sid))}})


(defn- call!
  "A tool call naming session `sid`, over connection `cid` when given."
  ([sid tool args] (call! nil sid tool args))
  ([cid sid tool args]
   (parsed (send! (call-body sid tool args) {:cid cid}))))


(defn- create-session!
  "A session as a legacy client gets one: by calling the tool. Returns its
   handle."
  ([] (create-session! (initialize!)))
  ([cid]
   (second (re-find #"\"session-id\": \"([a-z]+)\"" (text-of (call! cid nil "create-session" {}))))))


(defn- id-of
  [response]
  (some-> (re-find #";; id (\d+)" (text-of response)) second parse-long))


(deftest initialize-opens-a-connection-and-teaches
  (let [sessions (session/live-count)
        resp     (send! (init-body))
        body     (parsed resp)]
    (is (= 200 (HttpResponse/.statusCode resp)))
    (is (some? (header-of resp "Mcp-Session-Id")))
    (is (= sessions (session/live-count)) "a handshake mints no session")
    (is (map? (get-in body [:result :serverInfo])))
    (is (str/includes? (get-in body [:result :instructions]) "the test rig")
      "init!'s :describe reaches the wire")))


(deftest a-request-without-a-connection-is-served
  (is (= 200 (HttpResponse/.statusCode (send! {:jsonrpc "2.0" :id 1 :method "tools/list"}))))
  (is (true? (get-in (call! nil "eval" {:code "1"}) [:result :isError]))
    "what a call cannot do without is a session"))


(deftest a-session-knows-the-client-that-shook-hands
  (let [sid (create-session!)]
    (is (= "http-suite" (get-in (session/client sid) [:clientInfo :name])))))


(deftest a-session-outlives-its-connection
  (let [c1  (initialize!)
        sid (create-session! c1)]
    (call! c1 sid "eval" {:code "(def kept 17)"})
    (is (str/starts-with? (text-of (call! (initialize!) sid "eval" {:code "kept"})) "17")
      "a reconnect is a new connection and the same session")
    (is (true? (get-in (call! c1 c1 "eval" {:code "1"}) [:result :isError]))
      "the connection's id is not a session")))


(deftest origins-are-checked-and-cors-never-offered
  (is (= 403 (HttpResponse/.statusCode (send! (init-body) {:origin "https://evil.example"}))))
  (let [resp (send! (init-body) {:origin "https://ok.example"})]
    (is (= 200 (HttpResponse/.statusCode resp)))
    (is (nil? (header-of resp "Access-Control-Allow-Origin"))
      "an allowed origin is served, never advertised")))


(deftest only-post-speaks
  (is (= 405 (HttpResponse/.statusCode (send! nil {:method :get})))))


(defn- at-probe
  "Rebind the suite's helpers onto the sibling `/probe` context."
  [f]
  (binding [*url* (str/replace *url* "/mcp" "/probe")]
    (f)))


(deftest probe-answers-identity-without-a-session
  (at-probe
    (fn []
      (let [roster-before (count (session/roster))
            resp          (send! nil {:method :get})
            body          (parsed resp)]
        (is (= 200 (HttpResponse/.statusCode resp)))
        (is (= "remontoire" (:server body))
          "the fixed discriminator discovery keys on")
        (is (and (string? (:version body)) (seq (:version body))))
        (is (= "the test rig" (:describe body))
          "init!'s :describe reaches the sessionless door")
        (is (nil? (header-of resp "Mcp-Session-Id")) "no session minted")
        (is (= roster-before (count (session/roster)))
          "probes leave no ghosts on the roster")))))


(deftest probe-is-get-only-and-origin-gated
  (at-probe
    (fn []
      (is (= 405 (HttpResponse/.statusCode (send! {:jsonrpc "2.0"})))
        "POST does not speak here")
      (is (= 403 (HttpResponse/.statusCode
                   (send! nil {:method :get :origin "https://evil.example"}))))
      (is (= 200 (HttpResponse/.statusCode
                   (send! nil {:method :get :origin "https://ok.example"})))))))


(deftest eval-roundtrip
  (let [sid (create-session!)
        r   (call! sid "eval" {:code "(+ 20 22)"})]
    (is (false? (get-in r [:result :isError])))
    (is (str/starts-with? (text-of r) "42"))
    (is (pos-int? (id-of r)))))


(deftest state-sticks-to-the-session
  (let [s1 (create-session!)
        s2 (create-session!)]
    (call! s1 "eval" {:code "(def http-marker 17)"})
    (is (str/starts-with? (text-of (call! s1 "eval" {:code "http-marker"})) "17"))
    (is (true? (get-in (call! s2 "eval" {:code "http-marker"}) [:result :isError]))
      "another session's scratch is its own")))


(deftest async-work-spans-requests
  (let [sid (create-session!)
        r1  (call! sid "eval" {:code "(q/sleep 300 :late)" :await 1})
        id  (id-of r1)]
    (is (str/includes? (text-of r1) "running"))
    (is (str/includes? (text-of (call! sid "await" {:all-of [id] :await 5000}))
          ":done"))
    (is (str/starts-with?
          (text-of (call! sid "eval" {:code (str "@(res " id ")")}))
          ":late")
      "the id kept resolving across requests")))


(deftest cancel-over-the-wire
  (let [sid (create-session!)
        id  (id-of (call! sid "eval" {:code "(q/sleep 60000)" :await 1}))]
    (is (str/includes? (text-of (call! sid "cancel" {:ids [id] :await 3000}))
          ":cancelled"))))


(deftest a-clients-cancellation-finds-its-own-request
  ;; Two connections from one address, each with request 1 in flight —
  ;; `call-body` numbers every call 1, as a client's first call is.
  (let [sid     (create-session!)
        mine    (initialize!)
        theirs  (initialize!)
        started (fn [cid ms] (future (call! cid sid "eval" {:code (str "(q/sleep " ms " :slept)") :await 10000})))
        a       (started mine 20000)
        b       (started theirs 600)
        flying  #(count @@#'co.multiply.remontoire.mcp/in-flight)]
    (is (loop [n 0] (cond (= 2 (flying)) true (< 200 n) false :else (do (Thread/sleep 10) (recur (inc n))))))
    (is (= 202 (HttpResponse/.statusCode
                 (send! {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 1}}
                   {:cid mine}))))
    (is (= :cancelled (registry/status (registry/task (id-of (deref a 3000 nil)))))
      "the request on the connection that asked")
    (is (str/starts-with? (text-of (deref b 5000 nil)) ":slept") "and not its namesake on another")
    (is (zero? (flying)) "both off the books once answered")))


(deftest a-cancellation-during-the-write-leaves-the-result-owed
  ;; The handler held at the point of writing a finished eval's answer:
  ;; the cancellation arrives then, and what goes out after it has no
  ;; reader.
  (let [sid     (create-session!)
        cid     (initialize!)
        writing (promise)
        proceed (promise)
        respond @#'co.multiply.remontoire.http/respond!]
    (with-redefs-fn {#'co.multiply.remontoire.http/respond!
                     (fn [exchange status content-type body]
                       (when (and body (str/includes? body ":held-at-the-write"))
                         (deliver writing true)
                         (deref proceed 5000 nil))
                       (respond exchange status content-type body))}
      #(let [answer (future (call! cid sid "eval" {:code ":held-at-the-write"}))]
         (is (true? (deref writing 5000 false)))
         (is (= 202 (HttpResponse/.statusCode
                      (send! {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 1}}
                        {:cid cid}))))
         (deliver proceed true)
         (let [entry (id-of (deref answer 5000 nil))]
           (is (some? entry))
           (is (loop [n 0]
                 (cond (zero? (count @@#'co.multiply.remontoire.mcp/in-flight)) true
                       (< 200 n) false
                       :else (do (Thread/sleep 10) (recur (inc n))))))
           (is (nil? (:touched (registry/entry entry))) "not marked delivered")
           (is (= [entry] (mapv :id (registry/owed sid))) "and still owed"))))))


(deftest sse-streams-the-call
  (let [sid  (create-session!)
        resp (send! (call-body sid "eval" {:code "(* 6 7)"})
               {:accept "application/json, text/event-stream"})]
    (is (= 200 (HttpResponse/.statusCode resp)))
    (is (str/starts-with? (or (header-of resp "Content-Type") "")
          "text/event-stream"))
    (let [result (first (filter :result (stream-messages resp)))]
      (is (some? result) "the stream carries the one response message")
      (is (str/starts-with? (get-in result [:result :content 0 :text]) "42")))))


(deftest concurrent-evals-are-attributed-correctly
  ;; Three requests in flight at once, grounded in parallel by Quiescent:
  ;; each response carries its own value and its own printed output.
  (let [sid   (create-session!)
        ;; Built on the test thread, where *url* is bound; only the sends
        ;; ride tasks.
        req   (fn [n]
                (build (call-body sid "eval" {:code (str "(do (Thread/sleep 100)"
                                                      " (println \"tick-" n "\")"
                                                      " (* " n " " n "))")})
                  nil))
        tasks (mapv (fn [n]
                      (q/as-task (HttpClient/.sendAsync client (req n)
                                   (HttpResponse$BodyHandlers/ofString))))
                [2 3 4])
        texts (mapv (comp text-of parsed) @(q/task tasks))]
    (doseq [[n text] (map vector [2 3 4] texts)]
      (is (str/starts-with? text (str (* n n))))
      (is (str/includes? text (str "tick-" n)))
      (doseq [other (remove #{n} [2 3 4])]
        (is (not (str/includes? text (str "tick-" other)))
          "no output leaks between concurrent evals")))))


(deftest goodbye-forgets-the-connection-and-spares-its-sessions
  (let [cid (initialize!)
        sid (create-session! cid)]
    (is (= 204 (HttpResponse/.statusCode (send! nil {:method :delete :cid cid}))))
    (is (nil? (session/connection-client cid)))
    (is (session/live? sid) "a session ends by silence, not with a connection")))


(deftest a-stale-tool-list-is-announced-until-it-is-fetched
  (let [cid       (initialize!)
        sid       (create-session! cid)
        announced #(boolean
                     (some (comp #{"notifications/tools/list_changed"} :method)
                       (stream-messages
                         (send! (call-body sid "eval" {:code "1"})
                           {:cid cid :accept "application/json, text/event-stream"}))))]
    (is (true? (announced)) "a connection that has fetched nothing holds a stale list")
    (send! {:jsonrpc "2.0" :id 2 :method "tools/list"} {:cid cid})
    (is (false? (announced)))))


(defn- upload!
  "PUT `content` to the sibling `/upload` context as `name` for `sid`."
  (^HttpResponse [sid name content] (upload! sid name content nil))
  (^HttpResponse [sid name ^String content {:keys [origin method]}]
   (let [builder (HttpRequest/newBuilder
                   (URI/create (str (str/replace *url* "/mcp" "/upload/") sid "/" name)))]
     (if (= :get method)
       (HttpRequest$Builder/.GET builder)
       (HttpRequest$Builder/.PUT builder (HttpRequest$BodyPublishers/ofString content)))
     (when origin (HttpRequest$Builder/.header builder "Origin" origin))
     (HttpClient/.send client (HttpRequest$Builder/.build builder)
       (HttpResponse$BodyHandlers/ofString)))))


(deftest an-upload-is-staged-for-its-session
  (let [sid    (create-session!)
        resp   (upload! sid "notes/a.edn" "{:n 1}")
        staged (parsed resp)]
    (is (= 201 (HttpResponse/.statusCode resp)))
    (is (= "notes/a.edn" (:name staged)))
    (is (= "{:n 1}" (slurp (:path staged)))
      "the answer is the JVM's own path to the bytes")
    ;; The listing is every session's; `:by` marks the ones not the caller's.
    (is (str/starts-with? (text-of (call! sid "eval" {:code "(mapv :name (remove :by (__r/uploads)))"}))
          "[\"notes/a.edn\"]")
      "the session that sent it finds it listed as its own")
    (is (= (:path staged) (:path (parsed (upload! sid "notes/a.edn" "{:n 2}"))))
      "a name sent again lands in the same place")
    (is (= "{:n 2}" (slurp (:path staged))) "and replaces what was there")))


(defn- told-url
  "The upload URL an eval answers with, read off the response text."
  [response]
  (second (re-find #"\"(https?://[^\"]+)\"" (text-of response))))


(deftest a-session-is-told-where-to-upload
  (let [sid (create-session!)
        url (told-url (call! sid "eval" {:code "(__r/upload-url)"}))]
    (is (str/starts-with? (str url) (str/replace *url* "/mcp" "/upload/"))
      "the address the client itself used")
    (is (= 201 (HttpResponse/.statusCode
                 (HttpClient/.send client
                   (-> (HttpRequest/newBuilder (URI/create (str url "a.edn")))
                     (HttpRequest$Builder/.PUT (HttpRequest$BodyPublishers/ofString "{}"))
                     (HttpRequest$Builder/.build))
                   (HttpResponse$BodyHandlers/ofString))))
      "and an upload sent there lands")
    (is (str/starts-with?
          (str (told-url
                 (parsed (send! (call-body sid "eval" {:code "(__r/upload-url)"})
                           {:headers {"X-Forwarded-Proto" "https"
                                      "X-Forwarded-Host"  "repl.example"}}))))
          "https://repl.example/upload/")
      "a proxy's account of the original address is believed")))


(deftest an-upload-that-cannot-be-placed-is-refused
  (let [sid (create-session!)]
    (is (= 404 (HttpResponse/.statusCode (upload! "nobody" "a.edn" "{}"))))
    (is (not (session/live? "nobody")) "an upload never mints the session it names")
    (is (= 400 (HttpResponse/.statusCode (upload! sid "%2e%2e/escape.edn" "{}")))
      "a name may not climb out of the directory")
    (is (= 405 (HttpResponse/.statusCode (upload! sid "a.edn" nil {:method :get}))))
    (is (= 403 (HttpResponse/.statusCode
                 (upload! sid "a.edn" "{}" {:origin "https://evil.example"}))))
    (with-redefs [upload/max-bytes 4]
      (is (= 413 (HttpResponse/.statusCode (upload! sid "big.edn" "{:too :long}")))))
    (is (= [] (upload/staged (session/upload-dir sid)))
      "nothing refused is left behind")))


(deftest an-upload-is-not-staged-for-a-session-that-ended-under-it
  ;; The session ends after the bytes are in and before they are moved
  ;; into place.
  (let [sid  (session/mint!)
        dir  (session/upload-dir! sid)
        copy @#'upload/copy!]
    (with-redefs-fn {#'upload/copy! (fn [in part]
                                      (let [n (copy in part)]
                                        (session/drop! sid)
                                        n))}
      #(is (= 404 (HttpResponse/.statusCode (upload! sid "notes/late.edn" "{}")))))
    (is (not (File/.exists (File. (str dir)))) "and nothing of it is left on disk")))


(deftest an-upload-is-answered-with-what-it-put-there
  ;; Not with a reading of the disk, where another upload under the same
  ;; name may already have taken its place.
  (let [sid (session/mint!)]
    (with-redefs-fn {#'upload/entry (constantly {:name "another" :path "elsewhere" :size 600})}
      #(is (= {:name "notes/a.edn" :size 6}
              (select-keys (parsed (upload! sid "notes/a.edn" "{:n 1}")) [:name :size]))))))


(deftest an-upload-is-heard-from-as-it-arrives
  (let [sid     (session/mint!)
        earlier (- (System/currentTimeMillis) 60000)
        store!  upload/store!
        seen    (atom nil)]
    (swap! @#'session/state assoc-in [sid :last-seen] earlier)
    (with-redefs-fn {#'upload/store! (fn [& args]
                                       (reset! seen (get-in @@#'session/state [sid :last-seen]))
                                       (apply store! args))}
      #(is (= 201 (HttpResponse/.statusCode (upload! sid "a.edn" "{}")))))
    (is (< earlier @seen) "before its bytes are taken")))


(deftest uploads-die-with-the-session
  (let [sid  (create-session!)
        path (:path (parsed (upload! sid "a.edn" "{}")))]
    (is (File/.exists (io/file path)))
    (registry/release-session! sid)
    (is (not (File/.exists (io/file path))))))


;; # The stateless era
;; ################################################################################

(def ^:private tasks-meta
  {:io.modelcontextprotocol/protocolVersion    "2026-07-28"
   :io.modelcontextprotocol/clientInfo         {:name "http-suite" :version "0"}
   :io.modelcontextprotocol/clientCapabilities {:extensions {:io.modelcontextprotocol/tasks {}}}})


(defn- modern!
  "A stateless request with its headers restating the body, as a 2026-07-28
   client sends it. `override` replaces headers to provoke mismatches."
  ([method params] (modern! method params nil))
  ([method params override]
   (let [named (case method
                 "tools/call" (:name params)
                 ("tasks/get" "tasks/cancel" "tasks/update") (:taskId params)
                 nil)]
     (send! {:jsonrpc "2.0" :id 7 :method method
             :params  (assoc params :_meta tasks-meta)}
       {:headers (merge (cond-> {"MCP-Protocol-Version" "2026-07-28"
                                 "Mcp-Method"           method}
                          named (assoc "Mcp-Name" named))
                   override)}))))


(deftest stateless-requests-need-no-transport-session
  (let [discover (modern! "server/discover" {})
        body     (parsed discover)]
    (is (= 200 (HttpResponse/.statusCode discover)))
    (is (nil? (header-of discover "Mcp-Session-Id")))
    (is (contains? (get-in body [:result :capabilities :extensions])
          :io.modelcontextprotocol/tasks)
      "namespaced keys survive encoding")
    (is (some? (get-in body [:result :_meta :io.modelcontextprotocol/serverInfo])))))


(deftest stateless-sessions-are-minted-by-a-tool
  (let [created (parsed (modern! "tools/call" {:name "create-session" :arguments {}}))
        sid     (second (re-find #"\"session-id\": \"([a-z]{5})\"" (text-of created)))
        evald   (parsed (modern! "tools/call" {:name      "eval"
                                               :arguments {:code "(+ 40 2)" :session-id sid}}))]
    (is (some? sid))
    (is (str/starts-with? (text-of evald) "42"))
    (is (str/includes? (text-of evald) (str "session " sid)) "the footer echoes the handle")))


(deftest stateless-headers-must-restate-the-body
  (is (= 400 (HttpResponse/.statusCode
               (modern! "tools/call" {:name "eval" :arguments {}} {"Mcp-Name" "cancel"}))))
  (is (= 400 (HttpResponse/.statusCode
               (modern! "tools/list" {} {"Mcp-Method" "tools/call"}))))
  (is (= 400 (HttpResponse/.statusCode
               (modern! "tasks/get" {:taskId "bcdfg-1"} {"Mcp-Name" "bcdfg-2"})))
    "a task method's name header carries its task id")
  (let [stateless {"MCP-Protocol-Version" mcp/protocol-version "Mcp-Method" "ping"}
        ping      (fn [version]
                    {:jsonrpc "2.0" :id 7 :method "ping"
                     :params  {:_meta (assoc tasks-meta :io.modelcontextprotocol/protocolVersion version)}})]
    (is (= 200 (HttpResponse/.statusCode (send! (ping mcp/protocol-version) {:headers stateless}))))
    (is (= 400 (HttpResponse/.statusCode (send! (ping "2025-06-18") {:headers stateless})))
      "the body names the revision its header does")
    (is (= 400 (HttpResponse/.statusCode (send! [(ping mcp/protocol-version)] {:headers stateless})))
      "and a stateless request is one message, never a batch")))


(def ^:private gate
  "What an eval in a test waits on until the test lets it go."
  (atom nil))


(deftest a-batch-serves-every-session-it-names
  ;; Two calls for two sessions in one post, answered on one stream: the
  ;; second waits its turn behind the first, which is held open here.
  (let [cid     (initialize!)
        ahead   (create-session! cid)
        behind  (create-session! cid)
        stamp   #(get-in @@#'session/state [behind :last-seen])
        entries (fn [] (count (filter #(#{ahead behind} (:session %)) (vals (:entries @@#'registry/state)))))
        until   (fn [done?]
                  (loop [n 0]
                    (cond (done?) true
                          (< 300 n) false
                          :else (do (Thread/sleep 10) (recur (inc n))))))
        batch   [(assoc (call-body ahead "eval" {:code (str "@@@#'" `gate " (__r/upload-url)") :await 5000}) :id 1)
                 (assoc (call-body behind "eval" {:code "(__r/upload-url)"}) :id 2)]]
    (reset! gate (promise))
    (with-redefs-fn {#'co.multiply.remontoire.http/heartbeat-ms 50
                     #'co.multiply.remontoire.http/poll-ms      10}
      #(let [answer (future (send! batch {:cid cid :accept "application/json, text/event-stream"}))]
         (try
           (is (until (fn [] (= 1 (entries)))) "the first call is admitted, and waits")
           ;; The post's arrival refreshed both sessions. Only a beat can
           ;; move the stamp from here.
           (let [arrived (stamp)]
             (is (until (fn [] (< arrived (stamp)))) "the stream's beats keep the waiting session fresh")
             (is (= 1 (entries)) "before its own call has begun"))
           (finally (deliver @gate :go)))
         ;; The batch's answer among the stream's messages: the connection
         ;; never listed its tools, so `tools/list_changed` rides ahead.
         (let [[told-ahead told-behind] (first (filter sequential? (stream-messages (deref answer 5000 nil))))
               doors (str/replace *url* "/mcp" "/upload/")]
           (is (str/starts-with? (str (told-url told-ahead)) doors))
           (is (str/starts-with? (str (told-url told-behind)) doors)
             "and each is told how it was addressed, not only the first"))))))


(deftest a-batch-narrates-each-call-under-its-own-token
  ;; Three evals on one stream, each printing a line: two attached a
  ;; progress token and one did not.
  (let [cid   (initialize!)
        sid   (create-session! cid)
        said  (fn [id line token]
                (cond-> (assoc (call-body sid "eval" {:code (str "(println " (pr-str line) ") (Thread/sleep 150)")})
                          :id id)
                  token (assoc-in [:params :_meta :progressToken] token)))
        batch [(said 1 "first-call-line" "one") (said 2 "second-call-line" "two") (said 3 "third-call-line" nil)]]
    (with-redefs-fn {#'co.multiply.remontoire.http/poll-ms 10}
      #(let [beats (->> (stream-messages (send! batch {:cid cid :accept "application/json, text/event-stream"}))
                     (filter (fn [m] (and (map? m) (= "notifications/progress" (:method m)))))
                     (map :params))
             under (fn [token] (str/join "\n" (keep (fn [b] (when (= token (:progressToken b)) (:message b))) beats)))]
         (is (re-find #"(?m)^first-call-line" (under "one")))
         (is (re-find #"(?m)^second-call-line" (under "two")) "whole, from the start of its own output")
         (is (not (str/includes? (under "one") "second-call")) "and not under the call before it")
         (is (not-any? (fn [b] (str/includes? (:message b) "third-call")) beats)
           "a call that attached no token is narrated under nobody else's")))))


(deftest a-stateless-call-is-narrated-under-its-token
  (let [sid  (session/mint!)
        call (-> (call-body sid "eval" {:code "(println \"lone-call-line\") (Thread/sleep 150)"})
               (assoc-in [:params :_meta] (assoc tasks-meta :progressToken "lone")))]
    (with-redefs-fn {#'co.multiply.remontoire.http/poll-ms 10}
      #(let [resp (send! call {:accept  "application/json, text/event-stream"
                               :headers {"MCP-Protocol-Version" mcp/protocol-version
                                         "Mcp-Method"           "tools/call"
                                         "Mcp-Name"             "eval"}})]
         (is (some (fn [m]
                     (and (= "lone" (get-in m [:params :progressToken]))
                       (str/starts-with? (str (get-in m [:params :message])) "lone-call-line")))
               (stream-messages resp)))))))


(def ^:private clock
  "The session clock, for a test that moves it."
  (atom 0))


(deftest a-batch-keeps-every-session-it-names-from-arrival
  ;; `waiting` is a moment from expiry when the post arrives, and its call
  ;; is last. The clock passes that moment during the first call, and the
  ;; second call's admission sweeps the silent.
  (let [cid     (initialize!)
        ahead   (create-session! cid)
        waiting (create-session! cid)
        batch   [(assoc (call-body ahead "eval" {:code (str "(swap! @#'" `clock " + 200)")}) :id 1)
                 (assoc (call-body ahead "eval" {:code ":sweeps"}) :id 2)
                 (assoc (call-body waiting "eval" {:code ":in-time"}) :id 3)]]
    (reset! clock (System/currentTimeMillis))
    (with-redefs-fn {#'session/now (fn [] @clock)}
      #(do (swap! @#'session/state assoc-in [waiting :last-seen] (- @clock session/expiry-ms -100))
         (let [answers (parsed (send! batch {:cid cid}))]
           (is (session/live? waiting))
           (is (= [false false false] (mapv (comp boolean :isError :result) answers))
             "and its call is served"))))))


(defn- streamed-eval
  [sid]
  (build (assoc-in (call-body sid "eval" {:code "(q/sleep 60000)"}) [:params :_meta] tasks-meta)
    {:accept  "application/json, text/event-stream"
     :headers {"MCP-Protocol-Version" mcp/protocol-version "Mcp-Method" "tools/call" "Mcp-Name" "eval"}}))


(defn- session-entry
  [sid]
  (some #(when (= sid (:by %)) %) (registry/evals)))


(defn- wait-until
  [f]
  (loop [attempt 0]
    (or (f) (when (< attempt 500) (Thread/sleep 10) (recur (inc attempt))))))


(deftest closing-one-stateless-stream-cancels-only-its-eval
  (let [a (session/mint!)
        b (session/mint!)
        open-stream (fn [sid]
                      (HttpResponse/.body (HttpClient/.send client (streamed-eval sid)
                                            (HttpResponse$BodyHandlers/ofInputStream))))]
    (with-redefs-fn {#'http/poll-ms 20}
      (fn []
        (with-open [^InputStream other (open-stream b)]
          (with-open [^InputStream first-stream (open-stream a)]
            (is (wait-until #(and (session-entry a) (session-entry b)))))
          (is (wait-until #(= :cancelled (:status (session-entry a)))))
          (is (= :running (:status (session-entry b))) "same peer and same JSON-RPC id"))
        (is (wait-until #(= :cancelled (:status (session-entry b)))))))))


(deftest a-json-answer-that-fails-to-go-out-delivers-nothing
  (let [cid     (initialize!)
        sid     (create-session! cid)
        respond @#'http/respond!]
    (with-redefs-fn {#'http/respond! (fn [exchange status content-type body]
                                       (if (= 200 status)
                                         (throw (java.io.IOException. "the client is gone"))
                                         (respond exchange status content-type body)))}
      ;; What the client is told of the failure is not the point here.
      #(try (send! (call-body sid "eval" {:code ":unread"}) {:cid cid})
         (catch java.io.IOException _ nil)))
    (let [{:keys [id]} (session-entry sid)]
      (is (some? id) "the eval ran")
      (is (nil? (:touched (registry/entry id))) "its result reached nobody, and stays protected")
      (is (= [id] (mapv :id (registry/owed sid))) "and is still to be told"))))


(deftest the-final-sse-response-hands-off-running-work
  ;; No heartbeat during the grace period. The first observed failure can
  ;; be the response carrying the task id, or the probe after that response.
  (doseq [[modern? fail-probe? expected] [[true false :cancelled]
                                          [true true :running]
                                          [false false :running]]]
    (let [sid   (session/mint!)
          write @#'http/write-frame!
          failed (promise)
          req    (if modern?
                   (streamed-eval sid)
                   (build (call-body sid "eval" {:code "(q/sleep 60000)" :await 30})
                     {:accept "text/event-stream"}))]
      (try
        (with-redefs-fn {#'mcp/task-grace 30
                         #'http/write-frame! (fn [out frame]
                                               (if (if fail-probe?
                                                     (= ": end\n\n" frame)
                                                     (str/starts-with? frame "data: "))
                                                 (do (deliver failed true) false)
                                                 (write out frame)))}
          (fn []
            (let [response (HttpClient/.send client req (HttpResponse$BodyHandlers/ofString))]
              (is (realized? failed))
              (is (= expected (:status (session-entry sid))))
              (when fail-probe?
                (is (some? (get-in (first (stream-messages response)) [:result :taskId])))
                ;; The probe failing says the answer may not have landed, so
                ;; its marks were withheld: the entry is still to be told.
                (let [id (:id (session-entry sid))]
                  (q/cancel (registry/task id))
                  @(q/await (registry/task id) 2000)
                  (is (= [id] (mapv :id (registry/owed sid)))))))))
        (finally
          (when-let [entry (session-entry sid)]
            (q/cancel (registry/task (:id entry)))))))))
