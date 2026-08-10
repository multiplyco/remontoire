(ns co.multiply.remontoire.api-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.remontoire.api :as api]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu]
    [co.multiply.scoped :as sc]))


(use-fixtures :each (fn [f] (tu/reset-state!) (f)))


(deftest manual-prints-the-prose-and-returns-the-publics
  (let [sid    (session/mint!)
        result (volatile! nil)
        prose  (binding [session/*session* sid]
                 (with-out-str (vreset! result (api/manual))))]
    (is (seq prose) "the authored half is printed")
    (is (= 'co.multiply.remontoire.api (:ns @result)))
    (is (seq (:vars @result)))
    (is (not-any? :private (:vars @result)) "publics only")
    (is (every? :doc (:vars @result)) "docstrings carried whole")
    (is (true? (session/manual-read? sid))
      "reading marks the session as informed")))


(deftest manual-tolerates-the-sessionless-caller
  ;; A human at the actual REPL: no session on the rails, and the call
  ;; still works — the mark is simply a no-op.
  (let [result (volatile! nil)]
    (with-out-str (vreset! result (api/manual)))
    (is (map? @result))))


(deftest scoped-fn-carries-scope-to-foreign-threads
  (let [f      (sc/scoping [tee/*bank* :marked]
                 (api/scoped-fn (fn [] (sc/ask tee/*bank* nil))))
        result (promise)]
    (Thread/.start (Thread. ^Runnable (fn [] (deliver result (f)))))
    (is (= :marked (deref result 2000 :timeout))
      "the captured scope re-establishes on a raw thread")))
