(ns co.multiply.remontoire.patterns.bounding
  "Deadlines. Two different waits are boundable, and they are not the
   same: the eval's `await` bounds how long the *response* waits before
   returning `running` — the work continues regardless, addressed by its
   id — where `__q/timeout` bounds the *work* itself. Reach for `await`
   when you only want your answer sooner; reach for `timeout` when work
   still running past the deadline should die."
  (:require [co.multiply.quiescent :as __q]))


(defn bounded
  "`task`, dead or done within `ms`: past the deadline the chain is
   cancel-cascaded — `__q/finally` cleanup still runs — and `fallback` is
   what the caller gets. The deadline speaks only when the task has said
   nothing: a task that settles first prevails whatever the duration,
   and a *failing* task throws its own exception promptly rather than
   hiding behind `fallback` until the deadline — absorbing failures too
   takes an explicit `__q/catch` composed on top."
  [task ms fallback]
  (__q/timeout task ms fallback))


(comment
  ;; The two bounds, side by side. This form's work dies at 10 s,
  ;; whatever the response did in the meantime:
  (bounded (__q/task (search-everything)) 10000 :gave-up)

  ;; Where this work runs for an hour untroubled — only the *response*
  ;; returned early as `running`; (__r/res <id>) collects it when done:
  (__q/task (search-everything)))
