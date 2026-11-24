(ns co.multiply.remontoire.mcp-stateless-test
  "The 2026-07-28 era at the dispatch level: sessions minted as a tool, the
   tool list following each request's tasks declaration, and long evals
   answered as tasks the client follows (§11). Invariants, not wording."
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
     (second (re-find #"\"session-id\": \"([a-z]{5})\"" (tu/text-of resp))))))


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
    (is (contains? (get-in (first (filter #(= "eval" (:name %)) with)) [:inputSchema :properties])
          :background))
    (is (every? #(some #{"session-id"} (get-in % [:inputSchema :required]))
          (remove #(= "create-session" (:name %)) (concat with without))))))


(deftest every-result-names-its-kind
  (is (= "complete" (get-in (rpc! "ping" {}) [:result :resultType])))
  (is (some? (get-in (rpc! "server/discover" {}) [:result :instructions]))))


(deftest a-session-is-minted-and-recorded
  (let [sid (create-session!)]
    (is (re-matches #"[a-z]{5}" sid))
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


(deftest background-skips-the-grace
  (with-redefs-fn {#'mcp/task-grace 60000}
    #(let [sid     (create-session!)
           t0      (System/nanoTime)
           created (call! "eval" {:code "(Thread/sleep 60000)" :session-id sid :background true})
           waited  (quot (- (System/nanoTime) t0) 1000000)
           task-id (get-in created [:result :taskId])]
       (is (= "task" (get-in created [:result :resultType])))
       (is (< waited 5000) "answered without waiting out the grace")
       (rpc! "tasks/cancel" {:taskId task-id}))))


(deftest background-on-a-call-that-declares-no-tasks-is-said-not-to-apply
  ;; A client listed the tasks `eval`, calling it without the declaration:
  ;; the one mismatch the server can see, since nobody else is listed
  ;; `background`.
  (let [sid     (create-session! false)
        said?   #(str/includes? (tu/text-of %) "background")
        running (call! "eval" {:code "(Thread/sleep 400) :late" :session-id sid :background true :await 50} false)
        entry   (tu/id-of running)]
    (is (nil? (get-in running [:result :taskId])) "no task for a call that declared none")
    (is (said? running))
    (is (str/includes? (tu/text-of running) (str "(__r/res " entry ")")) "and how to pick the result up")
    (is (said? (call! "eval" {:code ":quick" :session-id sid :background true} false))
      "also when there was nothing left to wait for")
    (is (not (said? (call! "eval" {:code ":quick" :session-id sid} false)))
      "a call that asked for nothing is told nothing")
    (is (not (said? (call! "eval" {:code ":quick" :session-id sid :background false} false))))
    @(q/await (registry/task entry) 5000))
  (let [sid (create-session!)]
    (is (not (str/includes? (str (tu/text-of (call! "eval" {:code ":quick" :session-id sid :background true})))
               "background"))
      "and a call that declared tasks got what it asked for")))


(deftest tasks-cancel-is-real
  (let [sid     (create-session!)
        task-id (get-in (call! "eval" {:code "(Thread/sleep 60000)" :session-id sid})
                  [:result :taskId])]
    (is (= {} (dissoc (:result (rpc! "tasks/cancel" {:taskId task-id})) :resultType :_meta)))
    (Thread/sleep 200)
    (is (= "cancelled" (get-in (rpc! "tasks/get" {:taskId task-id}) [:result :status])))))


(def ^:private gate
  "What an eval in a test waits on until the test lets it go."
  (atom nil))


(defn- held-task!
  "A background eval for `sid` that waits on `gate` and then returns
   `:kept` — so its task id is in hand before it can settle. Returns
   `[task-id entry-id]`."
  [sid]
  (reset! gate (promise))
  (let [task-id (get-in (call! "eval" {:code (str "@@@#'" `gate " :kept") :session-id sid :background true})
                  [:result :taskId])]
    [task-id (some-> task-id (str/split #"-") second parse-long)]))


(defn- collectable?
  "Whether `task-id`'s result can still be collected once the registry has
   been put under pressure."
  [sid task-id]
  (registry/capacity! 1)
  (dotimes [n 4] (call! "eval" {:code (str n) :session-id sid}))
  (let [collected (rpc! "tasks/get" {:taskId task-id})]
    (and (= "completed" (get-in collected [:result :status]))
      (str/starts-with? (str (get-in collected [:result :result :content 0 :text])) ":kept"))))


(deftest a-cancel-that-comes-too-late-leaves-the-result-owed
  ;; The task had settled, unread, before the cancel came.
  (let [sid             (create-session!)
        [task-id entry] (held-task! sid)]
    (deliver @gate :go)
    @(q/await (registry/task entry) 2000)
    (rpc! "tasks/cancel" {:taskId task-id})
    (is (nil? (:touched (registry/entry entry))) "nothing was delivered")
    (is (collectable? sid task-id))))


(deftest a-cancel-that-loses-to-the-tasks-own-ending-leaves-the-result-owed
  ;; Running when the cancel is asked for, finished before it bites.
  (let [sid             (create-session!)
        [task-id entry] (held-task! sid)]
    (with-redefs-fn {#'q/cancel (fn [task]
                                  (deliver @gate :go)
                                  @(q/await task 2000))}
      #(rpc! "tasks/cancel" {:taskId task-id}))
    (is (= :done (registry/status (registry/task entry))))
    (is (nil? (:touched (registry/entry entry))))
    (is (collectable? sid task-id))))


(deftest a-cancel-that-ends-its-task-delivers-that-ending
  (let [sid             (create-session!)
        [task-id entry] (held-task! sid)]
    (rpc! "tasks/cancel" {:taskId task-id})
    @(q/await (registry/task entry) 2000)
    (is (= :cancelled (registry/status (registry/task entry))))
    (is (loop [n 0]
          (cond (:touched (registry/entry entry)) true
                (< 100 n) false
                :else (do (Thread/sleep 10) (recur (inc n)))))
      "once it has settled: nobody reads a task they killed")))


(deftest task-ids-name-only-their-owners-entries
  (let [sid     (create-session!)
        task-id (get-in (call! "eval" {:code "(Thread/sleep 600)" :session-id sid}) [:result :taskId])
        foreign (str/replace task-id #"^[a-z]+" (if (= "bbbbb" sid) "ccccc" "bbbbb"))]
    (is (= -32602 (get-in (rpc! "tasks/get" {:taskId foreign}) [:error :code])))
    (is (= -32602 (get-in (rpc! "tasks/get" {:taskId "nonsense"}) [:error :code])))))


(deftest tasks-need-the-declaration
  (is (= -32021 (get-in (rpc! "tasks/get" {:taskId "abc-1"} false) [:error :code]))))


(deftest without-the-declaration-long-evals-still-run
  (let [sid  (create-session! false)
        resp (call! "eval" {:code "(Thread/sleep 400) :x" :await 50 :session-id sid} false)]
    (is (nil? (get-in resp [:result :taskId])))
    (is (str/includes? (tu/text-of resp) "running"))))
