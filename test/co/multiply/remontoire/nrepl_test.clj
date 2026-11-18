(ns co.multiply.remontoire.nrepl-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [co.multiply.remontoire.api :as api]
    [co.multiply.remontoire.history :as history]
    [co.multiply.remontoire.history-test :refer [isolated-history]]
    [co.multiply.remontoire.nrepl :as recorder]
    [nrepl.core :as nrepl]
    [nrepl.server :as server]
    [nrepl.transport :as transport]))


(use-fixtures :each isolated-history)


(defn- with-server
  [f]
  (with-open [server (server/start-server :port 0 :bind "127.0.0.1"
                       :handler (server/default-handler #'recorder/wrap-history))
              connection (nrepl/connect :port (:port server))]
    (let [client (nrepl/client connection 5000)
          session (:new-session (first (doall (nrepl/message client {:op "clone"}))))]
      (try (f client session connection)
        (finally (doall (nrepl/message client {:op "close" :session session})))))))


(defn- request
  [client session msg]
  (vec (nrepl/message client (assoc msg :session session))))


(defn- latest
  []
  (some-> (api/nrepl-history) :entries first :id api/nrepl-transcript))


(deftest real-stack-preserves-client-output-and-captures-printed-text
  (with-server
    (fn [client session _]
      (let [code "(println \"out\") (binding [*out* *err*] (println \"err\")) (+ 1 2)"
            responses (request client session {:op "eval" :id "human-eval" :code code})
            recorded (latest)]
        (is (= session (:session recorded)))
        (is (= "human-eval" (:request-id recorded)))
        (is (= code (:input recorded)))
        (is (= "3" (last (keep :value responses))))
        (doseq [key [:value :out :err]
                text (keep key responses)]
          (is (str/includes? (:output recorded) text)))
        (is (not-any? (set (keys recorded)) [:status :events]))))))


(def printer-calls (atom 0))


(defn counting-printer
  [_ writer _]
  (swap! printer-calls inc)
  (java.io.Writer/.write writer "custom-printed-value"))


(deftest configured-printer-is-used-exactly-once
  (with-redefs [printer-calls (atom 0)]
    (with-server
      (fn [client session _]
        (let [responses (request client session
                          {:op "eval" :code "{:answer 42}"
                           :nrepl.middleware.print/print
                           "co.multiply.remontoire.nrepl-test/counting-printer"})]
          (is (= 1 @printer-calls))
          (is (= ["custom-printed-value"] (vec (keep :value responses))))
          (is (str/includes? (:output (latest)) "custom-printed-value")))))))


(deftest load-file-is-one-original-request-with-only-the-final-result
  (with-server
    (fn [client session _]
      (let [code "(ns history.example)\n\"intermediate-marker\"\n\"final-marker\""
            responses (request client session {:op "load-file" :file code
                                               :file-path "history/example.clj"
                                               :file-name "example.clj"})
            recorded (latest)]
        (is (= 1 (count (:entries (api/nrepl-history)))))
        (is (= "load-file" (:op recorded)))
        (is (= code (:input recorded)))
        (is (= ["\"final-marker\""] (vec (keep :value responses))))
        (is (str/includes? (:output recorded) "final-marker"))
        (is (not (str/includes? (:output recorded) "intermediate-marker"))))
      (remove-ns 'history.example))))


(deftest eval-errors-are-text-not-inferred-status
  (with-server
    (fn [client session _]
      (let [responses (request client session {:op "eval" :code "(/ 1 0)"})
            output (:output (latest))]
        (is (some :ex responses))
        (is (some :err responses))
        (doseq [key [:err :ex :root-ex]
                text (keep key responses)]
          (is (str/includes? output text)))
        (is (not (contains? (latest) :status))))
      ;; Protocol statuses still reach the client, but are not transcript text.
      (let [responses (request client "no-such-session" {:op "eval" :code "1"})]
        (is (some #(some #{"error"} (:status %)) responses))
        (is (= "" (:output (latest))))))))


(deftest printer-runs-only-once-and-streamed-chunks-are-captured
  (with-server
    (fn [client session _]
      ;; nREPL itself realizes the lazy result. Capturing may not do so again.
      (let [responses (request client session
                        {:op "eval"
                         :code "(map (fn [x] (println \"realized\") x) (range 3))"
                         :nrepl.middleware.print/stream? 1
                         :nrepl.middleware.print/buffer-size 2})
            output (:output (latest))]
        (is (= "(0 1 2)" (apply str (keep :value responses))))
        (doseq [value (keep :value responses)]
          (is (str/includes? output value)))
        (is (= 3 (count (re-seq #"realized" (apply str (keep :out responses))))))
        (is (= 3 (count (re-seq #"realized" output))))))))


(deftest truncation-does-not-change-what-the-client-receives
  (api/nrepl-capture! {:max-chars 16})
  (with-server
    (fn [client session _]
      (let [code "(do (print (apply str (repeat 1000 \"x\"))) (flush) \"last-result\")"
            responses (request client session {:op "eval" :code code})
            recorded (latest)]
        (is (= (apply str (repeat 1000 "x")) (apply str (keep :out responses))))
        (is (= ["\"last-result\""] (vec (keep :value responses))))
        (is (= (subs code 0 16) (:input recorded)))
        (is (<= (count (:output recorded)) 16))
        (is (str/includes? (:output recorded) "last-result"))))))


(deftest non-evaluation-operations-do-not-enter-history
  (with-server
    (fn [client session _]
      (request client session {:op "describe"})
      (request client session {:op "lookup" :sym "map" :ns "clojure.core"})
      (request client session {:op "completions" :prefix "ma" :ns "clojure.core"})
      (is (empty? (:entries (api/nrepl-history)))))))


(deftest disabled-capture-does-not-affect-evaluation
  (api/nrepl-capture! {:enabled? false})
  (with-server
    (fn [client session _]
      (is (= ["3"] (vec (keep :value (request client session {:op "eval" :code "(+ 1 2)"})))))
      (is (empty? (:entries (api/nrepl-history))))
      (api/nrepl-capture! {:enabled? true})
      (request client session {:op "eval" :code "4"})
      (is (= "4" (:input (latest)))))))


(deftest running-output-is-readable-and-interrupts-do-not-create-entries
  (with-server
    (fn [client session connection]
      (transport/send connection {:op "eval" :id "waiting" :session session
                                  :code "(do (println \"working\") (flush) (Thread/sleep 30000))"})
      (let [first-response (transport/recv connection 5000)]
        (is (= "working\n" (:out first-response)))
        (is (str/includes? (:output (latest)) "working\n")))
      (request client session {:op "interrupt" :interrupt-id "waiting"})
      (is (= "waiting" (:request-id (latest))))
      (is (not (contains? (latest) :status)))
      (is (= 1 (count (:entries (api/nrepl-history))))))))


(deftest failing-recorder-does-not-affect-the-real-client
  (with-server
    (fn [client session _]
      (with-redefs [history/begin! (fn [_] (throw (Exception. "capture failed")))]
        (is (= ["3"] (vec (keep :value (request client session {:op "eval" :code "3"}))))))
      (with-redefs [history/response! (fn [& _] (throw (Exception. "capture failed")))]
        (is (= ["4"] (vec (keep :value (request client session {:op "eval" :code "4"})))))))))


(deftest middleware-does-not-realize-or-print-unexpected-response-objects
  (let [touched (atom false)
        value (lazy-seq (reset! touched true) (throw (Exception. "do not realize")))
        sent (atom nil)
        wire (reify transport/Transport
               (recv [_] :recv)

               (recv [_ timeout] timeout)

               (send [this response] (reset! sent response) this))
        handler (recorder/wrap-history
                  (fn [{:keys [transport]}]
                    (is (= :recv (transport/recv transport)))
                    (is (= 123 (transport/recv transport 123)))
                    (transport/send transport {:value value :status #{:done}})))]
    (handler {:op "eval" :code "1" :transport wire})
    (is (identical? value (:value @sent)))
    (is (false? @touched))
    (is (= "" (:output (latest))))))


(deftest handler-and-transport-failures-are-not-swallowed
  (let [failure (Exception. "original failure")
        handler (recorder/wrap-history (fn [_] (throw failure)))]
    (is (identical? failure
          (try (handler {:op "eval" :code "1"})
            (catch Exception e e)))))
  (let [failure (Exception. "transport failure")
        wire (reify transport/Transport
               (recv [_] nil)

               (recv [_ _] nil)

               (send [_ _] (throw failure)))
        handler (recorder/wrap-history
                  (fn [{:keys [transport]}] (transport/send transport {:value "1"})))]
    (is (identical? failure
          (try (handler {:op "eval" :code "1" :transport wire})
            (catch Exception e e))))))
