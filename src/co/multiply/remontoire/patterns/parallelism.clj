(ns co.multiply.remontoire.patterns.parallelism
  "Fanning out inside a system that is also doing its day job. Tasks in
   data structures already run in parallel — `(q/task {:a (f) :b (g)})`
   grounds both at once — and `qfor` maps in parallel when its body
   spawns tasks. The recipe worth naming is the brake: a `gate` bounds
   how many run at once, because an unbounded fan-out over production
   data competes with production."
  (:require [co.multiply.quiescent :as q :refer [qfor]]))


(defn gated
  "Run `f` (element → task) over `coll` with at most `n` in flight:
   `(gated 8 expensive-check ids)` is the polite fan-out. Settles to
   the results in collection order. The gate participates in structured
   concurrency — cancelling the eval cancels the enqueued work with it,
   nothing keeps running behind the brake."
  [n f coll]
  (let [g (q/gate n)]
    (qfor [x coll]
      (q/gate-task g (f x)))))


(comment
  ;; Eight at a time across ten thousand ids, cancellable as one unit
  ;; by this eval's id:
  (gated 8 #(q/task (expensive-check %)) all-ids))
