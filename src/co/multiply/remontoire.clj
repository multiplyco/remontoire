(ns co.multiply.remontoire
  "Public entry point. Inert until `init!`."
  (:require
    [clojure.string :as str]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.tee :as tee]))


(def ^:private defaults
  {:port            7888
   ;; Loopback while bringing the connection up. Tailnet binding — the first
   ;; interface address in 100.64.0.0/10 — belongs here once there is
   ;; something worth reaching remotely (doc/design §4).
   :bind            "127.0.0.1"
   :allowed-origins #{}
   ;; Settled, delivered registry entries retained (§5); raise it if results
   ;; you reach back for report evicted.
   :capacity        registry/default-capacity
   ;; The server's self-description, woven into the connect-time intro so
   ;; the environment names itself in its own voice — e.g. "the production
   ;; system behind multiply.co" (§4). nil stays silent.
   :describe        nil})


;; Its own defonce so that reloading this namespace does not orphan a bound
;; port — the same property the registry's state gets from a namespace of
;; its own.
(defonce ^:private running (atom nil))


(defn init!
  "Start the MCP endpoint. Idempotent: a second call returns the running one
   rather than binding another."
  ([] (init! {}))
  ([opts]
   (locking running
     (-> (or @running
           (let [{:keys [port bind capacity] :as opts} (merge defaults opts)]
             ;; Before the port opens, so no request ever sees the default
             ;; when an override was given.
             (registry/capacity! capacity)
             (mcp/configure! (select-keys opts [:describe]))
             ;; Root `*out*` tee for output attribution (§11) — install is
             ;; part of init, so inert-until-init covers the root swap too.
             (tee/install!)
             (let [info {:server (http/start! opts)
                         :url    (str "http://" bind ":" port "/mcp")
                         :opts   (dissoc opts :allowed-origins)}]
               (reset! running info)
               (println "remontoire listening on" (:url info))
               info)))
       (dissoc :server)))))


(defn stop!
  "Release the endpoint. Safe to call when not running."
  []
  (locking running
    (when-let [{:keys [server url]} @running]
      (http/stop! server)
      (tee/uninstall!)
      (reset! running nil)
      (println "remontoire stopped" url)
      :stopped)))


(defn capacity!
  "Retain `n` settled, delivered registry entries (§5). Adjustable mid-run;
   `init!`'s `:capacity` is the durable setting. Raising widens the pool
   immediately. Lowering evicts nothing by itself: the excess goes at the
   next admission, so a too-hasty reduction can still be undone before
   anything is lost, and eviction stays announced where it always is — in a
   response footer."
  [n]
  (registry/capacity! n))


(defn status
  []
  (some-> @running (dissoc :server)))


(comment

  (init! {:describe "A Remontoire REPL to the Remontoire project itself"})
  (stop!)

  (filterv #(-> % :private not) (:vars (__r/outline 'co.multiply.quiescent)))
  (co.multiply.remontoire.api/vars 'capacity!)
  (co.multiply.remontoire.api/file 'co.multiply.remontoire.api)

  :-)
