(ns co.multiply.remontoire.rewire
  "Keeping a scratch namespace pointed at the live world across host reloads
   (§4).

   A reloader that unloads with `remove-ns` — clj-reload, tools.namespace —
   loads the file back into a *new* namespace object: new vars, and for a
   `deftype` or `defrecord`, a new class. The host's namespaces are reloaded
   with it, dependents included. A scratch is not file-backed, so no reloader
   touches it: its aliases, refers and imports keep naming the objects the
   reload replaced.

   Compiled code mostly escapes that — a compiled fn looks its vars and
   classes up again by name when its class loads. What uses the stale
   objects themselves is everything evaluated without compiling a class:
   a top-level `def` with a simple init, `(def x (f))`, and a bare class
   symbol, `(def c T)`. Macro expansion through a stale alias or refer
   always runs the old macro. The old generation's instances then reach
   host code that casts to the new class, and fail as
   `X cannot be cast to X` (measured 2026-10-01, Clojure 1.12.4).

   Those three mappings are the whole stale surface reachable from the
   mapping table. A fully qualified symbol resolves through the namespace
   registry and is never stale. What is out of reach stays as it is: a fn
   compiled before the reload holds its vars in its own class, and a def
   holds whatever value it was given."
  (:require
    [clojure.string :as str])
  (:import
    [clojure.lang DynamicClassLoader Namespace Var]
    [java.lang.reflect Field]
    [java.util.concurrent.atomic AtomicReference]))


(defn- field
  [name]
  (doto (Class/.getDeclaredField Namespace name) (Field/.setAccessible true)))


;; Namespace keeps both tables in package-private AtomicReferences. Public
;; API cannot replace an alias — `alias` refuses to rebind one, and
;; `removeAlias` + `addAlias` leaves a moment with no alias at all — so both
;; tables are swapped directly, one compare-and-set per table.
(def ^:private mappings (field "mappings"))
(def ^:private aliases (field "aliases"))


(defn- swap-table!
  [^Field table ns f]
  (AtomicReference/.updateAndGet (Field/.get table ns) f))


(defn alias!
  "Point `ns`'s alias `sym` at `target`, whatever it named before, in one
   atomic step: a concurrent eval compiling in `ns` sees the old alias or
   the new one, never none."
  [ns sym target]
  (swap-table! aliases ns #(assoc % sym target)))


(defn- repoint!
  "Apply `[sym stale live]` triples to `table` in one atomic step, each only
   while `sym` still maps to `stale` — a mapping changed concurrently, by a
   def or a require, belongs to whoever changed it."
  [table ns triples]
  (when (seq triples)
    (swap-table! table ns
      (fn [m]
        (reduce (fn [m [sym stale live]]
                  (cond-> m (identical? stale (get m sym)) (assoc sym live)))
          m triples)))))


(defn- successor
  "The namespace registered under `ns`'s name when that is no longer `ns`.
   nil when `ns` is current — and nil when the name is unregistered:
   mid-reload (removed, not yet loaded back) and deleted look the same, so
   the mapping is left for a later check rather than dropped. A successor
   still loading is the right target anyway: it is the object the reload
   fills, and what it lacks so far is lacking for qualified symbols too."
  [ns]
  (when-let [ns' (find-ns (ns-name ns))]
    (when-not (identical? ns ns') ns')))


(defn- newest
  "The newest class defined under `c`'s name. A fresh loader has loaded
   nothing, so it falls through to Clojure's class cache, whichever loader
   the calling thread carries — a long-lived `DynamicClassLoader` keeps
   answering with the first generation it ever returned. nil when the name
   no longer resolves."
  [^Class c loader]
  (try (Class/forName (Class/.getName c) false loader)
       (catch ClassNotFoundException _ nil)))


(defn- stale-mappings
  "`[sym stale live]` for each of `ns`'s refers and imports that a reload
   has replaced. Runs before every scratch form, so the walk is `reduce-kv`
   and a var's home is checked only when it differs from the last home
   found with nothing to re-point to — most refers share `clojure.core`."
  [ns]
  (let [loader  (delay (DynamicClassLoader.))
        settled (volatile! nil)]
    (reduce-kv
      (fn [acc sym x]
        (cond
          (var? x)
          (let [home (.-ns ^Var x)]
            (if (identical? home @settled)
              acc
              (if-let [succ (successor home)]
                (if-let [live (Namespace/.findInternedVar succ (.-sym ^Var x))]
                  (conj acc [sym x live])
                  acc)
                (do (vreset! settled home) acc))))

          (and (class? x)
            (instance? DynamicClassLoader (Class/.getClassLoader x)))
          (let [live (newest x @loader)]
            (if (and live (not (identical? x live)))
              (conj acc [sym x live])
              acc))

          :else acc))
      [] (ns-map ns))))


(defn rewire!
  "Re-point `ns`'s aliases, refers and imports that name what a reload has
   since replaced. Returns the re-pointed symbols, nil when nothing was
   stale, and says so on `*err*` — the eval's own output, where the caller
   learns that defs made before the reload may still hold the old code and
   values. Nothing is ever removed: a mapping whose replacement does not
   exist yet is left as it is for a later call."
  [ns]
  (let [moved    (reduce-kv (fn [acc sym target]
                              (if-let [live (successor target)]
                                (conj acc [sym target live])
                                acc))
                   [] (ns-aliases ns))
        remapped (stale-mappings ns)]
    (repoint! aliases ns moved)
    (repoint! mappings ns remapped)
    (when-let [syms (seq (sort (map first (concat moved remapped))))]
      (binding [*out* *err*]
        (println (str "re-pointed " (str/join ", " syms) " in " (ns-name ns)
                   " after a reload — defs made there before it may still"
                   " hold the old code and values")))
      (vec syms))))
