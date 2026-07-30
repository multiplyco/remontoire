(ns co.multiply.remontoire.patterns.outliving
  "Work that must survive the eval that started it. An eval grounds each
   top-level form, and tasks spawned but not returned are children —
   cancelled, silently, when their form settles. Two honest ways out:
   prefer letting the eval itself *be* the long-running work, addressed
   by its id; reach for `__q/compel` when the work genuinely must detach."
  (:require [co.multiply.quiescent :as __q]))


(defmacro detached
  "`__q/compel` around inline construction — the shape that actually
   detaches. Compel protects what is *created inside it*: handed a task
   you already hold, it shields only the wrapper, and the cascade that
   was already coming still reaches the work. Wrapping the construction
   is the whole trick, so this is a macro — it makes the inline-ness
   syntactic, impossible to get wrong at the call site."
  [& body]
  `(__q/compel (__q/task ~@body)))


(comment
  ;; First choice — no detachment at all: the eval IS the work. Past
  ;; its await the response returns `running`, the work continues
  ;; untroubled, and the id is the handle — (__r/res 12) collects,
  ;; (__q/cancel (__r/res 12)) stops.
  (rebuild-the-index!)

  ;; When something must survive even this eval's cancellation — a
  ;; flush, an audit write — detach it:
  (detached (flush-audit-log!)))
