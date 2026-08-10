(ns co.multiply.remontoire.outline-test
  "Navigation reads the running system: `nses`/`vars` → `outline` →
   `file`, with the composability invariant — every result element
   is a valid input to the other functions — exercised on a
   specimen namespace."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    ;; The fix alias looks unused but is resolved dynamically:
    ;; designators-compose passes 'fix to outline as an alias designator.
    [co.multiply.remontoire.fixture :as fix]
    [co.multiply.remontoire.outline :as outline])
  (:import
    [java.util List]))


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


(deftest nses-ranks-like-vars
  ;; aaa.* sorts before co.*, so alphabetical order alone would put both
  ;; created namespaces first — only ranking explains the fixture leading.
  (try
    (create-ns 'aaa.fixture-adjacent)
    (alter-meta! (create-ns 'aaa.remarks) assoc
      :doc "Mentions the fixture namespace without resembling it.")
    (let [hits (outline/nses "fixture")
          pos  #(List/.indexOf hits %)]
      (is (every? #(<= 0 (pos %))
            '[co.multiply.remontoire.fixture aaa.fixture-adjacent aaa.remarks])
        "name hits, substring hits and docstring hits all surface")
      (is (< (pos 'co.multiply.remontoire.fixture) (pos 'aaa.fixture-adjacent))
        "an exact segment outranks a substring hit, whatever the alphabet says")
      (is (< (pos 'aaa.fixture-adjacent) (pos 'aaa.remarks))
        "a name hit outranks a docstring-only hit"))
    (finally
      (remove-ns 'aaa.fixture-adjacent)
      (remove-ns 'aaa.remarks))))


(deftest vars-searches-the-whole-loaded-system
  (let [hits (outline/vars "frobnitz")]
    (is (= '[co.multiply.remontoire.fixture] (mapv :ns hits))
      "a docstring hit carries the namespace that answers where it lives")
    (is (= 'public-fn (:name (first hits))))
    (is (seq (:matched (first hits))) "and quotes its evidence"))
  (is (= (outline/vars "public-macro") (outline/vars 'public-macro))
    "a symbol is matched as its name")
  (let [hits (outline/vars #"^poly$")]
    (is (some #(= [:a :b] (:dispatches %)) hits)
      "hits are outline entries, multimethod dispatches included"))
  (let [hits (outline/vars "private-fn")]
    (is (some #(and (= 'co.multiply.remontoire.fixture (:ns %)) (:private %)) hits)
      "privates are searched and marked"))
  (let [hits (outline/vars "outline")]
    (is (< 1 (count (distinct (map :ns hits)))) "the search spans namespaces"))
  (is (= 'vars (:name (first (outline/vars 'vars))))
    "an exact name hit outranks names that merely contain the match")
  (let [fx (filterv #(= 'co.multiply.remontoire.fixture (:ns %))
             (outline/vars "private"))]
    (is (= '[private-fn hidden] (mapv :name fx))
      "a name hit outranks a docstring hit, whatever the source order"))
  (is (= [] (outline/vars "no-var-answers-to-this-name")))
  (is (thrown? Exception (outline/vars nil))
    "a match is required — the whole var population is no glance"))


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
    (is (= '[public-fn public-macro] (mapv :name (:vars m)))))
  (let [pub (outline/outline 'co.multiply.remontoire.fixture
              {:include-private false})]
    (is (not-any? :private (:vars pub)) "the public surface alone on request")
    (is (= '[plain-value *knob* public-fn public-macro poly]
          (mapv :name (:vars pub)))
      "everything else survives the filter, order untouched")))


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
