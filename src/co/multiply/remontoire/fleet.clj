(ns co.multiply.remontoire.fleet
  "What this instance is among others (§15): nothing, by default — an
   instance alone, reached directly. A gateway is the one address a fleet
   is reached through, routing each session to the instance it lives on; a
   follower is an instance that also attaches to a gateway, under a name.

   Only the facts live here, in a namespace that requires nothing, so that
   the teaching surface — tool list, instructions, manual, scratch wiring —
   can vary by role without knowing how either role works. The roles
   themselves are `gateway` and `follower`."
  (:import
    [java.net InetAddress]))


(def wire
  "The revision of what followers and a gateway say to each other (§15).
   An attach carries the follower's, and a gateway refuses one that is not
   its own: the two agree on every message or on none, and a difference
   found later is found as a file stored wrong."
  1)


(def frame-bytes
  "The most one frame of a fetched request body holds: what a gateway
   writes at a time, and the longest a follower will take."
  65536)


;; {:role :gateway | :follower, :name str}; empty for an instance alone.
(defonce ^:private state (atom {}))


(defn default-name
  "The machine's hostname, which is what tells one development machine
   from another; `node` when the machine will not say."
  []
  (or (not-empty (System/getenv "HOSTNAME"))
    (try (not-empty (InetAddress/.getHostName (InetAddress/getLocalHost)))
      (catch Exception _ nil))
    "node"))


(defn configure!
  "Take a role — `:gateway`, `:follower` or nil — and the name this
   instance goes by among the fleet. Set at `init!`, before the port opens."
  [role name]
  (reset! state (if role {:role role :name name} {}))
  nil)


(defn role
  []
  (:role @state))


(defn node-name
  "This instance's name among the fleet, nil for an instance alone."
  []
  (:name @state))


(defn gateway?
  []
  (= :gateway (role)))
