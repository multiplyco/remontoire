(ns co.multiply.remontoire.nrepl
  "Optional nREPL middleware for transcript capture. Hosts supply nREPL; neither
   Remontoire startup nor its eval-facing toolkit loads this namespace."
  (:require
    [co.multiply.remontoire.history :as history]
    [nrepl.middleware :as middleware]
    [nrepl.middleware.load-file :as load-file]
    [nrepl.middleware.print :as print]
    [nrepl.middleware.session :as session]
    [nrepl.transport :as transport]))


(defn- observe
  [f]
  (try (f) (catch Throwable _ nil)))


(defn wrap-history
  "Capture eval/load-file code and already-printed outgoing text. Install this
   var via nREPL's middleware stack AND enable capture with
   `co.multiply.remontoire.api/nrepl-capture!`. Installation alone is inert.

   Other operations, including interrupts, are not recorded. Automated evals
   are indistinguishable from human input and are captured.

   Never evaluate or print values ourselves, retain request maps, or suppress
   handler/transport failures. Recording failures cannot break the client."
  [handler]
  (fn [{:keys [op transport] :as msg}]
    (let [history-id (when (#{"eval" "load-file"} op)
                       (observe #(history/begin! msg)))]
      (if-not history-id
        (handler msg)
        (handler
          (assoc msg :transport
            (reify transport/Transport
              (recv [_] (transport/recv transport))

              (recv [_ timeout] (transport/recv transport timeout))

              (send
                [this response]
                (observe #(history/response! history-id response))
                (transport/send transport response)
                this))))))))


;; Outer to session resolution, printing and load-file rewriting on ingress;
;; on egress this sees strings after printing and only load-file's final value.
(middleware/set-descriptor! #'wrap-history
  {:requires #{}
   :expects #{#'session/session #'print/wrap-print #'load-file/wrap-load-file}
   :handles {}})
