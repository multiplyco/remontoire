(ns co.multiply.remontoire.history
  "Recent nREPL text snapshots, independent of the task registry. No nREPL
   dependency or execution-status tracking; the optional middleware supplies
   already-printed strings. Writers serialize short, bounded updates.")


(def ^:private defaults
  {:enabled? false :max-entries 5 :max-chars 10000})


(defonce ^:private state
  (atom {:config defaults :entries (sorted-map) :next-id 0}))


(defn- clip
  "Keep a prefix or tail of at most limit UTF-16 code units."
  [^String text limit tail?]
  (let [length (String/.length text)
        kept (min length limit)]
    (if tail? (subs text (- length kept)) (subs text 0 kept))))


(defn- newest
  [entries limit]
  (into (sorted-map) (take limit (rseq entries))))


(defn begin!
  "Capture the start of an eval/load-file request; return its history id, or
   nil while disabled. Request maps and non-string values are never retained."
  [{:keys [op session id code file]}]
  (locking state
    (let [{:keys [config next-id] :as s} @state]
      (when (:enabled? config)
        (let [id' (inc next-id)
              input (if (= op "load-file") file code)
              input (clip (if (string? input) input "") (:max-chars config) false)
              entry {:id id' :op op :at (System/currentTimeMillis)
                     :session (when (string? session) session)
                     :request-id (when (string? id) id)
                     :input input :output ""}]
          (reset! state (assoc s :next-id id'
                          :entries (newest (assoc (:entries s) id' entry) (:max-entries config))))
          id')))))


(defn- append-output
  [entry k value limit]
  (if (and (string? value) (seq value))
    ;; Clip before concatenation: never copy a huge response just to trim it.
    (let [value (clip value limit true)]
      (assoc entry :output
        (clip (str (:output entry) "[" (name k) "] " value "\n") limit true)))
    entry))


(defn response!
  "Append printed value/out/err and exception strings to a retained snapshot.
   Status-only responses are ignored. Evicted or cleared requests cannot write
   back into history, even after recording is enabled again."
  [id response]
  (locking state
    (let [{:keys [config entries] :as s} @state]
      (when (:enabled? config)
        (when-let [entry (get entries id)]
          (let [entry (cond-> entry
                        (and (nil? (:session entry)) (string? (:session response)))
                        (assoc :session (:session response)))
                entry (reduce (fn [e k] (append-output e k (get response k) (:max-chars config)))
                        entry [:out :err :value :ex :root-ex])]
            (reset! state (assoc-in s [:entries id] entry)))))))
  nil)


(defn- status-of
  [s]
  (assoc (:config s) :entries (count (:entries s))))


(defn status
  "Capture settings and retained entry count. Character limits count UTF-16
   code units, independently for input and labeled output; not bytes or tokens."
  []
  (status-of @state))


(defn recent
  "All retained summaries, newest start first, plus capture settings. Filter
   the returned :entries in Clojure if needed. Reads never refresh retention."
  []
  (let [s @state]
    {:capture (status-of s)
     :entries (mapv #(dissoc (val %) :input :output) (rseq (:entries s)))}))


(defn transcript
  "One text snapshot, or {:id id :missing true}. History ids are not task
   registry ids. Absence of output says nothing about execution status."
  [id]
  (when-not (and (int? id) (pos? id))
    (throw (ex-info "History id must be a positive integer" {})))
  (get-in @state [:entries id] {:id id :missing true}))


(defn configure!
  "Merge :enabled?, :max-entries and :max-chars. Counts must be positive
   integers. New limits apply on subsequent starts and output appends, leaving
   untouched snapshots unchanged. Disabling recording clears history."
  [opts]
  (when-not (and (map? opts) (every? (set (keys defaults)) (keys opts)))
    (throw (ex-info "Unknown history configuration option" {})))
  (locking state
    (let [s @state
          config (merge (:config s) opts)]
      (when-not (and (boolean? (:enabled? config))
                  (every? #(and (int? %) (pos? %)) (map config [:max-entries :max-chars])))
        (throw (ex-info "Invalid history limits" {})))
      (let [s (cond-> (assoc s :config config)
                (not (:enabled? config)) (assoc :entries (sorted-map)))]
        (reset! state s)
        (status-of s)))))


(defn clear!
  "Delete snapshots without changing settings or reusing ids. Late responses
   cannot restore them. Copies already read by an agent cannot be retracted."
  []
  (locking state
    (let [s (assoc @state :entries (sorted-map))]
      (reset! state s)
      (status-of s))))
