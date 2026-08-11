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


(deftest nses-names-only-on-request
  (try
    (alter-meta! (create-ns 'aaa.marginalia) assoc
      :doc "Mentions the fixture namespace without resembling it.")
    (is (some #{'aaa.marginalia} (outline/nses "fixture"))
      "by default docstrings are searched")
    (let [hits (outline/nses "fixture" {:search-docstrings false})]
      (is (some #{'co.multiply.remontoire.fixture} hits))
      (is (not-any? #{'aaa.marginalia} hits)
        "with :search-docstrings false the docstring tier vanishes"))
    (finally
      (remove-ns 'aaa.marginalia))))


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


(deftest vars-scopes-by-qualified-match
  (try
    (alter-meta! (create-ns 'aaa.commentary) assoc
      :doc "Mentions the fixture namespace without resembling it.")
    (intern 'aaa.commentary 'public-decoy :decoy)
    (let [hits (outline/vars "fixture/public")]
      (is (= '[public-fn public-macro] (mapv :name hits))
        "only vars from the scoped namespaces answer")
      (is (every? #(str/includes? (str (:ns %)) "fixture") hits)
        "the scope is namespaces whose *name* matches")
      (is (not-any? #(= 'public-decoy (:name %)) hits)
        "a docstring mention of the scope term does not widen it"))
    (is (= (outline/vars "fixture/public") (outline/vars 'fixture/public))
      "string and symbol spell the same qualified match")
    (is (= [] (outline/vars "no.such.loaded.ns/public"))
      "an empty scope is an empty answer, not the whole system")
    (is (= (outline/vars "frobnitz") (outline/vars "frobnitz" nil))
      "no scope means the whole system, as in the 1-arity")
    (finally
      (remove-ns 'aaa.commentary))))


(deftest vars-qualified-empty-sides
  (is (= (outline/vars "frobnitz") (outline/vars "/frobnitz"))
    "an empty ns side degenerates to the bare search")
  (let [hits (outline/vars "co.multiply.remontoire.fixture/")]
    (is (= '[plain-value hidden *knob* public-fn private-fn public-macro poly]
          (mapv :name hits))
      "an empty name side lists the scope whole, in source order")
    (is (not-any? :matched hits) "no name match, no quoted evidence")))


(deftest vars-qualified-validation
  (is (thrown? IllegalArgumentException (outline/vars "a/b/c"))
    "more than one / in a string is ambiguous")
  (is (thrown? IllegalArgumentException (outline/vars (symbol "a/b/c")))
    "and in a symbol's ns part likewise")
  (is (thrown? IllegalArgumentException (outline/vars "core//"))
    "the var named / is a symbol's to reach")
  (is (thrown? IllegalArgumentException (outline/vars "/"))
    "both sides empty scopes nothing")
  (is (= '{:ns clojure.core, :name /}
        (select-keys (first (outline/vars 'clojure.core//)) [:ns :name]))
    "the reader splits 'clojure.core// — the division var answers, exact tier first"))


(deftest vars-qualified-regex
  (is (= '[public-fn public-macro]
        (mapv :name (outline/vars #"fixture/public-")))
    "a regex with a / matches whole qualified names")
  (is (not-any? #(= 'plain-value (:name %)) (outline/vars #"fixture"))
    "a slashless regex matches bare names — a namespace hit alone is no hit"))


(deftest vars-names-only-on-request
  (is (some #(= 'public-fn (:name %)) (outline/vars "fixture/frobnitz"))
    "by default the name side searches docstrings")
  (is (= [] (outline/vars "fixture/frobnitz" {:search-docstrings false}))
    "with :search-docstrings false the docstring tier vanishes")
  (is (= [] (outline/vars "frobnitz" {:search-docstrings false}))
    "on the bare search too"))


(deftest star-is-a-gap
  (is (= '[public-fn] (mapv :name (outline/vars "fixture/pu*-fn")))
    "* matches any run within the name side")
  (is (some #{'co.multiply.remontoire.fixture} (outline/nses "co.*.fixture"))
    "the wildcard serves nses the same way")
  (is (= '[public-macro]
        (mapv :name (:vars (outline/outline 'co.multiply.remontoire.fixture
                             {:match "p*-macro"}))))
    "and outline's :match")
  (is (= [] (outline/vars "no*such*name"))
    "a starred miss is still a miss"))


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
    (is (= '[public-fn public-macro] (mapv :name (:vars m))))
    (is (= m (outline/outline 'co.multiply.remontoire.fixture {:match 'public}))
      "a symbol is matched as its name, as in nses/vars"))
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
  (let [lines (outline/file 'co.multiply.remontoire.fixture)]
    (is (vector? lines))
    (is (every? string? lines))
    (is (str/starts-with? (first lines) "(ns co.multiply.remontoire.fixture"))
    (is (some #(str/includes? % "(defmethod poly :a") lines)
      "the whole file, not just what interns vars")
    (let [line (->> (outline/outline 'co.multiply.remontoire.fixture) :vars
                 (some #(when (= 'public-fn (:name %)) (:line %))))]
      (is (str/starts-with? (nth lines (dec line)) "(defn public-fn")
        "an outline entry's 1-indexed :line indexes the lines")))
  (try
    (is (thrown? Exception (outline/file (create-ns 'remontoire.test.no-file)))
      "a namespace with no source says so rather than returning nil")
    (finally (remove-ns 'remontoire.test.no-file))))
