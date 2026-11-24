(ns co.multiply.remontoire.upload
  "Staged files: bytes a client hands the JVM, kept in the session's upload
   directory until the session ends. Staging is all that happens here —
   where a file belongs, and whether it is loaded, moved or only read, is
   the caller's to decide in ordinary Clojure.

   The directory is the record: a listing reads it, so a file moved out or
   deleted is gone from the listing with nothing to keep in step."
  (:require
    [clojure.string :as str]
    [co.multiply.remontoire.session :as session])
  (:import
    [java.io IOException InputStream OutputStream]
    [java.nio.file CopyOption FileVisitOption Files InvalidPathException LinkOption OpenOption Path
     StandardCopyOption]
    [java.nio.file.attribute FileAttribute FileTime]
    [java.util Date]
    [java.util.stream Stream]))


(def max-bytes
  "The largest file staged, in bytes. A bound against a runaway upload
   filling the temp volume, not a judgment about what is worth sending."
  (* 64 1024 1024))


(defn- refuse
  [status message]
  (throw (ex-info message {:status status})))


(defn- target
  "Where client-chosen `name` lands beneath `dir`, or nil when it names
   nothing or would land elsewhere. `/` separates directories, so two files
   of one basename can be staged side by side; every segment must be a
   plain name."
  ^Path [^Path dir ^String name]
  (let [segments (str/split name #"/" -1)]
    (when-not (some #(or (str/blank? %) (#{"." ".."} %) (str/includes? % "\\")) segments)
      (try
        (let [path (Path/.normalize (Path/.resolve dir name))]
          (when (and (Path/.startsWith path dir) (not= path dir))
            path))
        (catch InvalidPathException _ nil)))))


(defn- copy!
  "Drain `in` to `file`, refusing past `max-bytes`. Returns the count."
  [^InputStream in ^Path file]
  (with-open [out (Files/newOutputStream file (make-array OpenOption 0))]
    (let [buffer (byte-array 65536)]
      (loop [total 0]
        (let [n (InputStream/.read in buffer)]
          (if (neg? n)
            total
            (let [total (+ total n)]
              (when (< max-bytes total)
                (refuse 413 (str "Larger than the " max-bytes " byte limit")))
              (OutputStream/.write out buffer 0 n)
              (recur total))))))))


(defn- file-name
  "`file`'s name as its client gave it: its path beneath `dir`."
  [^Path dir ^Path file]
  (str/join "/" (map str (Path/.relativize dir file))))


(defn- entry
  "One staged file as data, or nil when it vanished mid-listing."
  [^Path dir ^Path file]
  (try
    {:name (file-name dir file)
     :path (str file)
     :size (Files/size file)
     :at   (Date. (FileTime/.toMillis (Files/getLastModifiedTime file (make-array LinkOption 0))))}
    (catch IOException _ nil)))


(defn staged
  "The regular files beneath `dir`, by name.

   Output shape:
   [{:name String, :path String, :size Long, :at Inst} …]"
  [^Path dir]
  (if (and dir (Files/isDirectory dir (make-array LinkOption 0)))
    (with-open [paths (Files/walk dir (make-array FileVisitOption 0))]
      (->> (iterator-seq (Stream/.iterator paths))
        (filter #(Files/isRegularFile % (make-array LinkOption 0)))
        (keep #(entry dir %))
        (sort-by :name)
        vec))
    []))


(defn- withdraw!
  "Take back `file` and the directories left empty above it, up to and
   including `dir`."
  [^Path dir ^Path file]
  (loop [path file]
    (when (and path (Path/.startsWith path dir)
            (try (Files/deleteIfExists path) true
              (catch IOException _ false)))
      (recur (Path/.getParent path)))))


(defn store!
  "Stage the bytes of `in` as `name` in `sid`'s upload directory, replacing
   a file already staged under that name — re-sending an edited file is the
   expected use. Written beside the directory and moved in whole, so a
   listing or a load never sees half a file. Throws `ex-info` carrying an
   HTTP `:status` when refused.

   A session can end while its bytes are still arriving, and its directory
   goes with it. The file is then withdrawn and the upload refused: nothing
   is staged for a session that is not there to list it.

   Output shape:
   {:name String, :path String, :size Long}"
  [sid ^String name ^InputStream in]
  (let [gone #(refuse 404 (str "No live session " (pr-str sid)))
        dir  (or (session/upload-dir! sid) (gone))
        dest (or (target dir name)
               (refuse 400 (str "Not a usable file name: " (pr-str name))))
        part (Files/createTempFile (Path/.getParent dir) "part-" nil (make-array FileAttribute 0))]
    (try
      (let [size (copy! in part)]
        (Files/createDirectories (Path/.getParent dest) (make-array FileAttribute 0))
        (Files/move part dest (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
        ;; Asked after the move, so that either this sees the session gone
        ;; or the session's own cleanup, which follows its row, sees the
        ;; file.
        (when-not (= dir (session/upload-dir sid))
          (withdraw! dir dest)
          (gone))
        ;; What this request put there, not what is there now: another
        ;; upload under the same name may already have replaced it.
        {:name (file-name dir dest) :path (str dest) :size size})
      (finally
        (Files/deleteIfExists part)))))
