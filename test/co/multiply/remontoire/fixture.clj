(ns co.multiply.remontoire.fixture
  "A specimen namespace with one of everything, for the outline tests. Not
   itself a test namespace.")


(def plain-value
  "A plain value. With a second sentence the gist should drop."
  42)


(def ^:private hidden
  "A private var."
  :secret)


(def ^:dynamic *knob*
  "A dynamic var."
  nil)


(defn public-fn
  "Computes a very distinctive thing: the frobnitz quotient."
  [x]
  (inc x))


(defn- private-fn
  "A helper of no consequence."
  [x]
  (dec x))


(defmacro public-macro
  "A macro."
  [& body]
  `(do ~@body))


(defmulti poly
  "A multimethod."
  :kind)


(defmethod poly :a [_] :a)
(defmethod poly :b [_] :b)

;; The sigil family: one stem under each conventional decoration, the
;; undecorated stem deliberately last — only ranking, never source
;; order, can put it first. The impl/run pair reverses source order
;; against publicness for the same reason.

(def ^:dynamic *gadget*
  "An earmuffed relative."
  nil)


(defn- gadget-impl
  "A helper, defined before the sibling it serves."
  [x]
  x)


(defn gadget-run
  "The exported sibling, defined after its helper."
  [x]
  x)


(defn gadget?
  "A predicate."
  [x]
  (boolean x))


(defn ->gadget
  "A constructor."
  [x]
  {:x x})


(defn map->gadget
  "A map constructor."
  [m]
  m)


(defn gadget
  "The undecorated stem."
  []
  :gadget)
