(ns co.multiply.remontoire.patterns.uploads
  "Files sent to this JVM, put to use. A file sent to `(__r/upload-url)`
   lands staged: an ordinary file in a temporary directory, which nothing
   has loaded. What comes next depends on what the JVM runs from. Started
   from a checkout, its source files are on disk, and an edited namespace
   is moved over the file it replaces and reloaded, exactly as if it had
   been edited there. Started from a jar, there is no file to replace, and
   the staged file is loaded where it lies."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [java.io PushbackReader]
    [java.net URL]
    [java.nio.file CopyOption Files Path StandardCopyOption]
    [java.nio.file.attribute FileAttribute]))


(defn- declared-ns
  "The namespace a source file declares — the name in its leading `ns`
   form. Throws when the file does not open with one."
  [staged]
  (let [form (with-open [reader (PushbackReader. (io/reader (str staged)))]
               (read {:read-cond :allow :eof nil} reader))]
    (if (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)
      (throw (ex-info "Not a namespace's source file: it does not open with an ns form"
               {:staged (str staged)})))))


(defn- resource-path
  "Where the classpath keeps `ns-sym`: its munged path, with the staged
   file's own extension."
  [ns-sym staged]
  (str (str/replace (str (namespace-munge ns-sym)) "." "/")
    (or (re-find #"\.clj[cs]?$" (str staged)) ".clj")))


(defn- move!
  [staged ^Path dest]
  (some-> (Path/.getParent dest) (Files/createDirectories (make-array FileAttribute 0)))
  (Files/move (Path/of (str staged) (make-array String 0)) dest
    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))


(defn place!
  "Move the staged source file `staged` to where its namespace lives, and
   return the namespace symbol — reloading is the caller's, in whatever
   way the project reloads.

   With `staged` alone, the file replaces the one its namespace was loaded
   from, found on the classpath. Throws when that is not a file on disk —
   a namespace living in a jar, or one this JVM has no source for — and
   leaves the staged file where it was.

   A namespace new to the JVM has no file to replace: name `root`, the
   source directory it belongs under, as the JVM sees it — relative paths
   resolve against its working directory."
  ([staged]
   (let [ns-sym   (declared-ns staged)
         resource (resource-path ns-sym staged)
         url      (io/resource resource)]
     (when-not (= "file" (some-> url URL/.getProtocol))
       (throw (ex-info (str ns-sym " has no source file on disk to replace")
                {:ns ns-sym :resource resource :found (some-> url str)})))
     (move! staged (Path/of (URL/.toURI url)))
     ns-sym))
  ([staged root]
   (let [ns-sym (declared-ns staged)]
     (move! staged (Path/of (str root) (into-array String [(resource-path ns-sym staged)])))
     ns-sym)))


(comment
  ;; From your shell, send the edited file to (__r/upload-url):
  ;;
  ;;   curl -T src/app/orders.clj <url>
  ;;
  ;; The response names where it was staged — `path` below — and
  ;; (__r/uploads) lists everything staged, should the path be lost.

  ;; The JVM runs from a checkout: put the file where its namespace
  ;; lives, then reload the way the project reloads. In plain Clojure:
  (require (place! path) :reload)

  ;; A namespace this JVM has never loaded has no file to replace; say
  ;; which source directory it belongs under:
  (require (place! path "src"))

  ;; An edit across several namespaces: place them all, then reload —
  ;; dependencies before dependents, or once with the project's reloader.
  (run! place! paths)

  ;; The JVM runs from a jar: nothing on disk to replace, so the staged
  ;; file is loaded where it lies. Its definitions are live from here on;
  ;; `source` and `file` keep showing what the jar holds.
  (load-file path)

  ;; Many files in one go: send an archive rather than each file. Zip is
  ;; the format to pick — the JDK reads it unaided — with paths as the
  ;; repository lays them out. `git archive` only knows committed work;
  ;; for the edits in a working tree, untracked files included:
  ;;
  ;;   { git diff --name-only HEAD; git ls-files --others --exclude-standard; } \
  ;;     | zip changes.zip -@
  ;;   curl -T changes.zip <url>
  ;;
  ;; When the JVM's checkout is laid out like yours, unpacking over its
  ;; root puts every file in place at once — "." below is the JVM's
  ;; working directory — and one reload does the rest; a reloader that
  ;; tracks changed files (clj-reload, tools.namespace) finds them itself.
  (require '[clojure.java.io :as io])
  (let [root (.getCanonicalFile (io/file "."))]
    (with-open [zip (java.util.zip.ZipInputStream. (io/input-stream path))]
      (vec (for [entry (take-while some? (repeatedly #(.getNextEntry zip)))
                 :when (not (.isDirectory entry))
                 :let  [dest (.getCanonicalFile (io/file root (.getName entry)))]]
             (do (assert (.startsWith (.toPath dest) (.toPath root)) "an entry may not leave the root")
               (io/make-parents dest)
               (io/copy zip dest)
               (str (.relativize (.toPath root) (.toPath dest)))))))))
