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
    [clojure.string :as str]
    [co.multiply.scoped :as sc])
  (:import
    [clojure.lang PersistentQueue Var]
    [java.io Writer]))


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
  "A fresh bank: the tail of what one eval printed — completed lines in
   arrival order, each stored with its newline, plus the unterminated
   `:partial` — and how much has ever arrived, so trimming stays
   announceable. Lines, because whole entries are the unit a reader
   wants retained or dropped, and windowing (`tail`) falls out of the
   structure instead of out of string surgery."
  []
  (atom {:lines PersistentQueue/EMPTY :chars 0 :partial "" :total 0}))


(defn deposit!
  "Append `s` to `bank`. Writes assemble into newline-terminated lines —
   the unit of retention — and the cap trims whole lines from the front,
   oldest first. String surgery survives at one edge only: an entry that
   alone outgrows the cap is cut from its own front rather than dropped
   whole — the newest output is what a reader acts on (§5), and
   tail-keeping outranks entry purity when the two collide."
  [bank ^String s]
  (swap! bank
    (fn [{:keys [lines chars partial total]} ^String added]
      (let [s      (str partial added)
            pieces (str/split s #"(?<=\n)")
            whole? (str/ends-with? s "\n")
            done   (if whole? pieces (pop pieces))
            part   (if whole? "" (peek pieces))
            part'  (let [n (count part)]
                     (if (> n bank-cap) (subs part (- n bank-cap)) part))
            room   (- bank-cap (count part'))
            [q c] (loop [q (into lines done)
                         c (transduce (map count) + chars done)]
                    (cond
                      (<= c room) [q c]
                      (next (seq q)) (recur (pop q) (- c (count (peek q))))
                      (and (seq q) (pos? room))
                      (let [^String l (peek q)]
                        [(conj PersistentQueue/EMPTY (subs l (- (count l) room)))
                         room])
                      :else [PersistentQueue/EMPTY 0]))]
        {:lines q :chars c :partial part' :total (+ total (count added))}))
    s)
  nil)


(defn- retained
  "The bank's whole retained text, verbatim — lines then partial."
  ^String [{:keys [lines partial]}]
  (let [sb (StringBuilder.)]
    (doseq [^String l lines] (StringBuilder/.append sb l))
    (-> sb (StringBuilder/.append ^String partial) StringBuilder/.toString)))


(defn text
  "Everything `bank` holds, with the drop announced when the cap trimmed —
   a tail that silently posed as the whole output would be §5's nasty
   failure mode wearing a different hat."
  [bank]
  (let [{:keys [total] :as state} @bank
        s (retained state)]
    (if (> total (count s))
      (str "…[" (- total (count s)) " chars dropped]\n" s)
      s)))


(defn lines
  "What `bank` retains, as a vector of lines — terminators stripped, the
   pending unterminated line last, and chars the cap already dropped
   announced as the first element, which is positionally honest: the
   drop happened at the head end, and composition reaching for the tail
   never meets it. Plain data, made for composing — `take-last` peeks,
   `drop-last` then `take-last` pages back, `filter` searches — where
   `text` is the verbatim reading form the responses carry. A view, not
   a consumption: the bank keeps what it keeps however it is read."
  [bank]
  (let [{:keys [total] :as state} @bank
        dropped (- total (count (retained state)))]
    (cond-> []
      (pos? dropped) (conj (str "…[" dropped " chars dropped]"))
      true (into (map #(str/replace % #"\r?\n\z" "")) (:lines state))
      (seq (:partial state)) (conj (:partial state)))))


(defn since
  "What arrived after position `from` (a cumulative-character count), as
   `[text total]` — the caller holds `total` as its next cursor. This is
   the heartbeat's read (§4): each beat drains what the eval said since the
   last one. A `from` the cap already trimmed past gets the drop announced,
   same as `text`."
  [bank from]
  (let [{:keys [total] :as state} @bank
        s     (retained state)
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
  (Writer/.write original s))


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

    (flush [] (Writer/.flush original))
    ;; The root writer's lifetime is the JVM's; closing the tee must not
    ;; close what it wraps.
    (close [] (Writer/.flush original))))


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
      (let [out (Var/.getRawRoot #'*out*)
            err (Var/.getRawRoot #'*err*)]
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
