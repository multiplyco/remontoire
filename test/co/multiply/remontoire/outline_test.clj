(ns co.multiply.remontoire.outline-test
  "Navigation reads the running system: `nses` → `outline` → `file`, with
   the composability invariant — every result element is a valid input to
   the other functions — exercised on a specimen namespace."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [co.multiply.remontoire.fixture :as fix]
    [co.multiply.remontoire.outline :as outline]))


(deftest nses-lists-loaded-namespaces
  (let [all (outline/nses)]
    (is (vector? all))
    (is (every? symbol? all))
    (is (= all (vec (sort all))) "sorted")
    (is (some #{'clojure.core} all)))
  (is (= ['co.multiply.remontoire.fixture]
        (outline/nses 'co.multiply.remontoire.fixture))
    "a symbol answers whether a namespace is loaded")
  (is (= [] (outline/nses 'no.such.loaded.ns)))
  (let [hits (outline/nses "REMONTOIRE")]
    (is (seq hits) "string match is case-insensitive")
    (is (every? #(str/includes? (str %) "remontoire") hits))))


(deftest outline-is-the-namespace-at-a-glance
  (let [o       (outline/outline 'co.multiply.remontoire.fixture)
        by-name (into {} (map (juxt :name identity)) (:vars o))]
    (is (= 'co.multiply.remontoire.fixture (:ns o)))
    (is (string? (:doc o)))
    (is (= '[plain-value hidden *knob* public-fn private-fn public-macro poly]
          (mapv :name (:vars o)))
      "source order, privates included")
    (is (true? (:private (by-name 'hidden))))
    (is (true? (:macro (by-name 'public-macro))))
    (is (true? (:dynamic (by-name '*knob*))))
    (is (= [:a :b] (:dispatches (by-name 'poly)))
      "a multimethod shows its dispatch values")
    (is (seq (:arglists (by-name 'public-fn))))
    (is (every? :line (:vars o)))
    (is (not-any? :doc (:vars o)) "the glance carries no docstrings")))


(deftest outline-options
  (let [full (outline/outline 'co.multiply.remontoire.fixture
               {:include-docstrings true})]
    (is (some #(= "A macro." (:doc %)) (:vars full))))
  (let [m (outline/outline 'co.multiply.remontoire.fixture {:match "frobnitz"})]
    (is (= '[public-fn] (mapv :name (:vars m)))
      "a docstring hit keeps the var")
    (is (seq (:matched (first (:vars m)))) "and quotes its evidence"))
  (let [m (outline/outline 'co.multiply.remontoire.fixture {:match "public"})]
    (is (= '[public-fn public-macro] (mapv :name (:vars m))))))


(deftest designators-compose
  (is (= 'co.multiply.remontoire.fixture
        (:ns (outline/outline "co.multiply.remontoire.fixture"))))
  (is (= 'co.multiply.remontoire.fixture
        (:ns (outline/outline (the-ns 'co.multiply.remontoire.fixture)))))
  (binding [*ns* (the-ns 'co.multiply.remontoire.outline-test)]
    (is (= 'co.multiply.remontoire.fixture (:ns (outline/outline 'fix)))
      "an alias visible from the calling namespace designates"))
  (is (thrown? Exception (outline/outline 'no.such.ns)))
  (is (thrown? Exception (outline/outline 42))))


(deftest file-reads-what-is-written
  (let [s (outline/file 'co.multiply.remontoire.fixture)]
    (is (str/includes? s "(ns co.multiply.remontoire.fixture"))
    (is (str/includes? s "(defmethod poly :a")
      "the whole file, not just what interns vars"))
  (try
    (is (thrown? Exception (outline/file (create-ns 'remontoire.test.no-file)))
      "a namespace with no source says so rather than returning nil")
    (finally (remove-ns 'remontoire.test.no-file))))
