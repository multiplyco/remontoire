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
