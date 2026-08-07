(ns co.multiply.remontoire.evaluate
  "Evaluation: which namespace, and the eval itself.

   The namespace a caller was last in is conversational state, so it lives
   with the caller's session (§11) rather than here."
  (:require [co.multiply.quiescent :as q]
    [co.multiply.remontoire.api]
    [co.multiply.remontoire.exception :as exception]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.scoped :as sc])
  (:import [clojure.lang LineNumberingPushbackReader]
    [java.io StringReader]))


(defn- ensure-scratch!
  "The session's own scratch namespace (`session/scratch-ns-sym`) — where a
   cold caller starts: a namespace of one's own, so that a stray `def` lands
   somewhere disposable rather than in `clojure.core`, which is where an
   unbound `*ns*` otherwise resolves to at runtime. Per session, so that
   concurrent tenants' defs and requires cannot collide. Idempotent, and
   re-applied in full on every call: `create-ns` returns the existing
   namespace, and re-referring keeps a scratch created by an older
   remontoire current with the refers a newer one expects. A name the caller
   has interned themselves is never replaced — their def wins, and Clojure
   notes the rejection on *err*."
  [session]
  (binding [*ns* (create-ns (session/scratch-ns-sym session))]
    ;; `create-ns` alone gives a namespace in which `+` does not resolve.
    (refer-clojure)
    ;; The composition surface is clojure.core *and* Quiescent (§5): the
    ;; `q`-prefixed macros referred bare — correct here precisely because
    ;; scratch is a namespace the session owns — and the library under
    ;; `__q`, the same spelling every visited namespace guarantees, so
    ;; nothing diverges when an eval moves. `q` itself is left free:
    ;; reserved for user code, the model's to establish. And `res` is how
    ;; an id in one response becomes a value in the next expression — so
    ;; scratch resolves them all from the start.
    (require '[co.multiply.quiescent :as __q
               :refer [q qdo qjoin qfor qlet qmerge if-qlet when-qlet]]
      '[co.multiply.remontoire.registry :refer [res evals]]
      '[co.multiply.remontoire.api :as __r])
    *ns*))


(defn- ensure-aliases!
  "Make `__r/…` and `__q/…` resolve in `ns` — the API layer and Quiescent,
   the tooling rail, reachable wherever an eval navigates (§11) by
   construction rather than instruction: `res` hands back tasks precisely
   so that `catch`/`cancel`/`then` are the access idioms, so the rail is
   incomplete without Quiescent's verbs. `__q` and not `q`, deliberately:
   `q` is reserved for user code — host namespaces own their meaning of
   it, and the fill-when-free `q` this replaces (2026-08-05) made that
   meaning *conditional*, unknowable from where the model stands — where
   `__q`, like `__r`, is self-owned tooling: obscure by design, guaranteed
   everywhere, colliding with nothing. Both are reasserted when they point
   anywhere else — a `__r`/`__q` bound otherwise is a stale manual
   experiment, not a claim the invariant should yield to (observed
   2026-08-04: a hand-aliased `__r` in the REPL's own ns shadowed the API
   with a confusing `No such var`)."
  [ns]
  (let [assert! (fn [alias target]
                  (when-not (identical? target (get (ns-aliases ns) alias))
                    (.removeAlias ^clojure.lang.Namespace ns alias)
                    (.addAlias ^clojure.lang.Namespace ns alias target)))]
    (assert! '__r (the-ns 'co.multiply.remontoire.api))
    (assert! '__q (the-ns 'co.multiply.quiescent))))


(defn- resolve-ns
  "An explicit namespace wins; otherwise continue where the session's last
   call ended, which on a cold start — or a fresh session, since the sticky
   namespace is conversational state (§11) — is scratch.

   A named namespace must already exist. Creating one on a typo would answer
   every subsequent expression with `Unable to resolve symbol` and leave the
   blame on the expression."
  [requested session]
  ;; Unconditionally, so that naming scratch explicitly works on a cold start
  ;; the same as falling back to it does.
  (ensure-scratch! session)
  (if (seq requested)
    (let [sym (symbol requested)]
      (or (find-ns sym)
        (throw (ex-info (str "No such namespace: " sym)
                 {:ns   sym
                  :hint "require it first, or evaluate an (ns …) form from scratch"}))))
    (or (some-> (session/current-ns session) find-ns) (ensure-scratch! session))))


(def ^:private eof (Object.))


(defn- evaluate-forms
  "Read and evaluate one form at a time, each form's value **grounded** before
   the next is read — an eval is an implicit `qdo` (§9). A top-level form that
   returns a task therefore *runs* rather than drops: the value passes a task
   border on the way to the next form, and the next form sees the world the
   completed task left behind.

   Not `(do …)` around the whole string: that wrapper is swallowed by a
   trailing line comment, and reading everything up front resolves `::keywords`
   and reader aliases in *later* forms against the namespace the caller started
   in, even when an earlier form moved somewhere else. So each link reads its
   own form, under its own `*ns*`.

   Each link runs on its own virtual thread, which is why `*ns*` and `*file*`
   are re-established per link with the namespace threaded through as data —
   `binding` does not cross a spawn. Settles to the last form's grounded
   value; `details` gets `{:ns :forms}` at the end, `baseline` always holds
   the *running* link's arrival stack, so a failure in the third form
   subtracts the path that led to it, not to the first (§4)."
  [code ns-str details baseline bank session]
  (letfn [(step
            [ns-sym rdr value n]
            ;; On this link's thread, before read or eval — read can throw too.
            (reset! baseline (exception/here))
            ;; `*file*` names what a compile error blames. Left alone it is
            ;; whatever the thread inherited: a file the caller never heard of.
            ;; The bank and session are thread-bound here — the conveyance
            ;; rail: a `future` launched by this form carries the frame, and
            ;; `sc/ask` falls back to it (§11). The scope rail is wrapped
            ;; around the whole construction below.
            (binding [*ns*       (the-ns ns-sym), *file* "remontoire-eval",
                      tee/*bank* bank, session/*session* session]
              (ensure-aliases! *ns*)
              ;; LineNumbering, not a plain PushbackReader: it attaches :line
              ;; and :column, so a compile error reports a position inside the
              ;; submitted code instead of `Syntax error compiling at (0:0)`.
              (let [form (read {:eof eof} rdr)]
                (if (identical? eof form)
                  (do
                    ;; Track where the code *ended*, so `(in-ns …)` and
                    ;; `(ns …)` stick the way they do in a REPL — per
                    ;; session, like any conversational state (§11).
                    (session/current-ns! session ns-sym)
                    (deliver details {:ns ns-sym :forms n})
                    value)
                  (let [v   (eval form)
                        ns' (ns-name *ns*)]
                    ;; The border is the point: `v` grounds here — `then`
                    ;; takes a plain value and a task alike. Not `qlet`,
                    ;; tempting as that shape is: qlet evaluates its binding
                    ;; forms on threads of their own, outside this `binding`
                    ;; frame — `(eval form)` there sees `*ns*` = clojure.core
                    ;; (measured 2026-08-02). Eval and the ns capture must
                    ;; both happen right here, inside the frame, and in this
                    ;; order: eval moves `*ns*` when the form is `(in-ns …)`,
                    ;; and the next link must see where it landed. Only data
                    ;; crosses to the next link.
                    (q/then v (fn [v'] (step ns' rdr v' (inc n)))))))))]
    ;; Compelled at construction: cancellation is thereby *id-directed*.
    ;; Cancelling an entry that grounds a chain onto another entry propagates
    ;; upstream through the chain — and stops at this moat, so tearing down
    ;; e1 requires cancelling e1. Direct cancellation of this entry still
    ;; cascades down its own links, which is what keeps a wedged eval
    ;; killable (§9).
    ;;
    ;; The scope rail: Quiescent captures scope at task construction and
    ;; re-establishes it around every body, so binding the bank and session
    ;; around the construction reaches every link and every task the eval'd
    ;; code spawns, through compel moats (measured 2026-08-04, §11).
    (sc/scoping [tee/*bank* bank, session/*session* session]
      (q/compel
        (q/task
          (reset! baseline (exception/here))
          ;; Resolved inside the chain, so a bad namespace fails the task
          ;; like any other failure — profiled against a same-thread
          ;; baseline.
          (step (ns-name (resolve-ns ns-str session))
            (LineNumberingPushbackReader. (StringReader. code))
            nil 0))))))


(defn evaluate
  "Evaluate `code` in `ns-str`, or wherever `session`'s last call ended.

   Returns `{:task … :details … :baseline … :bank …}`. The task settles to the last
   form's grounded value — `@(res 12)` must be the thing itself (§5) — so the
   namespace and form count travel out of band, delivered when the chain
   completes. Rooted by construction: the calling thread is not a task, so
   the chain's seed has no ambient parent to be cancelled by (§9)."
  [code ns-str session]
  (let [details  (promise)
        baseline (atom nil)
        bank     (tee/bank)]
    {:task     (evaluate-forms code ns-str details baseline bank session)
     :details  details
     :baseline baseline
     :bank     bank}))


(defn current-ns
  "Where `session`'s next un-namespaced call would land. For reporting
   alongside an error, when `evaluate` threw before it could tell anyone."
  [session]
  (or (session/current-ns session) (ns-name (ensure-scratch! session))))
