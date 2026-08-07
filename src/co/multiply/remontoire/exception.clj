(ns co.multiply.remontoire.exception
  "Throwables as data, in two halves.

   `trace` is the navigable half: a sequential, complete run of **sites** —
   §3's second element type — so it reduces, filters and maps like any other
   result in the library, and its elements feed `nav/form`:

       (->> (exc/trace e) (filter :clj?) (map :sym) (map nav/form))

   `profile` is the ocular half: what went wrong, pruned for reading, with a
   trace attached. `render` turns a profile into a string. They are decoupled;
   a profile is data, so the registry can store it and answer questions about
   it later, which a string could not."
  (:require [clojure.repl :as repl]
    [clojure.string :as str]))


(def ^:private scaffolding
  "Sites that are how evaluation happens rather than what happened: the
   compiler's own, and remontoire's entry into it. Matched against the
   demunged symbol, and trimmed only from the bottom, so a caller debugging
   remontoire *through* remontoire keeps their own frames."
  ["clojure.lang.Compiler"
   "clojure.core/eval"
   "co.multiply.remontoire.evaluate"])


(def ^:private fn-methods
  "Methods the compiler emits for a function body. Their presence is what
   distinguishes a Clojure frame from a Java one."
  #{"invoke" "invokeStatic" "doInvoke" "invokePrim"})


(defn- class-name
  [elem]
  (StackTraceElement/.getClassName elem))


(defn- method-name
  [elem]
  (StackTraceElement/.getMethodName elem))


(defn- scaffolding?
  [site]
  (boolean (some #(str/starts-with? (str (:sym site)) %) scaffolding)))


(defn frame
  "One stack frame as a site. Clojure frames get a demunged `ns/name` symbol,
   which by §3's invariant is a valid input to `nav/form` — so the source of a
   frame becomes a lookup rather than a feature. Java frames get `Class/method`,
   still a symbol a reader would produce, just one nothing will resolve."
  [elem]
  (let [file (StackTraceElement/.getFileName elem)
        row  (StackTraceElement/.getLineNumber elem)
        clj? (contains? fn-methods (method-name elem))]
    (cond-> {:sym (if clj?
                    ;; Anonymous fns munge to `fn__6595`; the counter is noise.
                    (symbol (str/replace (repl/demunge (class-name elem)) #"--\d+$" ""))
                    (symbol (class-name elem) (method-name elem)))}
      file (assoc :file file)
      ;; `long`, because `getLineNumber` returns an int and §3 asks for the
      ;; value a Clojure reader would produce.
      (pos? row) (assoc :row (long row))
      clj? (assoc :clj? true))))


(defn- collapse
  "Clojure emits two frames per call — `invoke` trampolining into
   `invokeStatic` — so a raw trace reads at twice its true depth. Keep the
   innermost of each consecutive run.

   This is why `trace` is complete in *calls* rather than complete in frames:
   the trampoline is a JVM emission detail, not something anybody called.

   A run longer than that trampoline is real recursion, whose depth is the
   whole story when the throwable is a StackOverflowError, so those keep a
   count rather than being flattened silently."
  [frames]
  (into []
    (comp (partition-by :sym)
      (map (fn [run]
             (cond-> (first run)
               (> (count run) 2) (assoc :repeats (count run))))))
    frames))


(defn- sites
  [stack]
  (collapse (mapv frame stack)))


(defn here
  "The current stack as sites. Hand this to `profile` as the baseline and the
   path the call arrived by is subtracted from what it reports."
  []
  (sites (Thread/.getStackTrace (Thread/currentThread))))


(defn- shared-tail
  "How many trailing sites two sequences have in common.

   Compared on `:sym` alone, not on the whole site: the frame a `catch` sits in
   reports a different `:row` on each side, and a symbol already carries both
   class and method."
  [a b]
  (count (take-while (fn [[x y]] (= (:sym x) (:sym y)))
           (map vector (reverse a) (reverse b)))))


(defn causes
  "The chain, outermost first and root last. Throwables rather than data, so
   that `trace` and `profile` apply to each in turn.

   Capped, because a self-referencing cause would otherwise hang the thing
   meant to explain the hang."
  [e]
  (loop [acc [] e e]
    (if (and e (< (count acc) 20))
      (recur (conj acc e) (Throwable/.getCause e))
      acc)))


(defn- type-sym
  [e]
  (symbol (.getName (class e))))


(defn trace
  "Every distinct call involved, deepest first: the root cause's sites, then
   whatever each wrapper adds that is not already accounted for.

   Complete — nothing is pruned. Pruning is `profile`'s job, because pruning
   needs to know where the call came *from* and a trace does not.

   Subtracting rather than concatenating, because a wrapper's trace is normally
   an exact suffix of its cause's — catching and rethrowing on one thread adds
   no frames, so `mapcat` over the chain would report everything twice. Across
   threads the two are disjoint and both matter: the failure inside the task,
   then the thread that was awaiting it. Sites contributed by anything but the
   root carry `:from`, naming what rethrew them."
  [e]
  (reduce (fn [acc link]
            (let [s     (sites (Throwable/.getStackTrace link))
                  novel (subvec s 0 (- (count s) (shared-tail s acc)))]
              (into acc
                (if (seq acc)
                  (mapv #(assoc % :from (type-sym link)) novel)
                  novel))))
    []
    (reverse (causes e))))


(defn- described
  [e]
  (cond-> {:type (type-sym e)
           :msg  (ex-message e)}
    (ex-data e) (assoc :data (ex-data e))))


(defn profile
  "What went wrong, for reading: the **root cause** at the top level, because
   that is what actually failed, with wrappers under `:via` because those are
   context.

   Clojure wraps aggressively — a `CompilerException` around the real failure
   is the common case — so a format reporting only the outermost message
   describes the wrapper instead of the problem. `:via` is absent when nothing
   wraps anything.

   Pass `baseline` — see `here` — and the arrival path is subtracted, along
   with the compiler scaffolding beneath it. `:dropped` reports how much, since
   a pruned trace and a genuinely short one look identical otherwise, and the
   difference matters when the answer is \"the interesting frame was in the part
   you hid\"."
  ([e] (profile e nil))
  ([e baseline]
   (let [all   (trace e)
         above (subvec all 0 (- (count all) (if baseline (shared-tail all baseline) 0)))
         kept  (->> above reverse (drop-while scaffolding?) reverse vec)
         chain (causes e)]
     (cond-> (assoc (described (peek chain)) :trace kept)
       (< (count kept) (count all)) (assoc :dropped (- (count all) (count kept)))
       (next chain) (assoc :via (mapv (fn [wrapper]
                                        (let [top (first (Throwable/.getStackTrace wrapper))]
                                          (cond-> (described wrapper)
                                            top (assoc :at (frame top)))))
                                  (butlast chain)))))))


(defn- source-name
  "Code that never came from a file. Clojure calls that `NO_SOURCE_FILE`; nREPL
   invents a `form-init<digits>.clj` per session. Both are the same absence, and
   the session-specific one is worse than useless — it looks like a filename."
  [file]
  (if (or (nil? file)
        (= "NO_SOURCE_FILE" file)
        (re-matches #"form-init\d+\.clj" file))
    "(evaluated)"
    file))


(defn- site-lines
  "Sites grouped by file, with a break wherever `:from` changes — that break is
   a thread boundary, and running the two sides together would read as one
   call chain when it is two."
  [trace]
  (:lines
    (reduce (fn [{:keys [lines from]} group]
              (let [group-from (:from (first group))]
                {:from  group-from
                 :lines (into lines
                          (concat
                            (when (not= group-from from)
                              [(str "  ↑ rethrown as " group-from)])
                            [(str "  " (source-name (:file (first group))))]
                            (for [{:keys [sym row repeats]} group]
                              (str "    " sym
                                (when row (str "  " row))
                                (when repeats (str "  ×" repeats))))))}))
      {:lines [] :from nil}
      (partition-by (juxt :from :file) trace))))


(defn render
  "A profile as a string, for ocular inspection. Grouping happens here, not in
   the data: §3 keeps results sequential so they compose, and puts rendering at
   exactly one boundary per pipeline."
  [{:keys [type msg data via trace dropped]}]
  (str/join
    "\n"
    (concat
      [(str type (when msg (str ": " msg)))]
      (when data
        ;; Bounded, because `ex-data` is whatever the thrower put there and an
        ;; unbounded `pr-str` of a lazy infinite seq never returns. Clojure
        ;; prints `...` at each cut, so the truncation announces itself.
        [(str "  data " (binding [*print-length* 32
                                  *print-level*  8]
                          (pr-str data)))])
      (for [{w-type :type w-msg :msg w-at :at} via]
        (str "  wrapped by " w-type (when w-msg (str ": " w-msg))
          (when-let [sym (:sym w-at)] (str " — " sym))))
      (site-lines trace)
      (when (and dropped (pos? dropped))
        [(str "  … " dropped " frame" (when (> dropped 1) "s") " dropped")]))))


(defn- prone-to-throwing
  []
  (letfn [(crash
            []
            (/ 1 0))]
    (crash)))


(comment

  ;; The navigable half: sequential, complete, filterable.
  (->> (trace (try (prone-to-throwing) (catch Throwable e e)))
    (filter :clj?)
    (map :sym))

  ;; The ocular half. No baseline: the complete trace, down through nrepl and
  ;; clojure.main.
  (println (render (profile (try (prone-to-throwing) (catch Throwable e e)))))

  ;; With one: everything shared with the calling stack is subtracted.
  (let [baseline (here)]
    (println (render (profile (try (prone-to-throwing) (catch Throwable e e)) baseline))))

  ;; Across threads the chain contributes two disjoint paths, not one repeated.
  (let [baseline (here)]
    (println (render (profile (try @(future (prone-to-throwing)) (catch Throwable e e)) baseline))))

  :-)
