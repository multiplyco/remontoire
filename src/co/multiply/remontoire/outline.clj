(ns co.multiply.remontoire.outline
  "A namespace at a glance, from the running system's own metadata: every
   var with its signature, source order preserved, privates included and
   marked — the gist of a namespace, not just its API (§7). Runtime over
   parsing, deliberately: everything the glance needs — arglists, doc,
   the macro and private flags, line — already sits on the vars, for any
   loaded namespace with no file plumbing, and reflects what is live after
   a hot reload rather than what is on disk. Source-level outlining
   (unloaded files, comment blocks, the shape of a file as written) is
   `form`/`changed-forms` territory and stays there."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [clojure.lang MultiFn Namespace Var]
    [java.util.regex Pattern]))


(defn- designated
  "The namespace `x` designates: a namespace itself, a symbol naming one,
   an alias visible from the calling namespace (`*ns*` is bound per link,
   §11, so aliases resolve wherever the eval has navigated), or a string
   of either. Throws a plain explanation otherwise — which namespaces are
   loaded is exactly what the caller may not know yet."
  [x]
  (cond
    (instance? Namespace x) x
    (string? x) (designated (symbol x))
    (symbol? x) (or (get (ns-aliases *ns*) x)
                  (find-ns x)
                  (throw (ex-info (str "No loaded namespace or alias " x
                                    " visible from " (ns-name *ns*))
                           {:designator x})))
    :else (throw (ex-info (str "Cannot designate a namespace with " (pr-str x))
                   {:designator x}))))


(defn- matcher
  "A predicate over strings from `match`: a regex as given, or a string as
   a case-insensitive substring — models write \"evict\" as readily as
   #\"evict\", and both should work."
  [match]
  (cond
    (nil? match) nil
    (instance? Pattern match) #(re-find match %)
    (string? match) (let [p (re-pattern (str "(?i)" (Pattern/quote match)))]
                      #(re-find p %))
    :else (throw (ex-info (str ":match takes a string or a regex, not " (pr-str match))
                   {:match match}))))


(defn- dispatches
  "Dispatch values when `v` holds a multimethod. `defmethod` defines no
   var, so without this a namespace that is mostly methods outlines as one
   bare multifn with its whole surface hidden."
  [^Var v]
  (when (Var/.hasRoot v)
    (let [x (var-get v)]
      (when (instance? MultiFn x)
        (vec (sort-by str (keys (methods x))))))))


(defn- gist
  "The docstring's first sentence — a namespace or var should never appear
   as an anonymous symbol; the first line alone would cut at an arbitrary
   wrap point."
  [doc]
  (let [s (str/trim (str/replace doc #"\s+" " "))]
    (or (re-find #".*?\.(?=\s|$)" s) s)))


(defn- entry
  "One outline entry for var `v` under `hit` (nil = keep everything).
   Returns nil when `hit` matches neither name nor docstring. A docstring
   hit in bare mode quotes its matching lines as `:matched` — the answer
   shows its evidence, the way grep shows the line and not just the file."
  [v include-docstrings? hit]
  (let [{:keys [name arglists doc line macro private dynamic]} (meta v)
        doc-hits (when (and hit doc)
                   (into [] (comp (filter hit) (map str/trim))
                     (str/split-lines doc)))]
    (when (or (nil? hit) (hit (str name)) (seq doc-hits))
      (let [multi (dispatches v)]
        (cond-> {:name name}
          arglists (assoc :arglists arglists)
          macro (assoc :macro true)
          private (assoc :private true)
          dynamic (assoc :dynamic true)
          multi (assoc :dispatches multi)
          line (assoc :line line)
          (and doc include-docstrings?) (assoc :doc doc)
          (and (seq doc-hits) (not include-docstrings?)) (assoc :matched doc-hits))))))


(defn file
  "The source file of the namespace `x` designates, verbatim, as a string.
   The munged namespace path is tried first (`.clj`, then `.cljc`), so a
   namespace assembled from several files — clojure.core `load`s five —
   answers with its defining file; the `:file` metadata its vars carry is
   the fallback, covering unconventional layouts. Throws a plain
   explanation when no file is on the classpath — REPL-minted namespaces
   and source-stripped AOT jars have none, and that is worth saying
   outright rather than returning nil."
  [x]
  (let [ns     (designated x)
        munged (-> (str (ns-name ns)) (str/replace "." "/") (str/replace "-" "_"))
        paths  (concat [(str munged ".clj") (str munged ".cljc")]
                 (distinct (keep (comp :file meta) (vals (ns-interns ns)))))]
    (or (some #(some-> (io/resource %) slurp) paths)
      (throw (ex-info (str "No source file on the classpath for " (ns-name ns))
               {:ns (ns-name ns) :tried (vec paths)})))))


(defn nses
  "Every loaded namespace, sorted — the system at a glance, and the top of
   the drilldown that `outline` continues. `match` (string or regex, the
   same matcher `outline` takes; a symbol is matched as its name, so
   `(nses 'some.ns)` answers whether a namespace is really loaded) keeps
   only matching names. Names only, deliberately: a flat vector of
   symbols is the composable form — filter it, count it, map `outline`
   over it. Loaded namespaces, not the classpath: what the running system
   has actually required."
  ([] (nses nil))
  ([match]
   (let [hit (matcher (if (symbol? match) (str match) match))]
     (into []
       (cond->> (sort (map ns-name (all-ns)))
         hit (filter #(hit (str %))))))))


(defn vars
  "Every loaded var whose name or docstring matches, across all
   namespaces — the entry point for \"where does that function live?\",
   where `nses` searches namespace names and `outline` reads one
   namespace at a time. Hits are outline entries with `:ns` attached,
   scored: vars named exactly `match` first, then name hits, then
   docstring-only hits — the definition asked for outranks a mention of
   it — and namespace-then-source-line order within each tier. Docstring
   hits quote their matching lines as `:matched`, the same evidence
   `outline` shows. `match` is required — the JVM's whole var population
   is no glance — and takes what `nses` takes: a string
   (case-insensitive substring), a regex, or a symbol matched as its
   name."
  [match]
  (let [m     (if (symbol? match) (str match) match)
        hit   (or (matcher m)
                (throw (ex-info "vars searches: match with a string, a regex or a symbol"
                         {:match match})))
        exact (if (string? m)
                #(String/.equalsIgnoreCase m %)
                #(boolean (re-matches m %)))
        tier  (fn [e]
                (let [n (str (:name e))]
                  (cond (exact n) 0 (hit n) 1 :else 2)))]
    (->> (all-ns)
      (mapcat (fn [ns]
                (map #(into {:ns (ns-name ns)} %)
                  (keep #(entry % false hit) (vals (ns-interns ns))))))
      (sort-by (fn [e] [(tier e) (:ns e) (or (:line e) Long/MAX_VALUE)]))
      (into []))))


(defn outline
  "The outline of the namespace `ns` designates: `{:ns sym :doc gist
   :vars [entry …]}`, entries in source order, privates included and
   marked — the glance, signatures only, safe for any size of namespace.
   Options: `:include-docstrings` carries every docstring whole (and the
   full ns docstring); `:match` (string or regex) keeps only vars whose
   name or docstring matches. The default flipped twice on 2026-08-05:
   docstrings-on when this call was the initialize instructions'
   bootstrap and both fresh tenants stripped the option; back to the
   glance when the rendered manual moved into the instructions
   themselves and outline returned to being a reading tool."
  ([ns] (outline ns nil))
  ([ns {:keys [include-docstrings match]}]
   (let [ns  (designated ns)
         hit (matcher match)
         doc (:doc (meta ns))]
     (cond-> {:ns (ns-name ns)}
       doc (assoc :doc (if include-docstrings doc (gist doc)))
       true (assoc :vars
              (->> (vals (ns-interns ns))
                (keep #(entry % include-docstrings hit))
                (sort-by (fn [e] (or (:line e) Long/MAX_VALUE)))
                (into [])))))))
