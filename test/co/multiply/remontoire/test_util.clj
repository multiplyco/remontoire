(ns co.multiply.remontoire.test-util
  "Shared rigging: the platform-park guard disabled (kaocha asserts on
   platform threads; the guard protects production carrier threads, not
   tests), cold-start state resets, and the dispatch-level MCP helpers on
   the legacy wire — `rpc!` emulates the transport's delivery discipline by
   invoking the `::mcp/delivered` mark after the response, as the write-out
   would."
  (:require
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]))


(q/throw-on-platform-park! false)


(defn reset-state!
  "Registry, sessions and connections back to a cold start — capacity
   included."
  []
  (reset! @#'registry/state {:next-id 1 :entries (sorted-map)})
  (reset! @#'session/state {})
  (reset! @#'session/minted #{})
  (reset! @#'session/connections {}))


(defn rpc!
  "Dispatch one message through `mcp/handle-payload` on connection `cid` —
   nil for a request carrying none — then run the response's delivery mark
   the way the transport does once the write goes out. Returns the
   response."
  [cid message]
  (let [resp (mcp/handle-payload message {:peer "test" :connection cid})]
    (when-let [mark! (::mcp/delivered (meta resp))]
      (mark!))
    resp))


(defn call!
  "A `tools/call` naming session `sid`, through `rpc!`."
  [sid tool args]
  (rpc! nil {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params  {:name tool :arguments (assoc args :session-id sid)}}))


(defn text-of
  [resp]
  (get-in resp [:result :content 0 :text]))


(defn tool-error?
  [resp]
  (get-in resp [:result :isError]))


(defn id-of
  "The registry id a response footer names."
  [resp]
  (some-> (re-find #";; id (\d+)" (text-of resp)) second parse-long))
