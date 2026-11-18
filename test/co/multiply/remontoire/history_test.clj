(ns co.multiply.remontoire.history-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.remontoire.api :as api]
    [co.multiply.remontoire.history :as history]))


(defn isolated-history
  [f]
  (with-redefs-fn {#'history/state (atom @@#'history/state)}
    #(do (api/nrepl-capture! {:enabled? true})
       (api/clear-nrepl-history!)
       (f))))


(use-fixtures :each isolated-history)


(defn- begin
  [session input]
  (history/begin! {:op "eval" :session session :id (str (random-uuid)) :code input}))


(deftest clip-keeps-bounded-prefixes-and-tails
  (doseq [[text limit prefix tail]
          [["" 3 "" ""]
           ["abc" 0 "" ""]
           ["abc" 2 "ab" "bc"]
           ["abc" 3 "abc" "abc"]
           ["abc" 5 "abc" "abc"]
           ["😀" 1 "\uD83D" "\uDE00"]]]
    (is (= prefix (#'history/clip text limit false)))
    (is (= tail (#'history/clip text limit true)))))


(deftest defaults-and-explicit-enablement
  (is (= {:enabled? false :max-entries 5 :max-chars 10000} @#'history/defaults))
  (api/nrepl-capture! {:enabled? false})
  (is (nil? (begin "a" "not recorded")))
  (is (empty? (:entries (api/nrepl-history))))
  (api/nrepl-capture! {:enabled? true})
  (is (int? (begin "a" "recorded"))))


(deftest summaries-and-transcripts-are-separate
  (let [a (begin "editor-a" "(+ 1 2)")
        b (begin "editor-b" "(/ 1 0)")]
    (history/response! a {:out "hello"})
    (history/response! a {:value "3" :ns "user"})
    (history/response! b {:err "division failed" :ex "ArithmeticException"})
    (history/response! b {:status #{:eval-error :done}})
    (is (= [b a] (mapv :id (:entries (api/nrepl-history)))))
    (is (every? #(not-any? (set (keys %)) [:input :output :events :status])
          (:entries (api/nrepl-history))))
    (is (= {:input "(+ 1 2)" :session "editor-a" :op "eval"}
           (select-keys (api/nrepl-transcript a) [:input :session :op])))
    (is (int? (:at (api/nrepl-transcript a))))
    (is (string? (:request-id (api/nrepl-transcript a))))
    (let [output (:output (api/nrepl-transcript a))]
      (is (str/includes? output "hello"))
      (is (str/includes? output "3"))
      (is (< (str/index-of output "hello") (str/index-of output "3"))))
    (is (str/includes? (:output (api/nrepl-transcript b)) "ArithmeticException"))
    (is (not-any? (set (keys (api/nrepl-transcript b)))
          [:status :events :bytes :input-truncated? :output-truncated?]))))


(deftest five-newest-starts-globally-even-when-older-work-is-running
  (let [ids (mapv #(begin (str "editor-" %) (str %)) (range 5))
        oldest (first ids)]
    (api/nrepl-transcript oldest)
    (history/response! oldest {:out "still running"})
    (let [newest (begin "another-editor" "sixth")]
      (is (= (vec (reverse (conj (subvec ids 1) newest)))
             (mapv :id (:entries (api/nrepl-history)))))
      (is (:missing (api/nrepl-transcript oldest)))
      (history/response! oldest {:value "late"})
      (is (:missing (api/nrepl-transcript oldest)))
      (is (= 5 (:entries (api/nrepl-capture!)))))))


(deftest input-prefix-and-output-tail-have-independent-utf16-limits
  (api/nrepl-capture! {:max-chars 10})
  (let [id (begin "a" (str (apply str (repeat 10 "😀")) "discarded"))]
    (history/response! id {:out (apply str (repeat 100 "x"))})
    (history/response! id {:err "終😀"})
    (let [{:keys [input output]} (api/nrepl-transcript id)]
      (is (= (apply str (repeat 5 "😀")) input))
      (is (= 10 (count input)))
      (is (= 10 (count output)))
      (is (str/includes? output "終😀"))
      (is (not (str/includes? output "x"))))))


(deftest exact-input-limit-and-smallest-limit
  (api/nrepl-capture! {:max-chars 1})
  (let [id (begin "a" "x")]
    (is (= "x" (:input (api/nrepl-transcript id))))
    (is (= "" (:output (api/nrepl-transcript id)))))
  (let [id (begin "a" "😀x")]
    (is (= "\uD83D" (:input (api/nrepl-transcript id))))))


(deftest huge-output-and-tiny-chunks-stay-bounded
  (api/nrepl-capture! {:max-chars 32})
  (let [id (begin "a" "code")]
    (history/response! id {:out (apply str (repeat 100000 "😀"))})
    (is (= 32 (count (:output (api/nrepl-transcript id)))))
    (dotimes [_ 2000] (history/response! id {:out "x"}))
    (is (= 32 (count (:output (api/nrepl-transcript id)))))
    (is (not (contains? (api/nrepl-transcript id) :events)))))


(deftest disabling-clears-history-and-reenabling-only-captures-new-evaluations
  (let [id (begin "a" "old")]
    (history/response! id {:out "before disabling"})
    (is (zero? (:entries (api/nrepl-capture! {:enabled? false}))))
    (is (:missing (api/nrepl-transcript id)))
    (history/response! id {:out "while disabled"})
    (is (nil? (begin "a" "started while disabled")))
    (api/nrepl-capture! {:enabled? true})
    (history/response! id {:out "after reenabling"})
    (is (empty? (:entries (api/nrepl-history))))
    (is (:missing (api/nrepl-transcript id)))
    (let [new-id (begin "a" "new")]
      (is (> new-id id))
      (history/response! new-id {:value "new result"})
      (is (str/includes? (:output (api/nrepl-transcript new-id)) "new result")))))


(deftest clear-does-not-reuse-ids-or-allow-late-resurrection
  (let [id (begin "a" "1")
        config (select-keys (api/nrepl-capture!) [:enabled? :max-entries :max-chars])]
    (api/clear-nrepl-history!)
    (history/response! id {:out "late"})
    (is (:missing (api/nrepl-transcript id)))
    (is (empty? (:entries (api/nrepl-history))))
    (is (= config (select-keys (api/nrepl-capture!) (keys config))))
    (is (> (begin "a" "2") id))
    (is (:missing (api/nrepl-transcript Long/MAX_VALUE)))))


(deftest limits-can-be-configured-before-enabling
  (api/nrepl-capture! {:enabled? false :max-entries 1 :max-chars 4})
  (api/nrepl-capture! {:enabled? true})
  (let [a (begin "a" "first")
        b (begin "a" "😀😀😀")]
    (is (:missing (api/nrepl-transcript a)))
    (is (= [b] (mapv :id (:entries (api/nrepl-history)))))
    (is (= "😀😀" (:input (api/nrepl-transcript b))))))


(deftest new-limits-apply-on-subsequent-starts-and-output-appends
  (let [a (begin "a" "oldest")
        b (begin "a" "abcdef")
        c (begin "a" "untouched")]
    (history/response! b {:out "0123456789"})
    (history/response! c {:out "untouched output"})
    (let [before (mapv api/nrepl-transcript [a b c])]
      (api/nrepl-capture! {:max-entries 2 :max-chars 4})
      (is (= before (mapv api/nrepl-transcript [a b c])))
      (history/response! b {:status #{:done}})
      (is (= before (mapv api/nrepl-transcript [a b c])))
      (history/response! b {:out "next"})
      (is (= 4 (count (:output (api/nrepl-transcript b)))))
      (is (str/includes? (:output (api/nrepl-transcript b)) "ext"))
      (is (= "abcdef" (:input (api/nrepl-transcript b))))
      (is (= [c b a] (mapv :id (:entries (api/nrepl-history)))))
      (let [d (begin "a" "new input")]
        (is (= [d c] (mapv :id (:entries (api/nrepl-history)))))
        (is (:missing (api/nrepl-transcript a)))
        (is (:missing (api/nrepl-transcript b)))
        (is (= "new " (:input (api/nrepl-transcript d))))
        (is (= (last before) (api/nrepl-transcript c)))
        (let [clipped (api/nrepl-transcript d)]
          (api/nrepl-capture! {:max-entries 5 :max-chars 10000})
          (is (= clipped (api/nrepl-transcript d)))
          (history/response! d {:out "new output"})
          (is (str/includes? (:output (api/nrepl-transcript d)) "new output")))))))


(deftest invalid-configuration-has-no-side-effects
  (let [id (begin "a" "keep me")
        before (api/nrepl-history)]
    (doseq [opts [{:enabled? :yes} {:max-entries 0} {:max-chars 0}
                  {:max-chars -1} {:max-entries 1.5} {:max-chars nil}
                  {:enabled? false :max-entries 0} {:typo 10}
                  {:entry-bytes 1024} {:total-bytes 4096} nil]]
      (is (thrown? clojure.lang.ExceptionInfo (api/nrepl-capture! opts)))
      (is (= before (api/nrepl-history))))
    (history/response! id {:out "still recording"})
    (is (str/includes? (:output (api/nrepl-transcript id)) "still recording"))
    (doseq [id [0 -1 "1" nil]]
      (is (thrown? clojure.lang.ExceptionInfo (api/nrepl-transcript id))))))


(deftest arbitrary-values-are-not-printed-realized-or-retained
  (let [touched (atom false)
        value (lazy-seq (reset! touched true) (throw (Exception. "must not realize")))
        id (begin "a" "1")]
    (history/response! id {:value value :status value
                           :nrepl.middleware.caught/throwable (Exception.)})
    (is (false? @touched))
    (is (= "" (:output (api/nrepl-transcript id))))))


(deftest concurrent-writers-preserve-every-chunk-within-the-limit
  (let [id (begin "a" "1")
        start (promise)
        workers (mapv (fn [i]
                        (future @start
                                (dotimes [j 20]
                                  (history/response! id {:out (str "<" i ":" j ">")}))))
                  (range 8))]
    (deliver start true)
    (doseq [worker workers] (is (not= ::timeout (deref worker 5000 ::timeout))))
    (let [output (:output (api/nrepl-transcript id))]
      (is (= 160 (count (re-seq #"<\d+:\d+>" output))))
      (doseq [i (range 8) j (range 20)]
        (is (str/includes? output (str "<" i ":" j ">")))))))
