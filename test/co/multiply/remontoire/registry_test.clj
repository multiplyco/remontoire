(ns co.multiply.remontoire.registry-test
  "The retention invariants (§5): running work and unread results are never
   evicted, delivered entries go least-recently-touched first, eviction is
   announced, and release is punctuation with an audit."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q :refer [q]]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu])
  (:import
    [java.io File]
    [java.nio.file Path]))


(use-fixtures :each (fn [f] (tu/reset-state!) (f)))


(defn- capture!*
  "Admit an entry for `sid` around task `t`, with the shape the transport
   supplies. Returns capture!'s {:id :evicted}."
  [sid t & {:as extra}]
  (registry/capture! (merge {:task     t
                             :baseline (atom nil)
                             :bank     (tee/bank)
                             :peer     "test"
                             :session  sid
                             :at       (java.util.Date.)}
                       extra)))


(defn- admit!
  "A settled entry holding `v`. Returns the id."
  [sid v]
  (:id (capture!* sid (q v))))


(deftest ids-mint-sequentially-and-resolve
  (let [sid (session/mint!)
        a   (admit! sid :a)
        b   (admit! sid :b)]
    (is (= (inc a) b))
    (is (= :a @(registry/res a)))
    (is (= :b @(registry/res b)) "reading is safe to repeat")
    (is (= :b @(registry/res b)))))


(deftest unresolvable-ids-throw-with-the-reason
  (let [sid (session/mint!)]
    (admit! sid :a)
    (is (thrown? Exception (registry/res 999))
      "never assigned is refused")
    (is (= 999 (try (registry/res 999) (catch Exception e (:id (ex-data e))))))))


(deftest unread-results-survive-any-pressure
  (let [sid (session/mint!)]
    (registry/capacity! 2)
    (let [ids (mapv #(admit! sid %) (range 10))]
      (is (every? #(some? (registry/entry %)) ids)
        "none were read, so none are evictable — capacity notwithstanding"))))


(deftest running-work-survives-any-pressure
  (let [sid (session/mint!)
        p   (q/promise)
        rid (:id (capture!* sid p))]
    (registry/capacity! 1)
    (registry/touch! rid)
    (let [ids (mapv #(admit! sid %) (range 5))]
      (run! registry/res ids)
      (is (= :running (registry/status (registry/task rid))))
      (is (some? (registry/entry rid))
        "delivered but still running: protected"))
    ;; Once settled, its old touch makes it the LRU candidate.
    (deliver p :done)
    @(q/await p 1000)
    (admit! sid :pressure)
    (is (thrown? Exception (registry/entry rid)))))


(deftest eviction-is-lru-by-touch-and-announced
  (let [sid (session/mint!)]
    (registry/capacity! 3)
    (let [[a b c] [(admit! sid :a) (admit! sid :b) (admit! sid :c)]]
      (run! registry/res [a b c])
      (registry/res a)                                      ; refresh: order is now b c a
      (registry/res (admit! sid :d))
      (let [{evicted :evicted} (capture!* sid (q :e))]
        (is (= [b] evicted)
          "the least recently touched goes first, and the loss is reported")
        (is (thrown? Exception (registry/entry b)))
        (is (= :a @(registry/res a)) "the refreshed entry outlived its elder id")))))


(deftest a-session-ended-twice-is-ended-once
  (let [gone (session/mint!)
        live (session/mint!)
        a    (admit! gone :a)]
    (registry/capacity! 1)
    (registry/res a)
    (registry/release-session! gone)
    (let [b (admit! live :b)]
      (registry/res b)
      ;; Asked for again: what the session left behind is not made newer
      ;; than what the living have read since.
      (registry/release-session! gone)
      (admit! live :pressure)
      (is (= :b @(registry/res b)) "a result just read outlives one a session that ended left behind")
      (is (thrown? Exception (registry/entry a))))))


(deftest an-entry-admitted-after-its-session-ended-is-owed-to-nobody
  (let [gone (session/mint!)
        live (session/mint!)]
    (registry/capacity! 1)
    (registry/release-session! gone)
    ;; Work that began while the session lived, admitted once it had gone.
    ;; Nobody reads it: reading would lift the shield whoever it was kept for.
    (let [late (admit! gone :late)]
      (is (some? (registry/entry late)) "it is there for whoever holds its id")
      (run! #(registry/res (admit! live %)) (range 3))
      (is (thrown? Exception (registry/entry late)) "and is not kept for a reader that is gone"))))


(deftest an-eval-admitted-as-its-session-ends-is-owed-to-nobody
  ;; The session ends where its eval has begun and is about to be admitted.
  (let [gone    (session/mint!)
        live    (session/mint!)
        capture registry/capture!]
    (registry/capacity! 1)
    (let [late (with-redefs [registry/capture! (fn [entry]
                                                 ;; Under way: its namespace resolved, its first form running.
                                                 (Thread/sleep 50)
                                                 (registry/release-session! (:session entry))
                                                 (capture entry))]
                 ;; In a host namespace, so that the scratch going with
                 ;; the session does not stop it; and answered while it
                 ;; runs, so that the answer delivers no result.
                 (tu/id-of (tu/call! gone "eval" {:code "(Thread/sleep 300) :late" :ns "clojure.string" :await 1})))
          task (registry/task late)]
      (is (= :running (registry/status task)) "the eval was begun, and answered as running")
      @(q/await task 5000)
      (is (= :late @task) "it ran to its end")
      (run! #(registry/res (admit! live %)) (range 3))
      (is (thrown? Exception (registry/entry late)) "and its result is not kept for a reader that is gone"))))


(deftest an-entry-its-admission-lapsed-is-not-lapsed-again-by-the-sweep
  ;; A session ends by losing its row and then sweeping its entries. An
  ;; entry admitted between the two is lapsed by its admission, a live
  ;; session reads a result of its own, and the sweep comes last.
  (let [gone  (session/mint!)
        live  (session/mint!)
        drop! session/drop!
        late  (atom nil)
        read  (atom nil)]
    (registry/capacity! 1)
    (with-redefs [session/drop! (fn [sid]
                                  (let [went? (drop! sid)]
                                    (reset! late (admit! gone :late))
                                    (reset! read (admit! live :read))
                                    (registry/res @read)
                                    went?))]
      (registry/release-session! gone))
    (admit! live :pressure)
    (is (= :read @(registry/res @read)) "what the living read last is what is kept")
    (is (thrown? Exception (registry/entry @late)) "over what a session that ended left behind")))


(deftest an-entry-the-sweep-lapsed-is-not-lapsed-again-by-its-admission
  ;; The other order: the entry is in when the sweep runs, and its
  ;; admission looks at the session only afterwards.
  (let [gone  (session/mint!)
        live  (session/mint!)
        live? session/live?
        armed (atom true)
        read  (atom nil)]
    (registry/capacity! 1)
    (let [late (with-redefs [session/live? (fn [sid]
                                             (when (and (= gone sid) (compare-and-set! armed true false))
                                               (registry/release-session! gone)
                                               (reset! read (admit! live :read))
                                               (registry/res @read))
                                             (live? sid))]
                 (admit! gone :late))]
      (is (false? @armed) "the admission looked at its session")
      (admit! live :pressure)
      (is (= :read @(registry/res @read)))
      (is (thrown? Exception (registry/entry late))))))


(deftest an-eval-is-answered-though-its-entry-was-evicted
  ;; Its session ends as it is admitted, so its result is owed to nobody,
  ;; and others' admissions take the entry before the call has answered.
  (let [gone     (session/mint!)
        live     (session/mint!)
        capture  registry/capture!
        waiting  @#'mcp/await-request]
    (registry/capacity! 1)
    (let [answer (with-redefs [registry/capture!  (fn [entry]
                                                    (when (= gone (:session entry))
                                                      ;; Under way: its namespace resolved.
                                                      (Thread/sleep 50)
                                                      (registry/release-session! gone))
                                                    (capture entry))
                               mcp/await-request (fn [task ms request]
                                                   (let [waited (waiting task ms request)]
                                                     (run! #(registry/res (admit! live %)) (range 3))
                                                     waited))]
                   (tu/call! gone "eval" {:code ":late" :ns "clojure.string" :await 5000}))]
      (is (thrown? Exception (registry/entry (tu/id-of answer))) "the entry is gone by the time the call answers")
      (is (not (tu/tool-error? answer)) "and the call is answered with what it came to")
      (is (str/starts-with? (str (tu/text-of answer)) ":late")))))


(deftest another-sessions-losses-are-queued-for-its-owner
  (let [s1 (session/mint!)
        s2 (session/mint!)]
    (registry/capacity! 1)
    ;; Two live sessions widen the pool (+10), so pressure needs volume.
    (let [ids (mapv #(admit! s1 %) (range 15))]
      (run! registry/res ids)
      (capture!* s2 (q :intruder))
      (is (seq (session/pending-evictions s1))
        "an id must never stop resolving in silence, whoever's admission did it")
      (is (nil? (session/pending-evictions s2))))))


(deftest release-comma-frees-settled-unread-only
  (let [sid (session/mint!)
        a   (admit! sid :a)
        b   (admit! sid :b)
        t   (q/task (Thread/sleep 60000))
        r   (:id (capture!* sid t))]
    (try
      (let [{:keys [released cancelled]} (registry/release! sid false nil)]
        (is (= #{a b} (set released)))
        (is (= [] cancelled) "a comma leaves running work alone")
        (is (= :running (registry/status (registry/task r)))))
      ;; Released entries have joined the LRU pool.
      (registry/capacity! 1)
      (run! #(registry/res (admit! sid %)) (range 3))
      (is (thrown? Exception (registry/entry a)))
      (finally (q/cancel t)))))


(deftest release-period-cancels-running-except-self
  (let [sid       (session/mint!)
        self-bank (tee/bank)
        t1        (q/task (Thread/sleep 60000))
        t2        (q/task (Thread/sleep 60000))
        r1        (:id (capture!* sid t1 :bank self-bank))
        r2        (:id (capture!* sid t2))]
    (try
      (let [{:keys [cancelled]} (registry/release! sid true self-bank)]
        (is (= [r2] cancelled))
        @(q/await t2 2000)
        (is (= :cancelled (registry/status (registry/task r2))))
        (is (= :running (registry/status (registry/task r1)))
          "punctuation ends the leg behind the caller, not the sentence being spoken"))
      (finally (q/cancel t1)))))


(deftest settled-unseen-is-owed-to-its-owner-once
  (let [sid (session/mint!)
        a   (admit! sid :a)]
    (is (= [{:id a :status :done}] (registry/owed sid)))
    (is (= [] (registry/owed "stranger")) "the news is owner-scoped")
    (registry/told! [a])
    (is (= [] (registry/owed sid)) "told once")
    (is (nil? (:touched (registry/entry a)))
      "announcing is not delivery: the entry stays protected")))


(deftest a-dead-sessions-claims-lapse-at-the-next-admission
  (let [sid (session/mint!)
        a   (admit! sid :a)]
    (swap! @#'session/state assoc-in [sid :last-seen] 0)
    (admit! (session/mint!) :other)                         ; admission reaps the dead
    (is (not-any? #(= sid (:sid %)) (session/roster)))
    (is (= :a @(registry/res a))
      "death degrades — the entry stays res-able until pressure claims it")))


(deftest a-session-whose-files-cannot-be-deleted-still-ends
  ;; Its upload directory is closed to the JVM. The admission that reaps it
  ;; goes through, and the claims lapse as for any other dead session.
  (let [sid (session/mint!)
        a   (admit! sid :a)
        dir (Path/.toFile (session/upload-dir! sid))]
    (spit (File. dir "staged") "x")
    (File/.setReadable dir false false)
    (File/.setExecutable dir false false)
    (try
      (swap! @#'session/state assoc-in [sid :last-seen] 0)
      (let [b (admit! (session/mint!) :other)]
        (is (= :other @(registry/res b)) "the admitting eval is on the books")
        (is (not (session/live? sid)))
        (is (some? (:touched (registry/entry a))) "and what the dead session owned is no longer protected"))
      (finally
        (File/.setReadable dir true false)
        (File/.setExecutable dir true false)
        (run! File/.delete (reverse (file-seq dir)))))))


(deftest a-session-that-woke-as-the-sweep-reached-it-is-kept
  ;; The sweep lists the silent, then ends them. A call let in between the
  ;; two has refreshed its session and may have started work.
  (let [sid    (session/mint!)
        silent session/expired]
    (swap! @#'session/state assoc-in [sid :last-seen] 0)
    (with-redefs [session/expired (fn []
                                    (let [listed (silent)]
                                      (session/refresh! sid)
                                      listed))]
      (let [a (admit! sid :a)]
        (is (session/live? sid) "listed as silent, awake by the time it would have been ended")
        (is (= [{:id a :status :done}] (registry/owed sid)) "and what it started is still its own")))))


(deftest evals-lists-everything-with-attribution
  (let [s1 (session/mint!)
        s2 (session/mint!)
        a  (admit! s1 :a)
        b  (admit! s2 :b)]
    (binding [session/*session* s1]
      (let [by-id (into {} (map (juxt :id identity)) (registry/evals))]
        (is (contains? by-id b) "another session's entries are labeled, never hidden")
        (is (nil? (:by (by-id a))))
        (is (= s2 (:by (by-id b))))
        (is (every? #(contains? % :status) (vals by-id)))))))


(deftest status-triage
  (let [p (q/promise)]
    (is (= :running (registry/status p)))
    (deliver p :ok)
    @(q/await p 1000)
    (is (= :done (registry/status p))))
  (let [t (q/task (throw (ex-info "boom" {})))]
    @(q/await t 1000)
    (is (= :failed (registry/status t))))
  (let [t (q/task (Thread/sleep 60000))]
    (q/cancel t)
    @(q/await t 2000)
    (is (= :cancelled (registry/status t))
      "cancellation reports as itself, not as failure")))
