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
