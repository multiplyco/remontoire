(ns co.multiply.remontoire.session-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.test-util :as tu]))


(use-fixtures :each (fn [f] (tu/reset-state!) (f)))


(deftest scratch-namespaces-are-per-session
  (is (= 'co.multiply.remontoire.scratch (session/scratch-ns-sym nil))
    "the sessionless in-process caller keeps the shared scratch")
  (is (not= (session/scratch-ns-sym "abc-123-xyz")
            (session/scratch-ns-sym "def-456-uvw")))
  ;; an adopted sid is client-chosen text; whatever it holds, the scratch
  ;; name must be a namespace-legal symbol
  (doseq [sid ["!!!///:::" "we ird" ""]]
    (let [sym (session/scratch-ns-sym sid)]
      (is (symbol? sym))
      (is (str/starts-with? (str sym) "co.multiply.remontoire.scratch.s"))
      (is (nil? (re-find #"[^A-Za-z0-9.]" (str sym)))))))


(deftest scratch-compartments-nest-under-the-session-scratch
  (is (= (symbol (str (session/scratch-ns-sym "abc-123") ".kbtest"))
         (session/scratch-sub-ns-sym "abc-123" "kbtest"))
    "a plain label is kept whole under the session's scratch")
  (is (not= (session/scratch-sub-ns-sym "abc-123" "kbtest")
            (session/scratch-sub-ns-sym "def-456" "kbtest"))
    "same label, different sessions — different namespaces")
  (is (= (session/scratch-sub-ns-sym "abc-123" "kbtest")
         (session/scratch-sub-ns-sym "abc-123" "kbtest"))
    "the label is the compartment's identity — deterministic, no counter")
  ;; a label is caller-chosen text; whatever it holds, the result is a
  ;; namespace-legal symbol nested under the session's scratch
  (doseq [label ["k.b/test!" "" "2var" "-x" "we ird"]]
    (let [sym (session/scratch-sub-ns-sym "abc-123" label)]
      (is (symbol? sym))
      (is (str/starts-with? (str sym)
            (str (session/scratch-ns-sym "abc-123") ".")))
      (is (re-matches #"[A-Za-z][A-Za-z0-9_-]*"
            (peek (str/split (str sym) #"\.")))
        (str "segment for " (pr-str label) " opens with a letter")))))


(deftest drop-reaps-the-scratch-family
  (let [sid  (session/mint!)
        home (session/scratch-ns-sym sid)
        sub  (session/scratch-sub-ns-sym sid "kbtest")]
    (session/furnish-scratch! home)
    (session/furnish-scratch! sub)
    (is (= [home sub] (session/scratch-nses sid))
      "the family lists home first, compartments after")
    (session/drop! sid)
    (is (nil? (find-ns home)))
    (is (nil? (find-ns sub)) "compartments die with the session")
    (is (= [] (session/scratch-nses sid)))))


(deftest unknown-ids-are-adopted-not-refused
  (session/seen! "minted-before-a-restart")
  (is (some #(= "minted-before-a-restart" (:sid %)) (session/roster))))


(deftest conversational-state-needs-a-live-session
  (let [sid (session/mint!)]
    (is (false? (session/manual-read? sid)))
    (session/manual-read! sid)
    (is (true? (session/manual-read? sid)))
    ;; a dead sid is not recreated by any of the marks
    (session/manual-read! "ghost")
    (session/note! "ghost" "boo")
    (session/note-evictions! "ghost" [1 2])
    (is (not (contains? @@#'session/state "ghost")))))


(deftest silence-expires-a-session
  (let [sid (session/mint!)]
    (is (= [] (session/expired)) "a fresh session is live")
    (swap! @#'session/state assoc-in [sid :last-seen] 0)
    (is (= [sid] (session/expired)))))
