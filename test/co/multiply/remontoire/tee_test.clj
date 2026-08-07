(ns co.multiply.remontoire.tee-test
  "The bank invariants: bounded, tail-keeping, and never silently short —
   a drop is always announced, and `since` is an exact cursor."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [co.multiply.remontoire.tee :as tee]))


(deftest bank-keeps-the-tail-and-announces-the-drop
  (let [b (tee/bank)]
    (tee/deposit! b (apply str (repeat 40000 "x")))
    (tee/deposit! b "the-tail-end")
    (is (= 40012 (:total @b)))
    (let [s (tee/text b)]
      (is (< (count s) 40012) "the bank is bounded")
      (is (str/ends-with? s "the-tail-end") "the newest output survives")
      (is (str/includes? s "dropped") "the trim is announced, never silent"))))


(deftest trimming-drops-whole-lines
  (let [b (tee/bank)]
    (dotimes [i 500]
      (tee/deposit! b (str "line-" (format "%03d" i) " "
                        (apply str (repeat 90 "=")) "\n")))
    (let [s (tee/text b)]
      (is (str/includes? s "dropped"))
      (is (re-find #"dropped\]\nline-\d" s)
        "the retained window begins at a line boundary, never mid-entry")
      (is (str/ends-with? s "\n")))))


(deftest lines-are-plain-composable-data
  (let [b (tee/bank)]
    (dotimes [i 10] (tee/deposit! b (str "l" i "\n")))
    (tee/deposit! b "pending")
    (let [ls (tee/lines b)]
      (is (vector? ls))
      (is (= ["l0" "l1" "l2" "l3" "l4" "l5" "l6" "l7" "l8" "l9" "pending"] ls)
        "terminators stripped, the unterminated line last")
      (is (= ["l9" "pending"] (take-last 2 ls)) "the tail is a take-last")
      (is (= ["l6" "l7"] (->> ls (drop-last 3) (take-last 2)))
        "paging back is composition, not an option"))
    (is (str/includes? (tee/text b) "l0")
      "a lines read consumed nothing — the full text is still whole")))


(deftest lines-announce-the-drop-at-the-head
  (let [b (tee/bank)]
    (tee/deposit! b (str (apply str (repeat 40000 "x")) "\n"))
    (tee/deposit! b "survivor\n")
    (let [ls (tee/lines b)]
      (is (str/includes? (first ls) "dropped")
        "chars the cap dropped are announced as the first element")
      (is (= "survivor" (peek ls)) "and the tail end never meets it"))))


(deftest text-is-the-whole-truth-under-the-cap
  (let [b (tee/bank)]
    (tee/deposit! b "hello ")
    (tee/deposit! b "world")
    (is (= "hello world" (tee/text b)))))


(deftest since-is-an-exact-cursor
  (let [b (tee/bank)]
    (tee/deposit! b "abc")
    (is (= ["abc" 3] (tee/since b 0)))
    (tee/deposit! b "def")
    (is (= ["def" 6] (tee/since b 3)))
    (is (= ["" 6] (tee/since b 6)) "a caught-up cursor reads nothing")
    (is (= ["f" 6] (tee/since b 5)))))


(deftest since-announces-a-cursor-the-cap-outran
  (let [b (tee/bank)]
    (tee/deposit! b (apply str (repeat 40000 "x")))
    (let [[s total] (tee/since b 0)]
      (is (= 40000 total))
      (is (str/includes? s "dropped")))))
