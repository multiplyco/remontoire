(ns co.multiply.remontoire.api.gw
  "The fleet behind a gateway (§15), aliased `__r.gw` in a gateway's
   sessions and nowhere else. Everything `__r` offers applies on a gateway
   as on any instance; this is what a gateway has besides."
  (:require
    [co.multiply.remontoire.gateway :as gateway]))


(defn nodes
  "The fleet: this gateway first, then attached nodes, then nodes that
   went away with sessions still routed to them.

   Output shape:
   [{:name     String
     :attached Boolean
     :gateway  true    ; present only on this gateway's row
     :since    Date    ; when it attached
     :heard-s  Long    ; seconds since it was last heard from
     :version  String  ; the Remontoire it runs
     :sessions Long}   ; sessions living there
    …]"
  []
  (gateway/roster))


(defn node
  "Wait for a node to attach.

   Accepts:
   name — String, the node's name.

   Output shape:
   a task settling with the node's row from `nodes` once it is attached;
   at once when it already is. It fails when the gateway stops first."
  [name]
  (gateway/await-node name))


(defn change
  "Wait for the fleet to change.

   Accepts:
   seen — optional, the fleet as `nodes` or this function last gave it.

   Output shape:
   a task settling with the fleet, as `nodes` gives it, once it is other
   than `seen`: a node has attached, gone away or been forgotten, or
   another has attached under its name. At once when it already is.
   Without `seen`, other than it is at the call. It fails when the gateway
   stops first."
  ([] (gateway/await-change nil))
  ([seen] (gateway/await-change seen)))
