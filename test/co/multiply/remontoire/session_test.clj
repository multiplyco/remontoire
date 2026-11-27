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
    (is (nil? (session/fetched-tools cid)))
    (session/fetched-tools! cid [:decls])
    (is (nil? (session/fetched-tools cid)) "a list recorded late does not bring it back")))


(deftest an-unknown-connection-is-adopted
  ;; A client that outlived a server restart keeps presenting its old id.
  (is (= "from-before-a-restart" (session/connected! "from-before-a-restart")))
  (session/fetched-tools! "from-before-a-restart" [:decls])
  (is (= [:decls] (session/fetched-tools "from-before-a-restart"))))


(deftest silent-connections-are-forgotten-when-another-is-adopted
  ;; An instance reached only through a gateway is never asked for a fresh
  ;; id (§15): the ids it sees are the gateway's, adopted as they arrive.
  (session/connected! "old")
  (swap! @#'session/connections assoc-in ["old" :last-seen] 0)
  (session/connected! "new")
  (is (= #{"new"} (set (keys @@#'session/connections)))))


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


(deftest nothing-written-about-a-session-brings-it-back
  ;; Every writer, called for a handle this server does not hold. Liveness
  ;; is the presence of a row, so one unguarded write revives a session.
  (let [gone "bcdfg"]
    (session/refresh! gone)
    (session/client! gone {:clientInfo {:name "late"}})
    (session/note! gone "late")
    (session/address! gone "http://late:1")
    (session/manual-read! gone)
    (session/nudged! gone)
    (session/current-ns! gone 'clojure.string)
    (session/note-evictions! gone [1])
    (session/clear-evictions! gone [1])
    (is (nil? (session/upload-dir! gone)))
    (is (not (session/live? gone)))
    (is (zero? (session/live-count)))))


(defn- mint-drawing-first
  [handle]
  (let [chars (atom (seq handle))
        draw  rand-nth]
    (with-redefs [rand-nth (fn [letters]
                             (let [[before] (swap-vals! chars next)]
                               (or (first before) (draw letters))))]
      (session/mint!))))


(deftest a-handle-names-one-session-for-the-life-of-the-jvm
  (let [old (session/mint!)]
    (is (true? (session/drop! old)) "ended by the call that found it")
    (is (false? (session/drop! old)) "and by no call after that")
    (let [fresh (mint-drawing-first old)]
      (is (not= old fresh))
      (is (not (session/live? old)))
      (session/current-ns! old 'clojure.string)
      (is (nil? (session/current-ns fresh)))))
  ;; And a JVM that has spent half the handles of a length is not left
  ;; drawing forever: the next ones are a letter longer.
  (let [length @#'session/handle-length
        spent  (quot (long (Math/pow 20 5)) 2)]
    (is (= (count (session/mint!)) (length 0) (length (dec spent))))
    (is (= (inc (length 0)) (length spent)))))


(deftest a-scratch-is-not-left-behind-by-a-session-that-ends-as-it-is-furnished
  (let [gone (session/mint!)
        sub  (session/scratch-sub-ns-sym gone "late")]
    (session/drop! gone)
    (is (nil? (session/furnish-for! gone sub)))
    (is (nil? (find-ns sub)) "refused, and nothing created"))
  ;; A furnishing under way when the session ends: the end waits for it,
  ;; and then takes what it made.
  (let [sid     (session/mint!)
        sub     (session/scratch-sub-ns-sym sid "midway")
        inside  (promise)
        proceed (promise)
        furnish session/furnish-scratch!]
    (with-redefs [session/furnish-scratch! (fn [ns-sym]
                                             (deliver inside true)
                                             (deref proceed 5000 nil)
                                             (furnish ns-sym))]
      (let [made  (future (session/furnish-for! sid sub))
            _     (is (true? (deref inside 5000 false)))
            ended (future (session/drop! sid))]
        (is (= :waiting (deref ended 200 :waiting)) "the sweep does not run through a furnishing")
        (deliver proceed true)
        (is (some? (deref made 5000 nil)))
        (is (true? (deref ended 5000 :waiting)) "and takes the session once it is over")))
    (is (nil? (find-ns sub)))
    (is (not (session/live? sid))))
  (is (some? (session/furnish-for! nil (session/scratch-ns-sym nil)))
    "the sessionless caller has no session to end"))
