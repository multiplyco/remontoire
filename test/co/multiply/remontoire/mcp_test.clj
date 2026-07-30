(ns co.multiply.remontoire.mcp-test
  "JSON-RPC dispatch without a socket — the mcp namespace's own premise.
   Every test emulates the transport's one discipline: the delivery mark
   runs after the response (test-util/rpc!)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu]))


(use-fixtures :once
  (fn [f]
    (tee/install!)
    (try (f) (finally (tee/uninstall!)))))


(use-fixtures :each (fn [f] (tu/reset-state!) (f)))


(defn- init-message
  []
  {:jsonrpc "2.0" :id 0 :method "initialize"
   :params  {:protocolVersion "2025-03-26"
             :clientInfo      {:name "test-suite" :version "0"}}})


(deftest initialize-teaches-and-remembers-the-client
  (let [sid  (session/mint!)
        resp (tu/rpc! sid (init-message))]
    (is (= 0 (:id resp)))
    (is (= "2025-03-26" (get-in resp [:result :protocolVersion]))
      "v0 echoes the proposed version")
    (is (map? (get-in resp [:result :serverInfo])))
    (is (string? (get-in resp [:result :instructions])))
    (is (= "test-suite" (get-in (session/client sid) [:clientInfo :name])))))


(deftest describe-weaves-into-the-instructions
  (mcp/configure! {:describe "the test rig"})
  (try
    (is (str/includes?
          (get-in (tu/rpc! (session/mint!) (init-message))
            [:result :instructions])
          "the test rig"))
    (finally (mcp/configure! {:describe nil}))))


(deftest the-tool-table
  (let [sid  (session/mint!)
        resp (tu/rpc! sid {:jsonrpc "2.0" :id 1 :method "tools/list"})]
    (is (= #{"eval" "await" "cancel"}
          (into #{} (map :name) (get-in resp [:result :tools]))))
    (is (every? :inputSchema (get-in resp [:result :tools])))
    (is (false? (mcp/tools-changed? sid))
      "the fetch marker advances when the list is actually served")))


(deftest eval-answers-value-first-with-id-and-namespace
  (let [sid  (session/mint!)
        resp (tu/call! sid "eval" {:code "(+ 1 2)"})]
    (is (false? (tu/tool-error? resp)))
    (is (str/starts-with? (tu/text-of resp) "3\n") "the value comes first")
    (is (pos-int? (tu/id-of resp)) "every response carries its entry id")
    (is (str/includes? (tu/text-of resp) "scratch")
      "and names the namespace it used")))


(deftest printed-output-rides-the-response
  (let [resp (tu/call! (session/mint!) "eval" {:code "(println \"marco\") :polo"})]
    (is (str/starts-with? (tu/text-of resp) ":polo"))
    (is (str/includes? (tu/text-of resp) ";; out"))
    (is (str/includes? (tu/text-of resp) "marco"))))


(deftest failure-is-a-result-not-a-protocol-error
  (let [resp (tu/call! (session/mint!) "eval"
               {:code "(throw (ex-info \"boom\" {:k 1}))"})]
    (is (true? (tu/tool-error? resp)))
    (is (str/includes? (tu/text-of resp) "boom"))
    (is (pos-int? (tu/id-of resp)) "a failed eval still has an id")))


(deftest the-footer-reports-where-the-session-actually-is
  ;; A nonexistent explicit ns was never entered; naming it in the footer
  ;; would read as the session having moved (smoke test, 2026-08-06).
  (let [sid    (session/mint!)
        resp   (tu/call! sid "eval" {:code "1" :ns "no.such.ns"})
        footer (some #(when (str/starts-with? % ";; id") %)
                 (str/split-lines (tu/text-of resp)))]
    (is (true? (tu/tool-error? resp)))
    (is (some? footer))
    (is (str/includes? footer "scratch"))
    (is (not (str/includes? footer "no.such.ns")))))


(deftest values-render-bounded
  (let [resp (tu/call! (session/mint!) "eval" {:code "(vec (range 1000))"})]
    (is (false? (tu/tool-error? resp)))
    (is (str/includes? (tu/text-of resp) "...") "the cut announces itself")
    (is (< (count (tu/text-of resp)) 10000))))


(deftest long-work-returns-running-and-its-settling-is-news
  (let [sid (session/mint!)
        r1  (tu/call! sid "eval" {:code "(__q/sleep 200 :late)" :await 1})
        id  (tu/id-of r1)]
    (is (str/includes? (tu/text-of r1) "running"))
    @(q/await (registry/task id) 5000)
    (let [r2 (tu/call! sid "eval" {:code ":check-in"})]
      (is (str/includes? (tu/text-of r2) ";; settled"))
      (is (str/includes? (tu/text-of r2) (str id " done"))))
    (is (not (str/includes? (tu/text-of (tu/call! sid "eval" {:code ":again"}))
               ";; settled"))
      "told once")
    (is (str/starts-with?
          (tu/text-of (tu/call! sid "eval" {:code (str "@(res " id ")")}))
          ":late")
      "the result waited, unread and protected")))


(deftest await-reports-without-delivering
  (let [sid (session/mint!)
        id  (tu/id-of (tu/call! sid "eval" {:code "(__q/sleep 150 :a)" :await 1}))
        ra  (tu/call! sid "await" {:all-of [id] :await 5000})]
    (is (str/includes? (tu/text-of ra) ":done"))
    (is (nil? (:touched (registry/entry id)))
      "a status is announcement; only res delivers")))


(deftest cancel-delivers-the-ending-it-authors
  (let [sid (session/mint!)
        id  (tu/id-of (tu/call! sid "eval" {:code "(__q/sleep 60000)" :await 1}))
        rc  (tu/call! sid "cancel" {:ids [id] :await 3000})]
    (is (str/includes? (tu/text-of rc) ":cancelled"))
    (is (= :cancelled (registry/status (registry/task id))))
    (is (some? (:touched (registry/entry id)))
      "a deliberately killed task is not immortal-unread")))


(deftest the-nudge-lives-exactly-until-the-manual-is-read
  (let [sid (session/mint!)]
    (is (str/includes? (tu/text-of (tu/call! sid "eval" {:code "1"}))
          "(__r/manual)"))
    (let [r2 (tu/call! sid "eval"
               {:code "(do (with-out-str (__r/manual)) :read)"})]
      (is (not (str/includes? (tu/text-of r2) "manual"))
        "the manual call's own response arrives already unmarked"))
    (is (true? (session/manual-read? sid)))
    (is (not (str/includes? (tu/text-of (tu/call! sid "eval" {:code "2"}))
               "manual")))))


(deftest evictions-are-announced-never-silent
  (let [sid (session/mint!)]
    (registry/capacity! 1)
    (let [texts (mapv #(tu/text-of (tu/call! sid "eval" {:code (str %)}))
                  (range 6))]
      (is (some #(str/includes? % ";; evicted") texts)))))


(deftest the-session-api-sees-the-wire-session
  (let [sid (session/mint!)]
    (tu/call! sid "eval" {:code "(__r/introduce! \"running the test suite\")"})
    (is (= "running the test suite"
          (:note (first (filter #(= sid (:sid %)) (session/roster))))))
    (is (str/starts-with?
          (tu/text-of (tu/call! sid "eval"
                        {:code "(:you (first (filter :you (__r/sessions))))"}))
          "true"))))


(deftest release-period-spares-the-eval-that-speaks-it
  (let [sid (session/mint!)
        id  (tu/id-of (tu/call! sid "eval" {:code "(__q/sleep 60000)" :await 1}))
        r2  (tu/call! sid "eval" {:code "(__r/release! true)"})]
    (is (false? (tu/tool-error? r2))
      "the release's own eval survives its period")
    (is (str/includes? (tu/text-of r2) ":cancelled"))
    @(q/await (registry/task id) 5000)
    (is (= :cancelled (registry/status (registry/task id))))))


(deftest protocol-refusals
  (let [sid (session/mint!)]
    (is (some? (:error (tu/rpc! sid {:jsonrpc "2.0" :id 1 :method "tools/call"
                                     :params  {:name "nope" :arguments {}}}))))
    (is (some? (:error (tu/rpc! sid {:jsonrpc "2.0" :id 2 :method "bogus/method"}))))
    (is (nil? (tu/rpc! sid {:jsonrpc "2.0" :method "notifications/initialized"}))
      "notifications get no reply")))


(deftest batches-answer-only-what-warrants
  (let [resp (mcp/handle-payload
               [{:jsonrpc "2.0" :id 1 :method "ping"}
                {:jsonrpc "2.0" :method "notifications/initialized"}]
               {:session (session/mint!) :peer "test"})]
    (is (= 1 (count resp)))
    (is (= 1 (:id (first resp))))))
