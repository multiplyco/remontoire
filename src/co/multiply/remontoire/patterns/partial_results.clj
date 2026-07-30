(ns co.multiply.remontoire.patterns.partial-results
  "Seeing into work that has not settled. Two channels exist, and they
   compose: printed output streams — a running eval's latest printed
   line is its live progress message, and everything printed so far is
   readable mid-run with `(__r/out id)` — and shared state waits, an
   atom the work fills and a later eval reads at its own rhythm."
  (:require [co.multiply.quiescent :as __q]))


(defn reporting
  "Wrap step-work `f` (element → value) so each element announces itself
   before it is worked: the announcement is the eval's progress line
   while it runs, and accumulates under `(__r/out id)`. Printing is
   attributed across the eval's whole task tree, so the wrapped `f`
   reports correctly from inside `qfor` and `qlet` bodies too."
  [f label]
  (fn [x]
    (println label x)
    (f x)))


(comment
  ;; Channel one, print: progress rides the output rails for free.
  (qfor [n (range 1000)]
    (__q/task ((reporting process! "item") n)))

  ;; Channel two, shared state. One eval launches work that fills an
  ;; atom it defined in scratch …
  (def progress (atom []))
  (__q/task (doseq [n (range 1000)]
              (swap! progress conj (process! n))))
  ;; … and a later eval — same session or another — reads at will,
  ;; while the first eval's id keeps the work itself addressable:
  (count @progress))
