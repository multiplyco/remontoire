(ns co.multiply.remontoire.evaluate-test
  "The eval model (§9, §11): an eval is an implicit qdo, tasks ground at
   form borders, orphans die there, namespaces stick per session, and
   output is attributed to the eval that spoke it."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.evaluate :as evaluate]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu]))


(use-fixtures :once
  (fn [f]
    (tu/reset-state!)
    (tee/install!)
    (try (f) (finally (tee/uninstall!)))))


(defn- run!!
  "Evaluate `code` for `sid` and settle, bounded.
   Returns {:task :bank :details :error :value}."
  ([code sid] (run!! code nil sid))
  ([code ns-str sid]
   (let [{:keys [task details bank]} (evaluate/evaluate code ns-str sid)]
     @(q/await task 10000)
     {:task    task
      :bank    bank
      :details (deref details 1000 nil)
      :error   (q/get-ex task)
      :value   (when-not (q/exceptional? task) @task)})))


(deftest the-last-forms-value-is-the-value
  (let [{:keys [value details error]} (run!! "(+ 1 2) (* 3 4)" (session/mint!))]
    (is (nil? error))
    (is (= 12 value))
    (is (= 2 (:forms details)))))


(deftest a-returned-task-runs-and-grounds
  (is (= :slept (:value (run!! "(q/sleep 30 :slept)" (session/mint!))))))


(deftest forms-ground-in-order
  ;; The second form's task completes before the third form runs — an eval
  ;; is an implicit qdo, not three racing forms.
  (is (= [:first :second]
         (:value (run!! (str "(def a (atom [])) "
                          "(q/task (Thread/sleep 40) (swap! a conj :first)) "
                          "(swap! a conj :second)")
                   (session/mint!))))))


(deftest orphans-die-with-the-eval-compelled-work-does-not
  (let [sid (session/mint!)]
    ;; Orphans: spawned but neither returned nor composed — the manual's
    ;; `dotimes` trap. The eval neither waits for them nor lets them
    ;; outlive it.
    (let [{:keys [value error]}
          (run!! (str "(def hit (atom 0)) "
                   "(dotimes [_ 3] (q/task (Thread/sleep 300) (swap! hit inc))) "
                   ":done")
            sid)]
      (is (nil? error))
      (is (= :done value) "the eval does not wait for orphans"))
    (Thread/sleep 600)
    (is (zero? (:value (run!! "@hit" sid)))
      "orphans were cancelled when the eval settled — none completed")
    ;; The moat: compelled work runs to completion.
    (run!! (str "(def kept (atom 0)) "
             "(q/compel (q/task (Thread/sleep 100) (swap! kept inc))) "
             ":done")
      sid)
    (Thread/sleep 600)
    (is (= 1 (:value (run!! "@kept" sid))))))


(deftest scratch-is-prewired
  (let [sid (session/mint!)]
    (is (= 3 (:value (run!! "(qlet [x (q 1) y (q 2)] (+ x y))" sid)))
      "the q-prefixed macros are referred bare in scratch")
    (is (= 1 (:value (run!! "(q/task 1)" sid)))
      "Quiescent is aliased q — the committed-source spelling")
    (is (true? (:value (run!! "(vector? (__r/nses))" sid)))
      "__r resolves in scratch")
    (is (true? (:value (run!! "(vector? (evals))" sid)))
      "res/evals are referred in scratch")))


(deftest the-namespace-sticks-per-session
  (let [sid (session/mint!)]
    (try
      (is (= 'remontoire.test.hop (-> (run!! "(ns remontoire.test.hop)" sid) :details :ns)))
      (is (= "remontoire.test.hop" (:value (run!! "(str *ns*)" sid)))
        "an un-namespaced call continues where the last one ended")
      (finally (remove-ns 'remontoire.test.hop)))))


(deftest an-explicit-namespace-must-exist
  (let [sid (session/mint!)
        {:keys [error]} (run!! "1" "no.such.ns" sid)]
    (is (some? error))
    (is (= 'no.such.ns
           (:ns (some ex-data (take-while some? (iterate ex-cause error))))))
    (is (nil? (session/current-ns sid))
      "the namespace was never entered — the session has not moved")))


(deftest sessions-have-scratch-of-their-own
  (let [s1 (session/mint!)
        s2 (session/mint!)]
    (is (not= (session/scratch-ns-sym s1) (session/scratch-ns-sym s2)))
    (run!! "(def marker :one)" s1)
    (run!! "(def marker :two)" s2)
    (is (= :one (:value (run!! "marker" s1))))
    (is (= :two (:value (run!! "marker" s2))))))


(deftest scratch-compartments-are-prewired-and-sticky
  (let [sid (session/mint!)
        sub (session/scratch-sub-ns-sym sid "kbtest")]
    (let [{:keys [value details error]}
          (run!! (str "(__r/scratch! \"kbtest\") "
                   "(def marker :kb) "
                   "(qlet [x (q 1) y (q 2)] (+ x y))")
            sid)]
      (is (nil? error))
      (is (= 3 value) "the compartment is wired like scratch")
      (is (= sub (:ns details)) "scratch! switches, and the switch sticks"))
    (is (= :kb (:value (run!! "marker" sid)))
      "an un-namespaced call continues in the compartment")
    (is (nil? (:value (run!! "(__r/scratch!) (resolve 'marker)" sid)))
      "no label is home — the main scratch, where the compartment's defs are not")
    (is (= :kb (:value (run!! "(__r/scratch! \"kbtest\") marker" sid)))
      "the same label re-enters the same compartment, defs intact")
    (is (= [(session/scratch-ns-sym sid) sub]
           (:value (run!! "(__r/scratches)" sid)))
      "the family lists home first, this session's compartments only")))


(deftest compartments-are-per-session
  (let [s1 (session/mint!)
        s2 (session/mint!)]
    (run!! "(__r/scratch! \"kb\") (def marker :one)" s1)
    (run!! "(__r/scratch! \"kb\") (def marker :two)" s2)
    (is (= :one (:value (run!! "marker" s1))))
    (is (= :two (:value (run!! "marker" s2))))))


(deftest retire-scratch-removes-and-lands-you-home
  (let [sid (session/mint!)
        sub (session/scratch-sub-ns-sym sid "doomed")]
    (run!! "(__r/scratch! \"doomed\") (def marker :doomed)" sid)
    (let [{:keys [value details error]} (run!! "(__r/retire-scratch! \"doomed\")" sid)]
      (is (nil? error))
      (is (= sub value) "the retired name is the report")
      (is (= (session/scratch-ns-sym sid) (:ns details))
        "retiring the ground you stand on moves you back to scratch"))
    (is (nil? (find-ns sub)))
    (is (nil? (:value (run!! "(__r/retire-scratch! \"doomed\")" sid)))
      "retiring what does not exist is a nil no-op")
    (is (some? (:error (run!! (str "(__r/retire-scratch! '"
                                (session/scratch-ns-sym sid) ")")
                         sid)))
      "the main scratch is not retirable")
    (is (some? (:error (run!! (str "(__r/retire-scratch! '"
                                (session/scratch-sub-ns-sym (session/mint!) "kb")
                                ")")
                         sid)))
      "another session's compartment is not yours to retire")))


(deftest aliases-follow-into-visited-namespaces
  (let [sid (session/mint!)]
    (try
      (run!! "(ns remontoire.test.visited)" sid)
      (is (= 'co.multiply.remontoire.api
             (:value (run!! "(:ns (__r/outline '__r))" "remontoire.test.visited" sid)))
        "__r resolves, and outline accepts it as an alias designator")
      (is (empty? (:value (run!! "(keys (select-keys (ns-aliases *ns*) '[q __q]))"
                            "remontoire.test.visited" sid)))
        "__r alone follows — Quiescent is aliased nowhere the model didn't do it")
      (finally (remove-ns 'remontoire.test.visited)))))


(deftest output-is-captured-per-eval-both-streams
  (let [{:keys [bank]} (run!! (str "(println \"hello-out\") "
                                "(binding [*out* *err*] (println \"hello-err\")) "
                                ":ok")
                         (session/mint!))
        s (tee/text bank)]
    (is (str/includes? s "hello-out"))
    (is (str/includes? s "hello-err") "*err* fills the same bank")))


(deftest attribution-follows-the-eval-not-the-clock
  (let [sid (session/mint!)
        e1  (evaluate/evaluate "(Thread/sleep 60) (println \"from-one\") :one" nil sid)
        e2  (evaluate/evaluate "(println \"from-two\") :two" nil sid)]
    @(q/await (:task e1) 5000)
    @(q/await (:task e2) 5000)
    (is (str/includes? (tee/text (:bank e1)) "from-one"))
    (is (not (str/includes? (tee/text (:bank e1)) "from-two"))
      "a concurrent eval's output lands in its own bank")
    (is (str/includes? (tee/text (:bank e2)) "from-two"))))


(deftest spawned-work-inherits-the-attribution
  ;; A future launched from top-level eval code rides Clojure conveyance;
  ;; the tee still knows whose output it is.
  (let [{:keys [bank]} (run!! "@(future (println \"from-a-future\") :ok)"
                         (session/mint!))]
    (is (str/includes? (tee/text bank) "from-a-future"))))
