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
    (let [{:keys [s total]} @b]
      (is (= 40012 total))
      (is (< (count s) total) "the bank is bounded")
      (is (str/ends-with? s "the-tail-end") "the newest output survives"))
    (is (str/includes? (tee/text b) "dropped") "the trim is announced, never silent")))


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
