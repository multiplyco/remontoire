(ns co.multiply.remontoire.rewire-test
  "Scratch across host reloads (§4): a reload that replaces a namespace
   object leaves the scratch's aliases, refers and imports re-pointed by the
   next form, never dropped, and host namespaces untouched.

   Staleness is observed through the paths that use a mapping's own object
   — an interpreted `def` init, a bare class symbol, a macro. A top-level
   call compiles, and compiled code finds its vars and classes by name, so
   it reads live with or without a rewire and proves nothing here."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.evaluate :as evaluate]
    [co.multiply.remontoire.rewire :as rewire]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.test-util :as tu])
  (:import
    [clojure.lang Namespace Symbol]
    [java.io Writer]
    [java.lang.reflect Constructor]))


(use-fixtures :once
  (fn [f]
    (tu/reset-state!)
    (tee/install!)
    (try (f) (finally (tee/uninstall!)))))


(defn- run!!
  "Evaluate `code` for `sid` and settle, bounded.
   Returns {:bank :error :value}."
  ([code sid] (run!! code nil sid))
  ([code ns-str sid]
   (let [{:keys [task bank]} (evaluate/evaluate code ns-str sid)]
     @(q/await task 10000)
     {:bank  bank
      :error (q/get-ex task)
      :value (when-not (q/exceptional? task) @task)})))


(def ^:private lib 'remontoire.test.reloaded)


(defn load-lib!
  "What a `remove-ns` reloader leaves behind: `lib` removed, then loaded
   back into a new namespace object — `f` and the macro `m` answering
   `tag`, and a fresh generation of the deftype `T`; without `f` when `f?`
   is false. Public, so an eval can drive it the way an eval drives a
   host's reload."
  ([tag] (load-lib! tag true))
  ([tag f?]
   (remove-ns lib)
   (binding [*ns* (create-ns lib)]
     (refer-clojure)
     (when f? (eval `(defn ~'f [] ~tag)))
     (eval `(defmacro ~'m [] ~tag))
     (eval '(deftype T [x]))
     *ns*)))


(def ^:private wire
  (str "(alias 'rl '" lib ") "
    "(refer '" lib " :only '[f m]) "
    "(import '" lib ".T) "
    ":wired"))


(defmacro ^:private with-lib
  [& body]
  `(try ~@body (finally (remove-ns lib))))


(deftest aliases-refers-and-imports-follow-a-reload
  (with-lib
    (let [sid (session/mint!)]
      (load-lib! :old)
      (run!! wire sid)
      (load-lib! :new)
      (let [{:keys [value error bank]}
            (run!! (str "(def via-alias (rl/f)) "
                     "(def via-refer (f)) "
                     "(def via-macro (rl/m)) "
                     "(def via-import T) "
                     "[via-alias via-refer via-macro "
                     " (identical? via-import (class (" lib "/->T 1)))]")
              sid)]
        (is (nil? error))
        (is (= [:new :new :new true] value)
          "alias, refer, macro and import all reach the live generation")
        (is (not (str/blank? (tee/text bank)))
          "the eval that re-pointed says so"))
      (is (str/blank? (tee/text (:bank (run!! ":quiet" sid))))
        "nothing stale, nothing said"))))


(deftest a-reload-inside-the-eval-reaches-the-next-form
  (with-lib
    (let [sid (session/mint!)]
      (load-lib! :old)
      (run!! wire sid)
      (is (= :new (:value (run!! (str "(co.multiply.remontoire.rewire-test/load-lib! :new) "
                                   "(def x (rl/f)) "
                                   "x")
                            sid)))))))


(deftest nothing-is-dropped
  (with-lib
    (let [sid     (session/mint!)
          _       (load-lib! :old)
          _       (run!! wire sid)
          scratch (the-ns (session/scratch-ns-sym sid))
          before  (ns-map scratch)
          old-ns  (get (ns-aliases scratch) 'rl)]
      (remove-ns lib)
      (is (nil? (:error (run!! ":fine" sid)))
        "a namespace gone mid-reload is not an error")
      (is (identical? old-ns (get (ns-aliases scratch) 'rl))
        "its alias stays until there is something to re-point to")
      (load-lib! :new false)
      (run!! ":fine" sid)
      (is (identical? (get before 'f) (get (ns-map scratch) 'f))
        "a refer whose var the successor lacks stays as it was")
      (is (not (identical? old-ns (get (ns-aliases scratch) 'rl)))
        "while the alias moves on"))))


(deftest host-namespaces-are-left-to-their-reloader
  (with-lib
    (let [sid  (session/mint!)
          host 'remontoire.test.host]
      (try
        (let [old-ns (load-lib! :old)]
          (run!! (str "(ns " host ") " wire) sid)
          (load-lib! :new)
          (is (nil? (:error (run!! ":fine" (str host) sid))))
          (is (identical? old-ns (get (ns-aliases (the-ns host)) 'rl))))
        (finally (remove-ns host))))))


(defn- detached
  "A namespace object named `sym` that the registry does not hold — what a
   reload leaves a stale mapping pointing at."
  [sym]
  (let [ctor (doto (Class/.getDeclaredConstructor Namespace (into-array Class [Symbol]))
               (Constructor/.setAccessible true))]
    (Constructor/.newInstance ctor (object-array [sym]))))


(deftest stale-wiring-does-not-wedge-the-scratch
  ;; The furnishing re-requires Quiescent `:as q` every eval, and `alias`
  ;; refuses to rebind — so a `q` naming a replaced namespace object would
  ;; fail every eval in the scratch unless it is re-pointed first.
  (let [sid     (session/mint!)
        _       (run!! ":warm" sid)
        scratch (the-ns (session/scratch-ns-sym sid))]
    (rewire/alias! scratch 'q (detached 'co.multiply.quiescent))
    (let [{:keys [value error]} (run!! "@(q/task 1)" sid)]
      (is (nil? error))
      (is (= 1 value)))))


(deftest concurrent-evals-never-see-a-mapping-missing
  (with-lib
    (let [sid     (session/mint!)
          old-ns  (load-lib! :old)
          _       (run!! wire sid)
          _       (load-lib! :new)
          scratch (the-ns (session/scratch-ns-sym sid))
          stop    (promise)
          flipper (future
                    (binding [*err* (Writer/nullWriter)]
                      (while (not (realized? stop))
                        (rewire/alias! scratch 'rl old-ns)
                        (rewire/rewire! scratch))))
          ;; The compiler's own alias lookup, without minting a class per try.
          misses  (try
                    (count (remove some? (repeatedly 200000 #(ns-resolve scratch 'rl/f))))
                    (finally (deliver stop true)))]
      @flipper
      (is (zero? misses)
        "resolving through the alias while it is re-pointed never finds it absent"))))
