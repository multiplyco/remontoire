(ns co.multiply.remontoire.http-test
  "Real roundtrips: `init!` binds an ephemeral loopback port and the JDK's
   own HttpClient speaks actual MCP at it — handshake, origin policy,
   session discipline, async work spanning requests, and the SSE shape."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.test-util :as tu])
  (:import
    [com.sun.net.httpserver HttpServer]
    [java.net URI]
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
     HttpResponse HttpResponse$BodyHandlers]))


(def ^:dynamic ^String *url* nil)


(use-fixtures :once
  (fn [f]
    (tu/reset-state!)
    (remontoire/init! {:port            0
                       :describe        "the test rig"
                       :allowed-origins #{"https://ok.example"}})
    (let [server ^HttpServer (:server @@#'remontoire/running)
          port   (.getPort (.getAddress server))]
      (binding [*url* (str "http://127.0.0.1:" port "/mcp")]
        (try (f) (finally (remontoire/stop!)))))))


(def ^:private client (HttpClient/newHttpClient))


(defn- build
  ^HttpRequest [body {:keys [sid accept origin method]}]
  (let [builder (HttpRequest/newBuilder (URI/create *url*))]
    (case (or method :post)
      :post   (-> builder
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body))))
      :get    (.GET builder)
      :delete (.method builder "DELETE" (HttpRequest$BodyPublishers/noBody)))
    (when sid (.header builder "Mcp-Session-Id" sid))
    (when accept (.header builder "Accept" accept))
    (when origin (.header builder "Origin" origin))
    (.build builder)))


(defn- send!
  (^HttpResponse [body] (send! body nil))
  (^HttpResponse [body opts]
   (.send client (build body opts) (HttpResponse$BodyHandlers/ofString))))


(defn- parsed
  [^HttpResponse resp]
  (json/read-str (.body resp) :key-fn keyword))


(defn- header-of
  [^HttpResponse resp k]
  (.orElse (.firstValue (.headers resp) k) nil))


(defn- init-body
  []
  {:jsonrpc "2.0" :id 0 :method "initialize"
   :params  {:protocolVersion "2025-03-26"
             :clientInfo      {:name "http-suite" :version "0"}}})


(defn- initialize!
  "The client handshake; returns the server-minted session id."
  []
  (let [resp (send! (init-body))
        sid  (header-of resp "Mcp-Session-Id")]
    (is (= 200 (.statusCode resp)))
    (is (some? sid))
    sid))


(defn- call!
  [sid tool args]
  (parsed (send! {:jsonrpc "2.0" :id 1 :method "tools/call"
                  :params  {:name tool :arguments args}}
            {:sid sid})))


(defn- text-of
  [response]
  (get-in response [:result :content 0 :text]))


(defn- id-of
  [response]
  (some-> (re-find #";; id (\d+)" (text-of response)) second parse-long))


(deftest initialize-mints-a-session-and-teaches
  (let [resp (send! (init-body))
        body (parsed resp)]
    (is (= 200 (.statusCode resp)))
    (is (some? (header-of resp "Mcp-Session-Id")))
    (is (map? (get-in body [:result :serverInfo])))
    (is (str/includes? (get-in body [:result :instructions]) "the test rig")
      "init!'s :describe reaches the wire")))


(deftest bare-requests-are-refused
  (is (= 400 (.statusCode (send! {:jsonrpc "2.0" :id 1 :method "tools/list"})))
    "every client initializes before anything else"))


(deftest origins-are-checked-and-cors-never-offered
  (is (= 403 (.statusCode (send! (init-body) {:origin "https://evil.example"}))))
  (let [resp (send! (init-body) {:origin "https://ok.example"})]
    (is (= 200 (.statusCode resp)))
    (is (nil? (header-of resp "Access-Control-Allow-Origin"))
      "an allowed origin is served, never advertised")))


(deftest only-post-speaks
  (is (= 405 (.statusCode (send! nil {:method :get})))))


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
        (is (= 200 (.statusCode resp)))
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
      (is (= 405 (.statusCode (send! {:jsonrpc "2.0"})))
        "POST does not speak here")
      (is (= 403 (.statusCode
                   (send! nil {:method :get :origin "https://evil.example"}))))
      (is (= 200 (.statusCode
                   (send! nil {:method :get :origin "https://ok.example"})))))))


(deftest eval-roundtrip
  (let [sid (initialize!)
        r   (call! sid "eval" {:code "(+ 20 22)"})]
    (is (false? (get-in r [:result :isError])))
    (is (str/starts-with? (text-of r) "42"))
    (is (pos-int? (id-of r)))))


(deftest state-sticks-to-the-session
  (let [s1 (initialize!)
        s2 (initialize!)]
    (call! s1 "eval" {:code "(def http-marker 17)"})
    (is (str/starts-with? (text-of (call! s1 "eval" {:code "http-marker"})) "17"))
    (is (true? (get-in (call! s2 "eval" {:code "http-marker"}) [:result :isError]))
      "another session's scratch is its own")))


(deftest async-work-spans-requests
  (let [sid (initialize!)
        r1  (call! sid "eval" {:code "(__q/sleep 300 :late)" :await 1})
        id  (id-of r1)]
    (is (str/includes? (text-of r1) "running"))
    (is (str/includes? (text-of (call! sid "await" {:all-of [id] :await 5000}))
          ":done"))
    (is (str/starts-with?
          (text-of (call! sid "eval" {:code (str "@(res " id ")")}))
          ":late")
      "the id kept resolving across requests")))


(deftest cancel-over-the-wire
  (let [sid (initialize!)
        id  (id-of (call! sid "eval" {:code "(__q/sleep 60000)" :await 1}))]
    (is (str/includes? (text-of (call! sid "cancel" {:ids [id] :await 3000}))
          ":cancelled"))))


(deftest sse-streams-the-call
  (let [sid  (initialize!)
        resp (send! {:jsonrpc "2.0" :id 5 :method "tools/call"
                     :params  {:name "eval" :arguments {:code "(* 6 7)"}}}
               {:sid sid :accept "application/json, text/event-stream"})]
    (is (= 200 (.statusCode resp)))
    (is (str/starts-with? (or (header-of resp "Content-Type") "")
          "text/event-stream"))
    (let [messages (into []
                     (comp (filter #(str/starts-with? % "data: "))
                       (map #(json/read-str (subs % 6) :key-fn keyword)))
                     (str/split-lines (.body resp)))
          result   (first (filter :result messages))]
      (is (some? result) "the stream carries the one response message")
      (is (str/starts-with? (get-in result [:result :content 0 :text]) "42")))))


(deftest concurrent-evals-are-attributed-correctly
  ;; Three requests in flight at once, grounded in parallel by Quiescent:
  ;; each response carries its own value and its own printed output.
  (let [sid   (initialize!)
        ;; Built on the test thread, where *url* is bound; only the sends
        ;; ride tasks.
        req   (fn [n]
                (build {:jsonrpc "2.0" :id (+ 10 n) :method "tools/call"
                        :params  {:name      "eval"
                                  :arguments {:code (str "(do (Thread/sleep 100)"
                                                      " (println \"tick-" n "\")"
                                                      " (* " n " " n "))")}}}
                  {:sid sid}))
        tasks (mapv (fn [n]
                      (q/as-task (.sendAsync client (req n)
                                   (HttpResponse$BodyHandlers/ofString))))
                [2 3 4])
        texts (mapv (comp text-of parsed) @(q/task tasks))]
    (doseq [[n text] (map vector [2 3 4] texts)]
      (is (str/starts-with? text (str (* n n))))
      (is (str/includes? text (str "tick-" n)))
      (doseq [other (remove #{n} [2 3 4])]
        (is (not (str/includes? text (str "tick-" other)))
          "no output leaks between concurrent evals")))))


(deftest goodbye-releases-the-session
  (let [sid (initialize!)]
    (is (= 204 (.statusCode (send! nil {:method :delete :sid sid}))))
    (is (not-any? #(= sid (:sid %)) (session/roster)))))
