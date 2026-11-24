(ns co.multiply.remontoire
  "Public entry point. Inert until `init!`."
  (:require
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.tee :as tee])
  (:import
    [java.net InetAddress InetSocketAddress URI]))


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


(defn- announce
  "Say `words` on `*out*`, best effort: the endpoint has started or stopped
   whether or not there is anyone to tell."
  [& words]
  (try (apply println words)
    (catch Exception _ nil)))


(defn- url
  "Where an endpoint bound to `address` answers. Built from the address
   the server holds, never from what was asked for: `127.1` and `[::1]`
   both bind, and a literal address always makes a URL."
  [^InetSocketAddress address]
  (str (URI. "http" nil
         (InetAddress/.getHostAddress (InetSocketAddress/.getAddress address))
         (InetSocketAddress/.getPort address)
         "/mcp" nil nil)))


(defn- adopt!
  "Apply `opts` to the host — the registry's capacity, the server's
   description, the tees at the roots of `*out*` and `*err*` — and return
   a function that puts back what was there. A bad option throws with
   nothing changed."
  [{:keys [capacity] :as opts}]
  (let [capacity-was (registry/capacity)
        describe-was (:describe (mcp/probe))]
    ;; First, because it is the one that refuses a value.
    (registry/capacity! capacity)
    (mcp/configure! (select-keys opts [:describe]))
    ;; Root `*out*` tee for output attribution (§11) — install is part of
    ;; init, so inert-until-init covers the root swap too.
    (let [installed? (tee/install!)]
      (fn []
        (when installed? (tee/uninstall!))
        (mcp/configure! {:describe describe-was})
        (registry/capacity! capacity-was)))))


(defn- endpoint
  "What is kept, and reported, of a started `server`: the address it got,
   which is not the one asked for when the port was 0 or the host a name."
  [server opts]
  (let [address (http/address server)]
    {:server server
     :url    (url address)
     :opts   (-> opts (dissoc :allowed-origins) (assoc :port (InetSocketAddress/.getPort address)))}))


(defn init!
  "Start the MCP endpoint. Idempotent: a second call returns the running one
   rather than binding another. `:port 0` takes any free port, and the
   `:url` and `:opts` returned name the one it got. A start that fails — a
   bad option, the port taken — throws and leaves the host as it was."
  ([] (init! {}))
  ([opts]
   (locking running
     (-> (or @running
           (let [opts   (merge defaults opts)
                 ;; Before the port opens, so no request ever sees the
                 ;; default when an override was given.
                 undo!  (adopt! opts)
                 ;; Nothing runs when this fails, so `stop!` has nothing to
                 ;; undo: what was adopted goes back here.
                 server (try (http/start! opts)
                          (catch Throwable t
                            (undo!)
                            (throw t)))
                 ;; The same goes for a failure once it answers, which must
                 ;; not leave an endpoint that `stop!` knows nothing of.
                 info   (try (endpoint server opts)
                          (catch Throwable t
                            (http/stop! server)
                            (undo!)
                            (throw t)))]
             (reset! running info)
             (announce "remontoire listening on" (:url info))
             info))
       (dissoc :server)))))


(defn stop!
  "Release the endpoint. Safe to call when not running."
  []
  (locking running
    (when-let [{:keys [server url]} @running]
      (http/stop! server)
      (tee/uninstall!)
      (reset! running nil)
      (announce "remontoire stopped" url)
      :stopped)))


(defn capacity!
  "Retain `n` settled, delivered registry entries (§5). Adjustable mid-run;
   `init!`'s `:capacity` is the durable setting. Raising widens the pool
   immediately. Lowering evicts nothing by itself: the excess goes at the
   next admission, so a too-hasty reduction can still be undone before
   anything is lost, and eviction stays announced where it always is — in a
   response footer."
  [n]
  ;; Under `init!`'s lock: a start that fails puts the old capacity back,
  ;; and must not put it back over one set here meanwhile.
  (locking running
    (registry/capacity! n)))


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
