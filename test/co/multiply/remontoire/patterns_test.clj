(ns co.multiply.remontoire.patterns-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [co.multiply.remontoire.patterns.uploads :as uploads])
  (:import
    [java.io File]
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))


(defn- temp-dir
  ^File []
  (.toFile (Files/createTempDirectory "patterns-test-" (make-array FileAttribute 0))))


(defn- staged!
  "A staged file named `name` holding `source`, in a directory of its own."
  ^File [name source]
  (doto (io/file (temp-dir) name) (spit source)))


(deftest a-new-namespace-is-placed-under-the-root-it-is-given
  (let [root   (temp-dir)
        staged (staged! "anything.cljc" ";; a comment first\n(ns trial.some-ns.core)\n(def x 1)\n")]
    (is (= 'trial.some-ns.core (uploads/place! (str staged) (str root)))
      "the answer is the namespace, ready to reload")
    (is (= ";; a comment first\n(ns trial.some-ns.core)\n(def x 1)\n"
           (slurp (io/file root "trial/some_ns/core.cljc")))
      "munged path, the staged file's own extension")
    (is (not (File/.exists staged)) "moved, not copied")))


(deftest a-namespace-with-no-file-on-disk-is-not-placed
  (let [jar-resident (staged! "json.clj" "(ns clojure.data.json)\n")
        unknown      (staged! "nowhere.clj" "(ns trial.loaded-from.nowhere)\n")
        not-source   (staged! "data.edn" "{:rows [1 2 3]}\n")]
    (is (thrown? clojure.lang.ExceptionInfo (uploads/place! (str jar-resident))))
    (is (thrown? clojure.lang.ExceptionInfo (uploads/place! (str unknown))))
    (is (thrown? clojure.lang.ExceptionInfo (uploads/place! (str not-source))))
    (is (every? File/.exists [jar-resident unknown not-source])
      "what could not be placed stays staged")))
