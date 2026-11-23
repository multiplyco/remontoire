(ns co.multiply.remontoire.session-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.test-util :as tu])
  (:import
    [java.nio.file Files LinkOption]))


(use-fixtures :each (fn [f] (tu/reset-state!) (f)))


(deftest scratch-namespaces-are-per-session
  (is (= 'co.multiply.remontoire.scratch (session/scratch-ns-sym nil))
    "the sessionless in-process caller keeps the shared scratch")
  (let [s1 (session/mint!)
        s2 (session/mint!)]
    (is (not= (session/scratch-ns-sym s1) (session/scratch-ns-sym s2)))
    (is (session/scratch? (session/scratch-ns-sym s1)))))


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


(deftest the-upload-directory-is-minted-on-demand-and-dies-with-the-session
  (let [sid (session/mint!)]
    (is (nil? (session/upload-dir sid)) "nothing exists until something is uploaded")
    (let [dir (session/upload-dir! sid)]
      (is (Files/isDirectory dir (make-array LinkOption 0)))
      (is (= dir (session/upload-dir! sid)) "one directory per session")
      (is (= {sid dir} (session/upload-dirs)))
      (spit (str dir "/a.edn") "{}")
      (session/drop! sid)
      (is (not (Files/exists dir (make-array LinkOption 0))) "staged files go with it"))
    (is (nil? (session/upload-dir! "ghost")))
    (is (not (session/live? "ghost")) "asking never mints a session")))


(deftest a-handle-this-server-does-not-hold-is-never-adopted
  (is (false? (session/refresh! "minted-before-a-restart")))
  (is (not (session/live? "minted-before-a-restart")))
  (is (true? (session/refresh! (session/mint!)))))


(deftest a-connection-is-not-a-session
  (let [cid (session/connect!)]
    (session/connection-client! cid {:clientInfo {:name "suite"}})
    (session/fetched-tools! cid [:decls])
    (is (= "suite" (get-in (session/connection-client cid) [:clientInfo :name])))
    (is (not (session/live? cid)))
    (is (= [] (session/roster)) "and never appears on the roster")
    (session/disconnect! cid)
    (is (nil? (session/connection-client cid)))
    (is (nil? (session/fetched-tools cid)))))


(deftest an-unknown-connection-is-adopted
  ;; A client that outlived a server restart keeps presenting its old id.
  (is (= "from-before-a-restart" (session/connected! "from-before-a-restart")))
  (session/fetched-tools! "from-before-a-restart" [:decls])
  (is (= [:decls] (session/fetched-tools "from-before-a-restart"))))


(deftest silent-connections-are-forgotten-when-another-opens
  (let [old (session/connect!)]
    (session/fetched-tools! old [:decls])
    (swap! @#'session/connections assoc-in [old :last-seen] 0)
    (let [fresh (session/connect!)]
      (is (nil? (session/fetched-tools old)))
      (is (contains? @@#'session/connections fresh)))))


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
