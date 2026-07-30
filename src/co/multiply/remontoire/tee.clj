(ns co.multiply.remontoire.tee
  "Output capture: the root `*out*` and `*err*` tees and the per-eval banks
   they fill.

   A tee sits at the one point every unbound deref of its var arrives at —
   the var's root — and discriminates per write, not per install: every
   write passes through to the original writer unchanged, and writes whose
   thread carries a bank in scope are additionally copied into that bank.
   It never diverts, never withholds, never attributes by wall-clock
   coincidence (§11). Host threads that bound `*out*` or `*err*` themselves
   never reach the root and never see the tee.

   Both streams fill the *same* bank, interleaved in arrival order and
   unmarked — chronology beats channel separation, as in a terminal — so
   what `*err*` carries reaches the reader at last: warnings (a refer
   rejected in favor of an interned def says so only here — measured
   2026-08-05, invisible then), reflection notes, stack traces from
   stray threads, anything host code mutters."
  (:require
    [co.multiply.scoped :as sc])
  (:import [java.io Writer]))


(def ^:dynamic *bank*
  "The attribution key. Rides two rails (§11): `scoping` around eval-chain
   construction, which Quiescent re-establishes around every descendant task
   body — and a plain per-link `binding`, which Clojure conveyance carries
   into `future`/`agent`/`bound-fn`. One `sc/ask` covers both, because its
   fallback is a var deref and var derefs read thread bindings (measured
   2026-08-04). Never bound outside those two sites."
  nil)


(def ^:private bank-cap
  "Characters retained per bank — the tail, because the newest output is
   what a reader acts on; the drop is announced by `text`, never silent. A
   cap, because an accidental print loop must not grow the registry without
   bound (§5)."
  32768)


(defn bank
  "A fresh bank: the tail of what one eval printed, plus how much has ever
   arrived, so trimming stays announceable."
  []
  (atom {:s "" :total 0}))


(defn deposit!
  "Append `s` to `bank`, trimming to the cap from the front."
  [bank ^String s]
  (swap! bank
    (fn [{:keys [s total] :as state} added]
      (let [s' (str s added)
            n  (count s')]
        {:s     (if (> n bank-cap) (subs s' (- n bank-cap)) s')
         :total (+ total (count added))}))
    s)
  nil)


(defn text
  "Everything `bank` holds, with the drop announced when the cap trimmed —
   a tail that silently posed as the whole output would be §5's nasty
   failure mode wearing a different hat."
  [bank]
  (let [{:keys [s total]} @bank]
    (if (> total (count s))
      (str "…[" (- total (count s)) " chars dropped]\n" s)
      s)))


(defn since
  "What arrived after position `from` (a cumulative-character count), as
   `[text total]` — the caller holds `total` as its next cursor. This is
   the heartbeat's read (§4): each beat drains what the eval said since the
   last one. A `from` the cap already trimmed past gets the drop announced,
   same as `text`."
  [bank from]
  (let [{:keys [s total]} @bank
        start (- total (count s))]
    [(cond
       (>= from total) ""
       (>= from start) (subs s (- from start))
       :else (str "…[" (- start from) " chars dropped]\n" s))
     total]))


(defn- relay
  "One write: into the scoped bank when this thread carries one, and through
   to the original writer either way."
  [^Writer original ^String s]
  (when-let [b (sc/ask *bank* nil)]
    (deposit! b s))
  (.write original s))


(defn- tee-writer
  "A Writer wrapping `original`. Clojure's proxy routes every `write`
   overload to one fn by arity, so the 1-arity form may see an int (a char
   code), a char[], or a String, and the 3-arity form a char[] or String
   with bounds — all normalized to a String before relaying."
  ^Writer [^Writer original]
  (proxy [Writer] []
    (write
      ([x]
       (relay original
         (cond
           (string? x) x
           (integer? x) (str (char (int x)))
           :else (String. ^chars x))))
      ([x off len]
       (relay original
         (if (string? x)
           (subs x off (+ off len))
           (String. ^chars x (int off) (int len))))))

    (flush [] (.flush ^Writer original))
    ;; The root writer's lifetime is the JVM's; closing the tee must not
    ;; close what it wraps.
    (close [] (.flush ^Writer original))))


;; Holds the original root writers, `{:out w :err w}`, while the tees are
;; installed — the defonce is what makes install survive reloads without
;; stacking tees, and what `uninstall!` restores from.
(defonce ^:private installed (atom nil))


(defn install!
  "Swap the tees in at the roots of `*out*` and `*err*`. Idempotent; called
   by `init!`, so a host that never inits never has its roots touched.
   `getRawRoot`, not the deref: install runs on a caller's thread, where
   either var may well be thread-bound to something that is not the root
   at all."
  []
  (locking installed
    (when-not @installed
      (let [out (.getRawRoot #'*out*)
            err (.getRawRoot #'*err*)]
        (alter-var-root #'*out* (constantly (tee-writer out)))
        (alter-var-root #'*err* (constantly (tee-writer err)))
        (reset! installed {:out out :err err})))
    nil))


(defn uninstall!
  "Restore the original root writers. Safe to call when not installed."
  []
  (locking installed
    (when-let [{:keys [out err]} @installed]
      (alter-var-root #'*out* (constantly out))
      (alter-var-root #'*err* (constantly err))
      (reset! installed nil))))
