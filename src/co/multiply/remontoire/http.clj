(ns co.multiply.remontoire.http
  "The Streamable HTTP transport, on the JDK's own HTTP server.

   The MCP endpoint is `/mcp`, POST only. Quick protocol methods are answered
   with plain JSON; a `tools/call` gets an SSE stream — opened immediately,
   heartbeated with progress while the work runs, closed after its one
   response message (§4). Nothing here initiates messages, so there is no GET
   stream to offer and GET is 405 — news reaches the model by riding
   responses instead (§5). Beside it, `/probe` is a sessionless GET answering
   the server's identity for discovery and health checks (§11)."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [co.multiply.quiescent.impl.executor :as q.exec]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee])
  (:import
    [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
    [java.io IOException OutputStream]
    [java.net InetSocketAddress]
    [java.nio.charset StandardCharsets]))


(def ^:private json-content-type "application/json; charset=utf-8")


(def ^:private sse-content-type "text/event-stream; charset=utf-8")


(def ^:private heartbeat-ms
  "The longest an open stream stays quiet — the keepalive cadence for a
   silent eval: the client's idle timer wants messages, and a failed beat
   is the only way the JDK server ever learns the client hung up."
  15000)


(def ^:private poll-ms
  "How often an open stream checks the bank for fresh output — the drain
   latency: a `println` reaches the progress display within about a second
   of being written (§4), rather than waiting for the next keepalive."
  1000)


(defn- encode
  "data.json escapes both `/` and every non-ASCII character by default. Both are
   valid JSON and neither is necessary over a UTF-8 body — and a project whose
   premise is that escaping layers eat backslashes should not add a layer of its
   own for decoration (§1)."
  [x]
  (json/write-str x :escape-slash false :escape-unicode false))


(defn- header
  [^HttpExchange exchange ^String k]
  (.getFirst (.getRequestHeaders exchange) k))


(defn- session!
  "Establish the request's session (§11). An `initialize` is a birth: a
   fresh id is minted and set on the response header, any id the request
   presented deliberately ignored — the client's own reconnects arrive
   bare (measured 2026-08-04). Anything else refreshes the id it carries —
   unknown ids are adopted (a restarted server; see the session ns) — or
   returns nil for a request carrying none, which `serve!` 400s: every
   client initializes before anything else, so a bare request is a broken
   one, and the loud refusal replaced a shared id-less identity that would
   have silently pooled strangers' scratch and claims (2026-08-06, §11)."
  [^HttpExchange exchange payload]
  (let [init? (let [im? #(= "initialize" (:method %))]
                (boolean (if (sequential? payload) (some im? payload) (im? payload))))]
    (if init?
      (let [sid (session/mint!)]
        (.set (.getResponseHeaders exchange) "Mcp-Session-Id" sid)
        sid)
      (some-> (header exchange "Mcp-Session-Id") session/seen!))))


(defn- origin-allowed?
  "Non-browser clients send no Origin at all; browsers always do. So absence is
   the normal case and presence is the one to check. Nothing is allowed by
   default, and no CORS headers are ever emitted — §4's failure mode is an
   Access-Control-Allow-Origin added later to make some browser test pass."
  [exchange allowed]
  (let [origin (header exchange "Origin")]
    (or (nil? origin) (contains? allowed origin))))


(defn- mark-delivered!
  "The response reached the client — only now do the delivery and told-once
   marks land (§5)."
  [response]
  (doseq [r (if (sequential? response) response [response])]
    (when-let [mark! (::mcp/delivered (meta r))]
      (mark!))))


(defn- accepts-sse?
  [exchange]
  (some-> (header exchange "Accept") (str/includes? "text/event-stream")))


(defn- wants-stream?
  "SSE is reserved for `tools/call`, the only method that waits — the quick
   protocol methods stay on the plain JSON path, so a client quirk in SSE
   handling can never break the handshake."
  [payload]
  (let [call? #(= "tools/call" (:method %))]
    (boolean (if (sequential? payload) (some call? payload) (call? payload)))))


(defn- write!
  "Heartbeat and message share the stream; the lock keeps frames whole. The
   flush matters twice over: chunked encoding buffers, and an unflushed
   heartbeat neither feeds the client's idle timer nor detects a hangup."
  [^OutputStream out ^String s]
  (locking out
    (.write out (.getBytes s StandardCharsets/UTF_8))
    (.flush out)))


(defn- beat-frame
  "One heartbeat: a `notifications/progress` message when the client
   attached a token — its idle timer counts MCP messages, not bytes
   (measured 2026-08-04: comment beats fed TCP but the client still aborted
   at 300 s of \"no response or progress\") — and a bare SSE comment
   otherwise, which at least makes a hangup noticeable to the server as a
   failed write."
  [token n message]
  (if token
    (str "data: "
      (encode {:jsonrpc "2.0"
               :method  "notifications/progress"
               :params  {:progressToken token
                         :progress      n
                         :message       message}})
      "\n\n")
    ": hb\n\n"))


(defn- heartbeat!
  "Two-speed narration on `out` until `done` delivers or a write fails.
   The loop polls the bank every `poll-ms` and beats the moment there is
   fresh output — every line since the last beat, untrimmed and all of
   them: the client renders the message verbatim and truncates its own
   display (observed 2026-08-04), and a server that pre-shortens for a UI
   it does not own destroys information twice. A silent eval gets a plain
   elapsed-time beat each `heartbeat-ms` instead, feeding the client's idle
   timer. The cursor advances only on an emitted beat, so nothing is ever
   dropped between beats."
  [out done token sid display]
  (Thread/startVirtualThread
    (fn []
      (loop [n 1 cursor 0 polls 0 idle 0]
        (when (= ::beat (deref done poll-ms ::beat))
          (let [polls' (inc polls)
                secs   (quot (* polls' poll-ms) 1000)
                [fresh total] (if-let [bank @display]
                                (tee/since bank cursor)
                                ["" cursor])
                said   (->> (str/split-lines fresh)
                         (remove str/blank?)
                         (str/join "\n"))
                emit!  (fn [message]
                         (try
                           (write! out (beat-frame token n message))
                           ;; A successful write is proof the client
                           ;; is still there: open streams are
                           ;; liveness (§11), so a session mid-await
                           ;; can never expire under its own wait.
                           (session/seen! sid)
                           true
                           (catch IOException _ false)))]
            (cond
              (seq said)
              (when (emit! (str said " · " secs " s"))
                (recur (inc n) total polls' 0))

              (>= (+ idle poll-ms) heartbeat-ms)
              (when (emit! (str "evaluating · " secs " s"))
                (recur (inc n) cursor polls' 0))

              :else
              (recur n cursor polls' (+ idle poll-ms)))))))))


(defn- respond-sse!
  "Answer with an SSE stream: headers and a comment immediately — the
   client's per-request timer wants a first byte within 60 s (§5) — then
   heartbeats while `compute` runs, then the response as the stream's one
   message, then close, which is the client's cue that the call is done.
   Returns the response when its write went out, nil when the client was
   gone — the caller marks delivery only on the former."
  [^HttpExchange exchange progress-token sid display compute]
  (.set (.getResponseHeaders exchange) "Content-Type" sse-content-type)
  ;; 0 = chunked: the stream stays open until the exchange closes.
  (.sendResponseHeaders exchange 200 0)
  (let [out  (.getResponseBody exchange)
        done (promise)]
    (write! out ": open\n\n")
    (heartbeat! out done progress-token sid display)
    (let [response (try (compute) (finally (deliver done ::done)))]
      (try
        ;; The one server-initiated notification this transport speaks, and
        ;; it rides a stream already open rather than a GET channel it does
        ;; not offer (§4): told the tool set moved, the client re-fetches
        ;; promptly (measured 2026-08-04). Announced ahead of the response
        ;; and re-announced each stream until a `tools/list` actually lands
        ;; — losing one costs nothing.
        (when (mcp/tools-changed? sid)
          (write! out (str "data: "
                        (encode {:jsonrpc "2.0"
                                 :method  "notifications/tools/list_changed"})
                        "\n\n")))
        (write! out (str "data: " (encode response) "\n\n"))
        ;; One more frame after the message: a final write can slip into
        ;; the send buffer of a socket the client already abandoned, and
        ;; marking on it burned an announcement once (measured 2026-08-04).
        ;; The probe draws the reset; delivery counts only if both writes
        ;; survive.
        (write! out ": end\n\n")
        response
        (catch IOException _ nil)))))


(defn- progress-token
  "The token the client attached to a request, when it did — only its
   presence authorizes progress notifications (MCP spec)."
  [payload]
  (let [tok #(get-in % [:params :_meta :progressToken])]
    (if (sequential? payload) (some tok payload) (tok payload))))


(defn- respond!
  [^HttpExchange exchange status content-type ^String body]
  (let [bytes (when body (.getBytes body StandardCharsets/UTF_8))]
    (when content-type
      (.set (.getResponseHeaders exchange) "Content-Type" content-type))
    ;; -1 means "no body"; 0 would mean chunked, which is how respond-sse!
    ;; holds its stream open.
    (.sendResponseHeaders exchange status (if bytes (alength bytes) -1))
    (when bytes
      (with-open [out (.getResponseBody exchange)]
        (.write out bytes)))))


(defn- serve!
  "Parse and dispatch one POST body, choosing the response's shape: SSE when
   the client accepts it and the payload waits (`tools/call`), plain JSON
   otherwise. Either way, delivery marks land only after the write that
   carried them went out (§5)."
  [^HttpExchange exchange]
  (let [payload (json/read-str (slurp (.getRequestBody exchange)
                                 :encoding "UTF-8")
                  :key-fn keyword)
        sid     (session! exchange payload)
        ;; getHostString, not getAddress: the string form without a reverse
        ;; DNS lookup. The port is ephemeral and omitted — tailnet addresses
        ;; are stable per device (§4), the host is the identity.
        ctx     {:peer    (.getHostString (.getRemoteAddress exchange))
                 :session sid}]
    (cond
      (nil? sid)
      (respond! exchange 400 "text/plain"
        "Mcp-Session-Id required — initialize first")

      (and (accepts-sse? exchange) (wants-stream? payload))
      ;; The display cell exists before the eval does — the heartbeat loop
      ;; starts first — so the handler fills it in once the bank is minted.
      (let [display (atom nil)]
        (some-> (respond-sse! exchange (progress-token payload) sid display
                  #(mcp/handle-payload payload (assoc ctx :display display)))
          (mark-delivered!)))

      :else
      (let [response (mcp/handle-payload payload ctx)]
        (if response
          (do
            (respond! exchange 200 json-content-type (encode response))
            ;; A client that timed out threw above instead, leaving its
            ;; entry owed, protected and announceable.
            (mark-delivered! response))
          ;; Notifications only: accepted, nothing to say back.
          (respond! exchange 202 nil nil))))))


(defn- handle-exchange!
  [^HttpExchange exchange allowed-origins]
  (try
    (cond
      (not (origin-allowed? exchange allowed-origins))
      (respond! exchange 403 "text/plain" "Forbidden origin")

      ;; The spec's session goodbye — never observed from claude-code,
      ;; whose reconnects abandon their old session in silence (measured
      ;; 2026-08-04), but honored: the session's claims lapse now rather
      ;; than at expiry (§11). GET — the client offering to open a
      ;; server-initiated stream this transport does not speak (§4) —
      ;; falls through to the 405 below, which it tolerates.
      (= "DELETE" (.getRequestMethod exchange))
      (if-let [sid (header exchange "Mcp-Session-Id")]
        (do (registry/release-session! sid)
          (respond! exchange 204 nil nil))
        (respond! exchange 400 "text/plain" "Mcp-Session-Id required"))

      (not= "POST" (.getRequestMethod exchange))
      (respond! exchange 405 "text/plain" "Method not allowed")

      :else (serve! exchange))
    (catch Throwable t
      (respond! exchange 400 json-content-type
        (encode {:jsonrpc "2.0"
                 :id      nil
                 :error   {:code    -32700
                           :message (str (.getName (class t)) ": " (ex-message t))}})))
    (finally
      (.close exchange))))


(defn- handle-probe!
  "The sessionless door: identity as plain JSON — no session minted, no
   registry or session state touched. Discovery and health probes arrive
   from several operators, several times a day, against every environment;
   answering them through `initialize` would fill the roster with ghost
   sessions, polluting exactly the surface built for tenant mutual
   awareness (§11). Same origin policy as `/mcp`; GET only, the whole
   point being a curl-friendly read."
  [^HttpExchange exchange allowed-origins]
  (try
    (cond
      (not (origin-allowed? exchange allowed-origins))
      (respond! exchange 403 "text/plain" "Forbidden origin")

      (not= "GET" (.getRequestMethod exchange))
      (respond! exchange 405 "text/plain" "Method not allowed")

      :else
      (respond! exchange 200 json-content-type (encode (mcp/probe))))
    (finally
      (.close exchange))))


(defn- handler
  "A shell that delegates through a handler var: the reified instance is
   compiled once at `start!`, and logic inlined in its body is frozen until
   a context swap — a reload that redefines a var it calls through, by
   contrast, takes effect immediately (learned 2026-08-04)."
  ^HttpHandler [delegate {:keys [allowed-origins]}]
  (reify HttpHandler
    (handle
      [_ exchange]
      (delegate exchange allowed-origins))))


(defn start!
  "Bind and start. Returns the HttpServer."
  ^HttpServer [{:keys [port bind allowed-origins]}]
  (doto (HttpServer/create (InetSocketAddress. ^String bind (int port)) 0)
    (.createContext "/mcp" (handler #'handle-exchange!
                             {:allowed-origins (set allowed-origins)}))
    (.createContext "/probe" (handler #'handle-probe!
                               {:allowed-origins (set allowed-origins)}))
    ;; The default executor runs every exchange serially on the dispatch
    ;; thread, which deadlocks on the second connection. Quiescent's own
    ;; executor rather than a fresh virtual one: handler threads then carry
    ;; the same classloader as every task thread, and a task constructed in
    ;; a handler is rooted, since the handler is not itself a task.
    (.setExecutor q.exec/virtual-executor)
    (.start)))


(defn stop!
  [^HttpServer server]
  (.stop server 0))
