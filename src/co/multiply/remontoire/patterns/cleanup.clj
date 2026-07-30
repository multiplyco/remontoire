(ns co.multiply.remontoire.patterns.cleanup
  "Stateful cleanup around async work: open, lend, release — however the
   work settles. `__q/finally` is the handler for it, because it is the
   only one that also runs on cancellation: an eval can be cancelled by
   id at any moment, and a resource opened inside one must not leak when
   it is."
  (:require [co.multiply.quiescent :as __q]))


(defn with-resource
  "The loan pattern — the async `with-open`: `acquire` a resource, lend
   it to `f` (resource → task), and `release` it however `f`'s task
   settles: success, failure, or cancellation. The resource is bound
   outside the chain, so release does not depend on how far `f` got;
   callers see `f`'s own result untouched, because `finally` never
   transforms. `release` is called synchronously — if release is itself
   async, wrap its *creation* in `__q/compel`, since the chain is already
   settling and will not wait for what a handler returns."
  [acquire release f]
  (let [resource (acquire)]
    (-> (f resource)
      (__q/finally (fn [_value _error _cancelled?] (release resource))))))


(comment
  ;; In an eval: stream a file and be certain it closes, even if this
  ;; eval is cancelled mid-read from anywhere: (__q/cancel (__r/res 12)).
  (with-resource
    #(clojure.java.io/input-stream "data.bin")
    #(java.io.InputStream/.close %)
    (fn [in] (__q/task (count-records in)))))
