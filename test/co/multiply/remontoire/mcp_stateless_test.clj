(ns co.multiply.remontoire.mcp-stateless-test
  "The 2026-07-28 era at the dispatch level: sessions minted as a tool, the
   tool list following each request's tasks declaration, and long evals
   answered as tasks the client follows (§11). Invariants, not wording."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu]))


(use-fixtures :once
  (fn [f]
    (tee/install!)
    (try (f) (finally (tee/uninstall!)))))


(use-fixtures :each
  (fn [f]
    (tu/reset-state!)
    ;; The grace shrunk so the background path is reachable in a test.
    (with-redefs-fn {#'mcp/task-grace 200} f)))


(defn- meta-of
  [tasks?]
  (cond-> {:io.modelcontextprotocol/protocolVersion "2026-07-28"
           :io.modelcontextprotocol/clientInfo      {:name "suite" :version "0"}}
    tasks? (assoc :io.modelcontextprotocol/clientCapabilities
             {:extensions {:io.modelcontextprotocol/tasks {}}})))


(defn- rpc!
  "One stateless message, then its delivery mark, as the transport runs it
   once the write goes out."
  ([method params] (rpc! method params true))
  ([method params tasks?]
   (let [resp (mcp/handle-modern {:jsonrpc "2.0" :id 1 :method method
                                  :params  (assoc params :_meta (meta-of tasks?))}
                {:peer "test"})]
     (when-let [mark! (::mcp/delivered (meta resp))] (mark!))
     resp)))


(defn- call!
  ([tool args] (call! tool args true))
  ([tool args tasks?] (rpc! "tools/call" {:name tool :arguments args} tasks?)))


(defn- create-session!
  ([] (create-session! true))
  ([tasks?]
   (let [resp (call! "create-session" {} tasks?)]
     (second (re-find #"\"session-id\": \"([a-z]{3})\"" (tu/text-of resp))))))


(defn- listed
  [tasks?]
  (get-in (rpc! "tools/list" {} tasks?) [:result :tools]))


(deftest the-list-follows-the-requests-declaration
  (let [with    (listed true)
        without (listed false)]
    (is (= #{"create-session" "eval"} (into #{} (map :name) with)))
    (is (= #{"create-session" "eval" "await" "cancel"} (into #{} (map :name) without)))
    (is (not (contains? (get-in (first (filter #(= "eval" (:name %)) with)) [:inputSchema :properties])
               :await))
      "a tasks client's eval has no deadline to set")
    (is (every? #(some #{"session-id"} (get-in % [:inputSchema :required]))
          (remove #(= "create-session" (:name %)) (concat with without))))))


(deftest every-result-names-its-kind
  (is (= "complete" (get-in (rpc! "ping" {}) [:result :resultType])))
  (is (some? (get-in (rpc! "server/discover" {}) [:result :instructions]))))


(deftest a-session-is-minted-and-recorded
  (let [sid (create-session!)]
    (is (re-matches #"[a-z]{3}" sid))
    (is (session/live? sid))
    (is (true? (:tasks (session/client sid))) "capabilities recorded at birth")))


(deftest calls-without-a-live-session-are-refused-and-mint-nothing
  (is (tu/tool-error? (call! "eval" {:code "1"})))
  (is (tu/tool-error? (call! "eval" {:code "1" :session-id "zzz"})))
  (is (zero? (session/live-count))))


(deftest quick-evals-answer-inline
  (let [sid  (create-session!)
        resp (call! "eval" {:code "(+ 1 2)" :session-id sid})]
    (is (str/starts-with? (tu/text-of resp) "3"))
    (is (str/includes? (tu/text-of resp) (str "session " sid)))))


(deftest hidden-tools-stay-callable
  (let [sid (create-session!)]
    (is (false? (tu/tool-error? (call! "await" {:await 0 :session-id sid}))))))


(deftest long-evals-become-tasks-the-client-follows
  (let [sid     (create-session!)
        created (call! "eval" {:code "(Thread/sleep 600) :slow" :session-id sid})
        task-id (get-in created [:result :taskId])
        entry   (some-> task-id (str/split #"-") second parse-long)]
    (is (= "task" (get-in created [:result :resultType])))
    (is (= "working" (get-in created [:result :status])))
    (is (str/starts-with? task-id (str sid "-")))
    (is (= "working" (get-in (rpc! "tasks/get" {:taskId task-id}) [:result :status])))
    (Thread/sleep 800)
    (let [done (rpc! "tasks/get" {:taskId task-id})]
      (is (= "completed" (get-in done [:result :status])))
      (is (str/starts-with? (get-in done [:result :result :content 0 :text]) ":slow")))
    (is (not (str/includes? (tu/text-of (call! "eval" {:code "1" :session-id sid})) ";; settled"))
      "a followed eval's ending is the client's to announce, never news")
    (is (some? (:touched (registry/entry entry))) "delivery through the task releases the entry")))


(deftest tasks-cancel-is-real
  (let [sid     (create-session!)
        task-id (get-in (call! "eval" {:code "(Thread/sleep 60000)" :session-id sid})
                  [:result :taskId])]
    (is (= {} (dissoc (:result (rpc! "tasks/cancel" {:taskId task-id})) :resultType :_meta)))
    (Thread/sleep 200)
    (is (= "cancelled" (get-in (rpc! "tasks/get" {:taskId task-id}) [:result :status])))))


(deftest task-ids-name-only-their-owners-entries
  (let [sid     (create-session!)
        task-id (get-in (call! "eval" {:code "(Thread/sleep 600)" :session-id sid}) [:result :taskId])
        foreign (str/replace task-id #"^[a-z]{3}" (if (= "bbb" sid) "ccc" "bbb"))]
    (is (= -32602 (get-in (rpc! "tasks/get" {:taskId foreign}) [:error :code])))
    (is (= -32602 (get-in (rpc! "tasks/get" {:taskId "nonsense"}) [:error :code])))))


(deftest tasks-need-the-declaration
  (is (= -32021 (get-in (rpc! "tasks/get" {:taskId "abc-1"} false) [:error :code]))))


(deftest without-the-declaration-long-evals-still-run
  (let [sid  (create-session! false)
        resp (call! "eval" {:code "(Thread/sleep 400) :x" :await 50 :session-id sid} false)]
    (is (nil? (get-in resp [:result :taskId])))
    (is (str/includes? (tu/text-of resp) "running"))))
