(ns co.multiply.remontoire.patterns.integrations
  "Other async currencies, spent here directly. A `CompletableFuture` —
   and anything else Quiescent recognizes: `Future`, another task —
   returned from eval code is grounded like a task: the eval awaits it,
   and its value is what the entry captures, with no adapter code.
   core.async is the one explicit conversion, and only available when
   the host system carries core.async."
  (:import [java.util.concurrent CompletableFuture]))


(defn from-cf
  "Nothing to do: a CompletableFuture needs no conversion — return it,
   and the task border grounds it. This exists as callable proof: it
   settles a CF on another thread and returns it bare; evaluate
   `(from-cf 42)` and what comes back is 42, not the future."
  [v]
  (CompletableFuture/supplyAsync (fn [] v)))


(comment
  ;; An SDK's async call, awaited by the entry with no scaffolding —
  ;; a CompletableFuture is simply returned:
  (S3AsyncClient/.getObject client request transformer)

  ;; core.async, when the host uses it: the adapter ships inside
  ;; Quiescent but converts explicitly, channel → task — the entry's
  ;; lifetime then covers the go block:
  (require '[co.multiply.quiescent.adapter.core-async])
  (q/as-task (fetch-through-channels!)))
