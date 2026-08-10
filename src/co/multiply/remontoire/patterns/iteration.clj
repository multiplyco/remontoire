(ns co.multiply.remontoire.patterns.iteration
  "Repeating async work has two honest shapes, and one trap between
   them. Parallel: `qfor`, one task per element. Sequential: plain
   imperative code *inside a single task* — these are virtual threads,
   so blocking is cheap and `Thread/sleep` in a loop is simply correct.
   The trap is mixing the two: `dotimes`/`doseq`/`run!` spawning tasks
   makes orphans — children never returned, cascade-cancelled the moment
   the form settles, silently. `q/sleep` itself is fine *composed* —
   `(qdo (q/sleep 1000) (fire!))` is delay-then-do — the trap is
   discarding what it returns. If you reached for `dotimes` around
   `q/task` or `q/sleep`, reach for one of the shapes below instead."
  (:require [co.multiply.quiescent :as q :refer [qfor]]))


(defn sequentially
  "Run `f` over `coll` one element at a time, inside one task: ordinary
   imperative iteration, blocking allowed — a virtual thread makes
   waiting cheap — settling to the vector of results in order. This is
   the shape `dotimes`-spawning-tasks wanted to be: the iteration lives
   *inside* the task, so nothing is orphaned and the eval's id addresses
   the whole run."
  [f coll]
  (q/task (mapv f coll)))


(comment
  ;; Sequential ticks — plain blocking code inside one task:
  (sequentially (fn [n] (Thread/sleep 1000) (probe! n)) (range 10))

  ;; A composed sleep — delay-then-do, returned so it runs:
  (qdo (q/sleep 1000) (fire!))

  ;; The same, parallel — one task per element, `qfor` returns them all:
  (qfor [n (range 10)]
    (q/task (Thread/sleep 1000) (probe! n)))

  ;; The trap, for recognition — returns immediately, probes never fire,
  ;; the sleeps are orphaned children cancelled as the form settles:
  (dotimes [n 10] (q/task (Thread/sleep 1000) (probe! n))))
