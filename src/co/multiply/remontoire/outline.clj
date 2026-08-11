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


(defn- pattern-of
  "The pattern a string or symbol match compiles to: case-insensitive,
   the text literal except `*`, a gap matching any run — glob spelling,
   `\"pu*-fn\"` finds `public-fn`. A starless match stays a plain
   substring; a literal `*` (earmuffed names) is a regex away."
  [s]
  (re-pattern (str "(?i)" (str/join ".*" (map #(Pattern/quote %) (str/split s #"\*" -1))))))


(defn- matcher
  "A predicate over strings from `match`: a regex as given, a string as a
   case-insensitive substring with `*` a gap, or a symbol matched as its
   name — models write \"evict\" as readily as #\"evict\" or 'evict, and
   all three should work."
  [match]
  (cond
    (nil? match) nil
    (instance? Pattern match) #(re-find match %)
    (or (string? match) (symbol? match))
    (let [p (pattern-of (str match))]
      #(re-find p %))
    :else (throw (ex-info (str ":match takes a string, a symbol or a regex, not "
                            (pr-str match))
                   {:match match}))))


(defn- exactly
  "The whole-string counterpart of `matcher`'s predicate: `m` — a string
   or a pattern — must cover all of its input, which is what admits a hit
   to the exact tier."
  [m]
  (let [p (if (string? m) (pattern-of m) m)]
    #(boolean (re-matches p %))))


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
  "One outline entry for var `v` under `name-hit` and `doc-hit` — nil
   meaning keep every name, search no docstring, respectively. Returns
   nil when neither matches. A docstring hit in bare mode quotes its
   matching lines as `:matched` — the answer shows its evidence, the way
   grep shows the line and not just the file."
  [v include-docstrings? name-hit doc-hit]
  (let [{:keys [name arglists doc line macro private dynamic]} (meta v)
        doc-hits (when (and doc-hit doc)
                   (into [] (comp (filter doc-hit) (map str/trim))
                     (str/split-lines doc)))]
    (when (or (nil? name-hit) (name-hit (str name)) (seq doc-hits))
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
  "The source file of the namespace `x` designates, as a vector of lines —
   lines, like `out`, because composition is the window: the 1-indexed
   `:line` of an `outline` or `vars` entry indexes it directly,
   `(drop (dec line) …)` starts reading there. The munged namespace path
   is tried first (`.clj`, then `.cljc`), so a namespace assembled from
   several files — clojure.core `load`s five — answers with its defining
   file; the `:file` metadata its vars carry is the fallback, covering
   unconventional layouts. Throws a plain explanation when no file is on
   the classpath — REPL-minted namespaces and source-stripped AOT jars
   have none, and that is worth saying outright rather than returning
   nil."
  [x]
  (let [ns     (designated x)
        munged (-> (str (ns-name ns)) (str/replace "." "/") (str/replace "-" "_"))
        paths  (concat [(str munged ".clj") (str munged ".cljc")]
                 (distinct (keep (comp :file meta) (vals (ns-interns ns)))))]
    (or (some #(some-> (io/resource %) slurp str/split-lines) paths)
      (throw (ex-info (str "No source file on the classpath for " (ns-name ns))
               {:ns (ns-name ns) :tried (vec paths)})))))


(defn nses
  "Every loaded namespace — the system at a glance, and the top of the
   drilldown that `outline` continues. `match` (a string — `*` a gap — a
   regex, or a symbol matched as its name, as everywhere) searches names
   and namespace docstrings, ranked the way `vars` ranks: names with a
   whole dot-segment matching exactly first — \"outline\" puts
   `x.outline` above `x.outline-test` — then remaining name hits, then
   docstring-only hits, alphabetical within each tier; so
   `(nses 'some.ns)` still answers whether a namespace is really loaded.
   Without `match`, all of them, sorted. `{:search-docstrings false}`
   restricts the search to names — the third tier vanishes; it is how
   `vars` scopes a qualified match's ns side, where a docstring mention
   would be a surprising reason to widen a scope. Names only in the output,
   deliberately: a flat vector of symbols is the composable form —
   filter it, count it, map `outline` over it — which means a docstring
   hit carries no quoted evidence; `outline` the namespace to see it.
   Loaded namespaces, not the classpath: what the running system has
   actually required."
  ([] (nses nil))
  ([match] (nses match nil))
  ([match {:keys [search-docstrings] :or {search-docstrings true}}]
   (let [m   (if (symbol? match) (str match) match)
         hit (matcher m)]
     (if-not hit
       (into [] (sort (map ns-name (all-ns))))
       (let [exact (exactly m)
             tier  (fn [ns]
                     (let [n (str (ns-name ns))]
                       (cond
                         (or (exact n)
                           (some exact (str/split n #"\."))) 0
                         (hit n) 1
                         (and search-docstrings
                           (some-> (:doc (meta ns)) hit)) 2)))]
         (->> (all-ns)
           (keep #(when-let [t (tier %)] [t (ns-name %)]))
           sort
           (mapv second)))))))


(defn- qualified
  "The `[ns-side name-side]` a qualified match splits into, nil when
   `match` has no `/`. Symbols arrive split by the reader — the one
   spelling that reaches the var named `/`, 'clojure.core// — while
   strings split at their single `/`: more than one is ambiguous and
   throws, as does \"/\" alone, which scopes nothing on either side."
  [match]
  (if (symbol? match)
    (when-let [nsp (namespace match)]
      (let [np (name match)]
        (when (or (str/includes? nsp "/")
                (and (not= "/" np) (str/includes? np "/")))
          (throw (IllegalArgumentException.
                   (str "More than one / in " match))))
        [nsp np]))
    (when-let [i (str/index-of match "/")]
      (let [nsp (subs match 0 i)
            np  (subs match (inc i))]
        (when (str/includes? np "/")
          (throw (IllegalArgumentException.
                   (str "More than one / in " (pr-str match)
                     " — the var named / is reached as a symbol: 'clojure.core//"))))
        (when (= "/" match)
          (throw (IllegalArgumentException.
                   "\"/\" scopes nothing on either side — give an ns side, a name side, or both")))
        [nsp np]))))


(defn vars
  "Every loaded var whose name or docstring matches, across all
   namespaces — the entry point for \"where does that function live?\",
   where `nses` searches namespace names and `outline` reads one
   namespace at a time. Hits are outline entries with `:ns` attached,
   scored: exact name matches first, then name hits, then docstring-only
   hits — the definition asked for outranks a mention of it — and
   namespace-then-source-line order within each tier. Docstring hits
   quote their matching lines as `:matched`, the same evidence `outline`
   shows. `match` is required — the JVM's whole var population is no
   glance — and takes what `nses` takes: a string (case-insensitive
   substring, `*` a gap), a regex, or a symbol matched as its name. A
   `/` qualifies the match, spelling the search the way Clojure spells
   the target: \"helpers/await\" — string or symbol — scopes to the
   namespaces whose *name* matches the ns side (docstrings not
   consulted: a scope widened by a docstring mention would surprise) and
   matches the name side within them, docstrings included as ever.
   Either side may be empty — \"helpers/\" lists a scope whole,
   \"/await\" degenerates to the bare search. A regex containing `/`
   instead matches whole qualified names, #\"helpers[^/]*/await\" — the
   one spelling that mixes forms — where a slashless regex matches bare
   names. `{:search-docstrings false}` restricts the name side to names
   alone — the docstring tier vanishes."
  ([match]
   (vars match nil))
  ([match {:keys [search-docstrings] :or {search-docstrings true}}]
   (let [[nsp np] (when (or (string? match) (symbol? match))
                    (qualified match))
         qregex?  (and (instance? Pattern match)
                    (str/includes? (Pattern/.pattern match) "/"))
         m        (cond
                    nsp             (not-empty np)
                    (symbol? match) (str match)
                    :else           match)
         hit      (matcher m)
         _        (when-not (or hit nsp)
                    (throw (ex-info "vars searches: match with a string, a regex or a symbol"
                             {:match match})))
         exact    (when hit (exactly m))
         doc-hit  (when (and hit search-docstrings) hit)
         subject  (if qregex?
                    #(str (:ns %) "/" (:name %))
                    #(str (:name %)))
         tier     (fn [e]
                    (if-not hit
                      0
                      (let [n (subject e)]
                        (cond (exact n) 0 (hit n) 1 :else 2))))]
     (->> (if nsp
            (keep find-ns (nses nsp {:search-docstrings false}))
            (all-ns))
       (mapcat (fn [ns]
                 (let [name-hit (if qregex?
                                  #(hit (str (ns-name ns) "/" %))
                                  hit)]
                   (map #(into {:ns (ns-name ns)} %)
                     (keep #(entry % false name-hit doc-hit) (vals (ns-interns ns)))))))
       (sort-by (fn [e] [(tier e) (:ns e) (or (:line e) Long/MAX_VALUE)]))
       (into [])))))


(defn outline
  "The outline of the namespace `ns` designates: `{:ns sym :doc gist
   :vars [entry …]}`, entries in source order, privates included and
   marked — the glance, signatures only, safe for any size of namespace.
   Options: `:include-docstrings` carries every docstring whole (and the
   full ns docstring); `:match` (a string, a regex, or a symbol matched
   as its name, as everywhere) keeps only vars whose name or docstring
   matches. The default flipped twice on 2026-08-05:
   docstrings-on when this call was the initialize instructions'
   bootstrap and both fresh tenants stripped the option; back to the
   glance when the rendered manual moved into the instructions
   themselves and outline returned to being a reading tool."
  ([ns] (outline ns nil))
  ([ns {:keys [include-docstrings include-private match]
        :or   {include-private true}}]
   (let [ns  (designated ns)
         hit (matcher match)
         doc (:doc (meta ns))]
     (-> {:ns (ns-name ns)}
       (cond-> doc (assoc :doc (if include-docstrings doc (gist doc))))
       (assoc :vars
         (let [entries (keep #(entry % include-docstrings hit hit) (vals (ns-interns ns)))
               entries (if include-private entries (filter #(-> % :private not) entries))]
           (->> entries
             (sort-by (fn [e] (or (:line e) Long/MAX_VALUE)))
             (into []))))))))
