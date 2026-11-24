(ns co.multiply.remontoire.mcp-test
  "JSON-RPC dispatch without a socket — the mcp namespace's own premise.
   Every test emulates the transport's one discipline: the delivery mark
   runs after the response (test-util/rpc!)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.api :as api]
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


(defn- create-session!
  "A session created over connection `cid`, as a legacy client does it."
  [cid]
  (second (re-find #"\"session-id\": \"([a-z]+)\""
            (tu/text-of (tu/rpc! cid {:jsonrpc "2.0" :id 1 :method "tools/call"
                                      :params  {:name "create-session" :arguments {}}})))))


(deftest initialize-teaches-and-remembers-the-client
  (let [cid  (session/connect!)
        resp (tu/rpc! cid (init-message))]
    (is (= 0 (:id resp)))
    (is (= "2025-03-26" (get-in resp [:result :protocolVersion]))
      "v0 echoes the proposed version")
    (is (map? (get-in resp [:result :serverInfo])))
    (is (string? (get-in resp [:result :instructions])))
    (is (zero? (session/live-count)) "a handshake opens a connection, not a session")
    (is (= "test-suite" (get-in (session/client (create-session! cid)) [:clientInfo :name]))
      "a session created over the connection knows its client")))


(deftest sessions-are-minted-by-a-tool-on-this-wire-too
  (let [sid (create-session! nil)]
    (is (session/live? sid) "no connection is needed to create one")
    (is (str/includes? (tu/text-of (tu/call! sid "eval" {:code "1"})) (str "session " sid))
      "the footer echoes the handle")))


(deftest calls-without-a-live-session-are-refused-and-mint-nothing
  (let [cid  (session/connect!)
        call #(tu/rpc! cid {:jsonrpc "2.0" :id 1 :method "tools/call"
                            :params  {:name "eval" :arguments %}})]
    (is (tu/tool-error? (call {:code "1"})))
    (is (tu/tool-error? (call {:code "1" :session-id "zzz"})))
    (is (tu/tool-error? (call {:code "1" :session-id cid}))
      "the connection's own id names no session")
    (is (zero? (session/live-count)))))


(deftest describe-weaves-into-the-instructions
  (mcp/configure! {:describe "the test rig"})
  (try
    (is (str/includes?
          (get-in (tu/rpc! (session/connect!) (init-message))
            [:result :instructions])
          "the test rig"))
    (finally (mcp/configure! {:describe nil}))))


(deftest the-tool-table
  (let [cid  (session/connect!)
        _    (is (true? (mcp/tools-changed? cid)) "nothing fetched yet")
        resp (tu/rpc! cid {:jsonrpc "2.0" :id 1 :method "tools/list"})]
    (is (= #{"create-session" "eval" "await" "cancel"}
           (into #{} (map :name) (get-in resp [:result :tools]))))
    (is (every? :inputSchema (get-in resp [:result :tools])))
    (is (every? #(some #{"session-id"} (get-in % [:inputSchema :required]))
          (remove #(= "create-session" (:name %)) (get-in resp [:result :tools])))
      "every tool but the one that mints takes the session")
    (is (false? (mcp/tools-changed? cid))
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


(deftest truncation-announces-its-size
  (let [sid (session/mint!)]
    (let [text (tu/text-of (tu/call! sid "eval" {:code "(vec (range 10000))"}))]
      (is (str/includes? text "truncated")
        "a cut rendering announces itself")
      (is (str/includes? text "9872")
        "and a counted collection says exactly how much was elided"))
    (is (str/includes?
          (tu/text-of (tu/call! sid "eval" {:code "{:a (vec (range 200))}"}))
          "72")
      "nested cuts count too")
    (is (str/includes?
          (tu/text-of (tu/call! sid "eval" {:code "(take 200 (range))"}))
          "truncated")
      "an uncounted seq announces the cut even without a total")
    (is (str/includes?
          (tu/text-of (tu/call! sid "eval"
                        {:code "(reduce (fn [a _] (vector a)) :x (range 20))"}))
          "truncated")
      "so does a subtree hidden below the print depth")
    (is (not (str/includes?
               (tu/text-of (tu/call! sid "eval" {:code "(vec (range 5))"}))
               "truncated"))
      "an uncut value says nothing")))


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
        r1  (tu/call! sid "eval" {:code "(q/sleep 200 :late)" :await 1})
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
        id  (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 150 :a)" :await 1}))
        ra  (tu/call! sid "await" {:all-of [id] :await 5000})]
    (is (str/includes? (tu/text-of ra) ":done"))
    (is (nil? (:touched (registry/entry id)))
      "a status is announcement; only res delivers")))


(deftest cancel-delivers-the-ending-it-authors
  (let [sid (session/mint!)
        id  (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 60000)" :await 1}))
        rc  (tu/call! sid "cancel" {:ids [id] :await 3000})]
    (is (str/includes? (tu/text-of rc) ":cancelled"))
    (is (= :cancelled (registry/status (registry/task id))))
    (is (some? (:touched (registry/entry id)))
      "a deliberately killed task is not immortal-unread")))


(deftest the-nudge-appears-once-per-session
  (let [sid (session/mint!)]
    (is (str/includes? (tu/text-of (tu/call! sid "eval" {:code "1"}))
          "(__r/manual)")
      "the session's first response carries the note")
    (is (not (str/includes? (tu/text-of (tu/call! sid "eval" {:code "2"}))
               "manual"))
      "and no later response repeats it, manual read or not"))
  (let [sid (session/mint!)
        r1  (tu/call! sid "eval"
              {:code "(do (with-out-str (__r/manual)) :read)"})]
    (is (not (str/includes? (tu/text-of r1) "(__r/manual)"))
      "a first eval that reads the manual arrives already unmarked")
    (is (true? (session/manual-read? sid)))))


(deftest the-nudge-survives-an-undelivered-response
  (let [sid (session/mint!)]
    ;; Dispatch without running the delivery mark — a response the client
    ;; never received, as after a request timeout.
    (mcp/handle-payload {:jsonrpc "2.0" :id 1 :method "tools/call"
                         :params  {:name "eval" :arguments {:code "1" :session-id sid}}}
      {:peer "test"})
    (is (str/includes? (tu/text-of (tu/call! sid "eval" {:code "2"}))
          "(__r/manual)")
      "an undelivered note burns nothing — the next response carries it")))


(deftest asking-how-an-entry-failed-delivers-only-a-failure
  (let [sid     (session/mint!)
        running (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 60000)" :await 1}))
        failed  (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 50) (throw (ex-info \"no\" {}))" :await 1}))]
    (try
      @(q/await (registry/task failed) 2000)
      (is (string? (api/failure running)))
      (is (nil? (:touched (registry/entry running))) "an entry still running has delivered nothing")
      (is (nil? (:touched (registry/entry failed))))
      (is (str/includes? (api/failure failed) "no"))
      (is (some? (:touched (registry/entry failed))) "a failure read is a failure delivered")
      (finally (q/cancel (registry/task running))))))


(deftest a-failure-is-read-whole-though-its-entry-goes-once-delivered
  ;; Delivered, an entry can be evicted, here by admissions that come the
  ;; moment the mark is made.
  (let [sid    (session/mint!)
        failed (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 50) (throw (ex-info \"no\" {}))" :await 1}))
        state  @#'registry/state
        admit! (fn []
                 (dotimes [_ 3]
                   (registry/touch!
                     (:id (registry/capture! {:task (doto (q/promise) (deliver :x)) :baseline (atom nil) :bank (tee/bank)
                                              :peer "test" :session sid :at (java.util.Date.)})))))
        fired  (atom false)]
    @(q/await (registry/task failed) 2000)
    (registry/capacity! 1)
    (add-watch state ::admit
      (fn [_ _ _ now]
        (when (and (:touched (get-in now [:entries failed])) (compare-and-set! fired false true))
          (admit!))))
    (try
      (is (str/includes? (api/failure failed) "no"))
      (is (true? @fired))
      (is (thrown? Exception (registry/entry failed)) "and it did go")
      (finally (remove-watch state ::admit)))))


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
        id  (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 60000)" :await 1}))
        r2  (tu/call! sid "eval" {:code "(__r/release! true)"})]
    (is (false? (tu/tool-error? r2))
      "the release's own eval survives its period")
    (is (str/includes? (tu/text-of r2) ":cancelled"))
    @(q/await (registry/task id) 5000)
    (is (= :cancelled (registry/status (registry/task id))))))


(deftest protocol-refusals
  (let [cid (session/connect!)]
    (is (some? (:error (tu/rpc! cid {:jsonrpc "2.0" :id 1 :method "tools/call"
                                     :params  {:name "nope" :arguments {}}}))))
    (is (some? (:error (tu/rpc! cid {:jsonrpc "2.0" :id 2 :method "bogus/method"}))))
    (is (nil? (tu/rpc! cid {:jsonrpc "2.0" :method "notifications/initialized"}))
      "notifications get no reply")))


(deftest batches-answer-only-what-warrants
  (let [resp (mcp/handle-payload
               [{:jsonrpc "2.0" :id 1 :method "ping"}
                {:jsonrpc "2.0" :method "notifications/initialized"}]
               {:connection (session/connect!) :peer "test"})]
    (is (= 1 (count resp)))
    (is (= 1 (:id (first resp))))))


(defn- eval-as
  "An `eval` of `code` as request 9 from `sender` — `{:connection …}`, or
   `{:peer …}` for one the wire knows only by address — on a thread of its
   own. Derefs to the response, its delivery mark not yet run."
  [sid sender code]
  (future
    (mcp/handle-payload
      {:jsonrpc "2.0" :id 9 :method "tools/call"
       :params  {:name "eval" :arguments {:code code :await 5000 :session-id sid}}}
      sender)))


(defn- cancel-as
  [sender]
  (mcp/handle-payload
    {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 9}}
    sender))


(defn- waiting-as
  "How many requests in flight go by id 9 from `sender`, and how many of
   them wait on work."
  [sender]
  (let [records (get @@#'mcp/in-flight [(:connection sender) 9])]
    [(count records) (count (filter #(:cancel @%) records))]))


(defn- eventually
  [pred]
  (loop [n 0]
    (cond (pred) true (< 300 n) false :else (do (Thread/sleep 10) (recur (inc n))))))


(defn- ended
  "How the eval a response reports on ended — its footer's news speaks of
   the session's other entries too."
  [response]
  (registry/status (registry/task (tu/id-of (deref response 3000 nil)))))


(def ^:private mine {:peer "one-machine" :connection "mine"})
(def ^:private theirs {:peer "one-machine" :connection "theirs"})
(def ^:private nobody {:peer "one-machine"})


(deftest a-cancellation-reaches-its-senders-request-and-no-other
  ;; Request ids are per client and start at zero: two clients routinely
  ;; have the same one in flight.
  (let [sid (session/mint!)]
    (let [a (eval-as sid mine "(Thread/sleep 3000)")
          b (eval-as sid theirs "(Thread/sleep 300)")]
      (is (eventually #(= [[1 1] [1 1]] [(waiting-as mine) (waiting-as theirs)])))
      (cancel-as mine)
      (is (= :cancelled (ended a)) "the one that asked is cancelled")
      (is (nil? (::mcp/delivered (meta @a))) "and, having no reader, marks nothing delivered")
      (is (= :done (ended b)) "the other runs on")
      (is (some? (::mcp/delivered (meta @b))) "and is delivered as ever"))
    ;; The notice that crosses its own request's response: by the time it
    ;; arrives its request is off the books, and a namesake is not.
    (let [b (eval-as sid theirs "(Thread/sleep 300)")]
      (is (eventually #(= [1 1] (waiting-as theirs))))
      (cancel-as mine)
      (is (= :done (ended b)) "a late cancellation finds nothing, not somebody else's request")
      (is (some? (::mcp/delivered (meta @b)))))
    (let [a (eval-as sid mine "(Thread/sleep 300)")
          b (eval-as sid mine "(Thread/sleep 300)")]
      (is (eventually #(= [2 2] (waiting-as mine))))
      (cancel-as mine)
      (is (= [:done :done] (mapv ended [a b])) "an id that names two requests stops neither")
      (is (every? #(nil? (::mcp/delivered (meta @%))) [a b]) "and either may be the one nobody reads"))
    ;; And twice in one batch, which the books hold as one record.
    (let [call {:jsonrpc "2.0" :id 9 :method "tools/call"
                :params  {:name "eval" :arguments {:code "(Thread/sleep 300)" :await 5000 :session-id sid}}}
          both (future (mcp/handle-payload [call call] mine))]
      (is (eventually #(= [1 1] (waiting-as mine))))
      (cancel-as mine)
      (let [answers (deref both 5000 nil)]
        (is (= [:done :done] (mapv #(registry/status (registry/task (tu/id-of %))) answers))
          "nor does one that names two in a batch")
        (is (every? #(nil? (::mcp/delivered (meta %))) answers))))
    (is (empty? @@#'mcp/in-flight) "nothing stays on the books")))


(deftest notices-without-a-sender-leave-work-and-delivery-alone
  (let [sid     (session/mint!)
        message {:id 9 :method "tools/call" :params {:name "eval" :arguments {:code ":ran" :session-id sid}}}
        notice  {:method "notifications/cancelled" :params {:requestId 9}}]
    (doseq [dispatch [mcp/handle-modern mcp/handle-payload]]
      (let [ctx (mcp/admit nobody message)]
        (try
          (dispatch notice nobody)
          (let [answer (dispatch message ctx)
                id     (tu/id-of answer)]
            (is (= :done (registry/status (registry/task id))))
            (when-let [mark! (::mcp/delivered (meta answer))] (mark!))
            (is (some? (:touched (registry/entry id)))))
          (finally (mcp/dismiss ctx)))))))


(deftest a-request-is-in-flight-until-its-response-is-out
  ;; What a transport does around the write: the request stays on the
  ;; books while its answer is rendered and sent, so a cancellation that
  ;; arrives then still finds it.
  (let [sid     (session/mint!)
        message {:jsonrpc "2.0" :id 9 :method "tools/call"
                 :params  {:name "eval" :arguments {:code ":quick" :await 5000 :session-id sid}}}
        ctx     (mcp/admit mine message)
        answer  (mcp/handle-payload message ctx)
        entry   (tu/id-of answer)]
    (is (= [1 0] (waiting-as mine)) "answered, not yet written, and still on the books")
    (is (some? (::mcp/delivered (meta answer))))
    (cancel-as mine)
    ;; The write goes out; the client, having cancelled, reads none of it.
    ((::mcp/delivered (meta answer)))
    (mcp/dismiss ctx)
    (is (nil? (:touched (registry/entry entry))) "a result nobody read is not marked delivered")
    (is (= [entry] (mapv :id (registry/owed sid))) "and stays owed")
    (is (empty? @@#'mcp/in-flight)))
  ;; An address-only notice cannot claim a stateless task poll during its write.
  (with-redefs-fn {#'mcp/task-grace 50}
    #(let [sid     (session/mint!)
           tasks   {:_meta {:io.modelcontextprotocol/clientCapabilities
                            {:extensions {:io.modelcontextprotocol/tasks {}}}}}
           created (mcp/handle-modern
                     {:jsonrpc "2.0" :id 8 :method "tools/call"
                      :params  (merge tasks {:name "eval" :arguments {:code "(Thread/sleep 150) :slow" :session-id sid}})}
                     mine)
           task-id (get-in created [:result :taskId])
           entry   (parse-long (second (str/split task-id #"-")))
           poll    {:jsonrpc "2.0" :id 9 :method "tasks/get" :params (merge tasks {:taskId task-id})}
           _       (is (eventually (fn [] (not= :running (registry/status (registry/task entry))))))
           ctx     (mcp/admit nobody poll)
           answer  (mcp/handle-modern poll ctx)]
       (is (= "completed" (get-in answer [:result :status])))
       (mcp/handle-modern {:method "notifications/cancelled" :params {:requestId 9}} nobody)
       ((::mcp/delivered (meta answer)))
       (mcp/dismiss ctx)
       (is (some? (:touched (registry/entry entry)))))))


(deftest a-cancellation-that-arrives-before-the-wait-is-acted-on-as-it-begins
  (let [sid     (session/mint!)
        message {:jsonrpc "2.0" :id 9 :method "tools/call"
                 :params  {:name "eval" :arguments {:code "(Thread/sleep 3000)" :await 5000 :session-id sid}}}
        ctx     (mcp/admit mine message)]
    (cancel-as mine)
    (let [response (mcp/handle-payload message ctx)]
      (mcp/dismiss ctx)
      (is (= :cancelled (registry/status (registry/task (tu/id-of response)))))
      (is (nil? (::mcp/delivered (meta response)))))))


(deftest disconnecting-an-await-only-ends-the-wait
  (let [sid     (session/mint!)
        id      (tu/id-of (tu/call! sid "eval" {:code "(q/sleep 60000)" :await 0}))
        message {:id 9 :method "tools/call"
                 :params {:name "await" :arguments {:session-id sid :all-of [id] :await 60000}}}
        ctx     (mcp/admit nobody message)
        close!  (mcp/on-stream-close ctx 9)]
    (try
      ;; The first failed beat can precede installation of the wait hook.
      (close!)
      (let [answer (future (mcp/handle-modern message ctx))]
        (is (map? (deref answer 3000 nil)))
        (is (= :running (registry/status (registry/task id)))))
      (finally
        (q/cancel (registry/task id))
        (mcp/dismiss ctx)))))
