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


(deftest tail-is-a-window-not-a-consumption
  (let [b (tee/bank)]
    (dotimes [i 10] (tee/deposit! b (str "l" i "\n")))
    (tee/deposit! b "pending")
    (let [t (tee/tail b 3)]
      (is (str/includes? t "l9"))
      (is (str/includes? t "pending")
        "the unterminated line is the newest entry")
      (is (not (str/includes? t "l5")))
      (is (str/includes? t "more lines")
        "what lies behind the window is announced"))
    (is (str/includes? (tee/text b) "l0")
      "a tail read consumed nothing — the full text still starts at the top")))


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
