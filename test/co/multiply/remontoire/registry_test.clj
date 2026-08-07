(ns co.multiply.remontoire.registry-test
  "The retention invariants (§5): running work and unread results are never
   evicted, delivered entries go least-recently-touched first, eviction is
   announced, and release is punctuation with an audit."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q :refer [q]]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu]))


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


(deftest evals-lists-everything-with-attribution
  (let [s1 (session/mint!)
        s2 (session/mint!)
        a  (admit! s1 :a)
        b  (admit! s2 :b)]
    (binding [session/*session* s1]
      (let [by-id (into {} (map (juxt :id identity)) (registry/evals))]
        (is (contains? by-id b) "another session's entries are labeled, never hidden")
        (is (nil? (:by (by-id a))))
        (is (= (subs s2 0 8) (:by (by-id b))))
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
