(ns co.multiply.remontoire
  "Public entry point. Inert until `init!`."
  (:require
    [co.multiply.remontoire.fleet :as fleet]
    [co.multiply.remontoire.follower :as follower]
    [co.multiply.remontoire.gateway :as gateway]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
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
   ;; system behind example.com" (§4). nil stays silent.
   :describe        nil
   ;; An instance among others (§15), neither by default. A gateway is the
   ;; one address a fleet is reached through; a follower attaches to the
   ;; gateway at `:follow` — its base address, "http://host:port" — and
   ;; stays reachable directly as well. Either goes by `:name` among the
   ;; fleet, the machine's hostname unless given.
   :gateway?        false
   :follow          nil
   :name            nil})


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


(defn- role-of
  "What `opts` make of this instance among others (§15): `:gateway`,
   `:follower`, or nil for an instance alone."
  [{:keys [gateway? follow] :as opts}]
  (cond
    (and gateway? follow)
    (throw (ex-info "An instance is a gateway or follows one, not both"
             (select-keys opts [:gateway? :follow])))
    gateway? :gateway
    follow :follower))


(defn- adopt!
  "Apply `opts` to the host — the registry's capacity, the server's
   description, the `role` and name it has among a fleet, the tees at the
   roots of `*out*` and `*err*` — and return a function that puts back
   what was there. A bad option throws with nothing changed."
  [role {:keys [capacity] :as opts}]
  (let [capacity-was (registry/capacity)
        describe-was (:describe (mcp/probe))
        role-was     (fleet/role)
        name-was     (fleet/node-name)]
    ;; First, because it is the one that refuses a value.
    (registry/capacity! capacity)
    (fleet/configure! role (:name opts))
    (mcp/configure! (select-keys opts [:describe]))
    ;; Root `*out*` tee for output attribution (§11) — install is part of
    ;; init, so inert-until-init covers the root swap too.
    (let [installed? (tee/install!)]
      (fn []
        (when installed? (tee/uninstall!))
        (mcp/configure! {:describe describe-was})
        (fleet/configure! role-was name-was)
        (registry/capacity! capacity-was)))))


(defn- endpoint
  "What is kept, and reported, of a started `server`: the address it got,
   which is not the one asked for when the port was 0 or the host a name."
  [server opts]
  (let [address (http/address server)]
    {:server server
     :url    (url address)
     :opts   (-> opts (dissoc :allowed-origins) (assoc :port (InetSocketAddress/.getPort address)))}))


;; The run of an instance alone that this JVM is in, as the flag its claim
;; reads: false while it lasts, true for good once it has ended. nil when
;; the JVM is in none.
(defonce ^:private alone (atom nil))


(defn- stand-alone!
  "Be an instance alone (§15) until `stand-down!`, and return what the
   transport is to be started with, `{:fleet hooks}`: the claim of the run
   that starts here. It gives out any handle while the run lasts, and
   makes no session once it has ended — asked and taken in one step with
   the ending, so nothing is opened on it after `stop!` has returned. What
   answers for the JVM's sessions by then, a gateway perhaps with routes
   of its own, was not asked."
  []
  (let [ended? (atom false)
        hooks  {:claim (fn [handle take!]
                         (locking ended?
                           (when @ended?
                             (throw (ex-info (str "The endpoint this request was sent to has stopped. "
                                               "Nothing was opened.")
                                      {:handle handle})))
                           (take!)
                           true))}]
    (reset! alone ended?)
    (session/fleet-hooks! hooks)
    {:fleet hooks}))


(defn- stand-down!
  "End the run of an instance alone. Safe when there is none."
  []
  (session/fleet-hooks! nil)
  (when-let [ended? (first (reset-vals! alone nil))]
    (locking ended?
      (reset! ended? true)))
  nil)


(defn- enlist!
  "Take up `role` (§15), and return what the transport is to be started
   with. Before the port opens: a server carries what its instance is —
   the claim its sessions are made on, and for a gateway the router and
   the door for followers of the run it was started for — from the moment
   it is made, so that no request is served as anything else, whenever it
   arrived."
  [role opts]
  (case role
    :gateway (gateway/start!)
    :follower (follower/start! opts)
    nil (stand-alone!)))


(defn- delist!
  "Give up `role`. Safe when it was never taken up."
  [role]
  (case role
    :gateway (gateway/stop!)
    :follower (follower/stop!)
    nil (stand-down!)))


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
                 role   (role-of opts)
                 opts   (cond-> opts role (update :name #(or % (fleet/default-name))))
                 ;; Before the port opens, so no request ever sees the
                 ;; default when an override was given.
                 undo!  (adopt! role opts)
                 ;; Nothing runs when this fails, so `stop!` has nothing to
                 ;; undo: what was adopted goes back here.
                 server (try (http/start! opts (enlist! role opts))
                          (catch Throwable t
                            (delist! role)
                            (undo!)
                            (throw t)))
                 ;; The same goes for a failure once it answers, which must
                 ;; not leave an endpoint that `stop!` knows nothing of. A
                 ;; follower dials only now: what the gateway sends it is
                 ;; served like anything that arrives at its own port.
                 info   (try (let [info (endpoint server opts)]
                               (when (= :follower role) (follower/attach!))
                               info)
                          (catch Throwable t
                            (http/stop! server)
                            (delist! role)
                            (undo!)
                            (throw t)))]
             (reset! running info)
             (announce "remontoire listening on"
               (str (:url info)
                 (case role
                   :gateway (str " — a gateway, as " (pr-str (:name opts)))
                   :follower (str " — following " (:follow opts) " as " (pr-str (:name opts)))
                   nil)))
             info))
       (dissoc :server)))))


(defn stop!
  "Release the endpoint. Safe to call when not running."
  []
  (locking running
    (when-let [{:keys [server url]} @running]
      ;; The port first: nothing new arrives for a role being given up.
      (http/stop! server)
      (delist! (fleet/role))
      (fleet/configure! nil nil)
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
