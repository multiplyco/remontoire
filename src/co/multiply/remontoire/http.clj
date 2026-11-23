(ns co.multiply.remontoire.http
  "The Streamable HTTP transport, on the JDK's own HTTP server.

   The MCP endpoint is `/mcp`, POST only. Quick protocol methods are answered
   with plain JSON; a `tools/call` gets an SSE stream — opened immediately,
   heartbeated with progress while the work runs, closed after its one
   response message (§4). Nothing here initiates messages, so there is no GET
   stream to offer and GET is 405 — news reaches the model by riding
   responses instead (§5). Beside it, `/probe` is a sessionless GET answering
   the server's identity for discovery and health checks (§11), and
   `/upload/<session-id>/<name>` takes a file by PUT into that session's
   upload directory."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [co.multiply.quiescent.impl.executor :as q.exec]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.remontoire.upload :as upload])
  (:import
    [clojure.lang ExceptionInfo]
    [com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer]
    [java.io IOException OutputStream]
    [java.net InetSocketAddress URI]
    [java.nio.charset StandardCharsets]
    [java.util Base64 Base64$Decoder]))


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
  (json/write-str x :escape-slash false :escape-unicode false
    ;; data.json writes a keyword key by its `name`, dropping the namespace
    ;; the stateless era's `_meta` keys live by
    ;; (`io.modelcontextprotocol/serverInfo`).
    :key-fn #(if (keyword? %) (subs (str %) 1) (str %))))


(defn- header
  [exchange ^String k]
  (Headers/.getFirst (HttpExchange/.getRequestHeaders exchange) k))


(defn- connection!
  "The legacy wire's connection for this request (§11). An `initialize`
   opens one: a fresh id is minted and set on the response header, any id
   the request presented deliberately ignored — the client's own
   reconnects arrive bare (measured 2026-08-04). Anything else refreshes
   the id it carries, known or not, and a request carrying none has no
   connection, which costs it only the tool-list announcement. No session
   hangs off the id: a tool call names its own."
  [exchange payload]
  (let [init? (let [im? #(= "initialize" (:method %))]
                (boolean (if (sequential? payload) (some im? payload) (im? payload))))]
    (if init?
      (let [cid (session/connect!)]
        (Headers/.set (HttpExchange/.getResponseHeaders exchange) "Mcp-Session-Id" cid)
        cid)
      (some-> (header exchange "Mcp-Session-Id") session/connected!))))


(defn- named-sessions
  "The sessions a payload's tool calls name in their arguments, each once
   — a batch may name several. Unchecked: what they are used for must not
   mint."
  [payload]
  (into []
    (comp (filter map?) (keep #(get-in % [:params :arguments :session-id])) (distinct))
    (if (sequential? payload) payload [payload])))


(defn- note-address!
  "Record how the client addressed this request, for `sid`: the `Host` it
   named, or what a proxy in front says the client named. A guess at the
   server's outside address, which the server cannot know — good on a
   direct connection, plausible behind a proxy that forwards the original
   host, and wrong behind one that does neither."
  [exchange sid]
  (let [forwarded #(some-> (header exchange %) (str/split #",") first str/trim not-empty)]
    (when-let [host (or (forwarded "X-Forwarded-Host") (header exchange "Host"))]
      (session/address! sid (str (or (forwarded "X-Forwarded-Proto") "http") "://" host)))))


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
  [out ^String s]
  (locking out
    (OutputStream/.write out (String/.getBytes s StandardCharsets/UTF_8))
    (OutputStream/.flush out)))


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
   dropped between beats. Every session in `sids` is kept fresh by a beat:
   a batch's later calls wait behind its earlier ones on this one stream."
  [out done token sids display]
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
                           ;; Guarded: a sid is a tool argument,
                           ;; and a beat must never mint the session
                           ;; a refused handle names.
                           (run! session/refresh! sids)
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
   gone — the caller marks delivery only on the former. `changed?`, when
   given, is asked whether the client's tool list is stale; the stateless
   wire gives none, having `ttlMs` on the list itself."
  [exchange progress-token sids changed? display compute]
  (Headers/.set (HttpExchange/.getResponseHeaders exchange) "Content-Type" sse-content-type)
  ;; 0 = chunked: the stream stays open until the exchange closes.
  (HttpExchange/.sendResponseHeaders exchange 200 0)
  (let [out  (HttpExchange/.getResponseBody exchange)
        done (promise)]
    (write! out ": open\n\n")
    (heartbeat! out done progress-token sids display)
    (let [response (try (compute) (finally (deliver done ::done)))]
      (try
        ;; The one server-initiated notification this transport speaks, and
        ;; it rides a stream already open rather than a GET channel it does
        ;; not offer (§4): told the tool set moved, the client re-fetches
        ;; promptly (measured 2026-08-04). Announced ahead of the response
        ;; and re-announced each stream until a `tools/list` actually lands
        ;; — losing one costs nothing.
        (when (and changed? (changed?))
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
  [exchange status content-type ^String body]
  (let [bytes (when body (String/.getBytes body StandardCharsets/UTF_8))]
    (when content-type
      (Headers/.set (HttpExchange/.getResponseHeaders exchange) "Content-Type" content-type))
    ;; -1 means "no body"; 0 would mean chunked, which is how respond-sse!
    ;; holds its stream open.
    (HttpExchange/.sendResponseHeaders exchange status (if bytes (alength bytes) -1))
    (when bytes
      (with-open [out (HttpExchange/.getResponseBody exchange)]
        (OutputStream/.write out bytes)))))


(defn- decode-header-value
  "Undo the spec's `=?base64?…?=` sentinel for a header value that could not
   ride as plain ASCII (2026-07-28)."
  [^String v]
  (if (and v (str/starts-with? v "=?base64?") (str/ends-with? v "?=") (<= 11 (count v)))
    (try
      (let [^String encoded (subs v 9 (- (count v) 2))]
        (String. ^bytes (Base64$Decoder/.decode (Base64/getDecoder) encoded) StandardCharsets/UTF_8))
      (catch IllegalArgumentException _ nil))
    v))


(defn- modern-refusal
  "Why a stateless request is malformed at the HTTP binding, as
   `[code message]`, or nil. The headers restate the body so intermediaries
   can route without parsing it, and a mismatch is refused (2026-07-28).
   Notifications pass unchecked: nothing answers them either way."
  [exchange payload]
  (let [{:keys [id method params]} (when (map? payload) payload)
        mcp-name (decode-header-value (header exchange "Mcp-Name"))]
    (cond
      (not (map? payload))
      [-32600 "A stateless request is a single JSON-RPC message."]

      (nil? id) nil

      (not= mcp/protocol-version (get-in params [:_meta :io.modelcontextprotocol/protocolVersion]))
      [-32020 "Header mismatch: MCP-Protocol-Version header does not match _meta protocolVersion."]

      (not= method (header exchange "Mcp-Method"))
      [-32020 "Header mismatch: Mcp-Method header does not match the body's method."]

      (and (= "tools/call" method) (not= mcp-name (:name params)))
      [-32020 "Header mismatch: Mcp-Name header must carry the tool name."]

      (and (#{"tasks/get" "tasks/update" "tasks/cancel"} method) (not= mcp-name (:taskId params)))
      [-32020 "Header mismatch: Mcp-Name header must carry the request's taskId."])))


(defn- answer!
  "Answer `payload` with what `dispatch` makes of it, on either wire: an
   SSE stream for a `tools/call` when the client accepts one, plain JSON
   otherwise, 202 when only notifications came. Delivery marks land only
   after the write that carried them (§5). The sessions the calls name are
   the ones its heartbeats keep fresh, and the ones told how they were
   addressed. `dispatch` takes what the transport adds to the context."
  [exchange payload changed? dispatch]
  (let [sids (named-sessions payload)]
    (run! #(note-address! exchange %) sids)
    ;; Heard from on arrival, not at its own call's turn: a batch's later
    ;; calls wait behind its earlier ones, whose admissions sweep the silent.
    (run! session/refresh! sids)
    (if (and (accepts-sse? exchange) (wants-stream? payload))
      ;; The display cell exists before the eval does — the heartbeat loop
      ;; starts first — so the handler fills it in once the bank is minted.
      (let [display (atom nil)]
        (some-> (respond-sse! exchange (progress-token payload) sids changed? display
                  #(dispatch {:display display}))
          (mark-delivered!)))
      (if-let [response (dispatch {})]
        (do (respond! exchange 200 json-content-type (encode response))
          ;; A client that timed out threw above instead, leaving its
          ;; entry owed, protected and announceable.
          (mark-delivered! response))
        ;; Notifications only: accepted, nothing to say back.
        (respond! exchange 202 nil nil)))))


(defn- serve-modern!
  "A stateless-wire request (§11): nothing but its own headers to check."
  [exchange payload peer]
  (if-let [[code message] (modern-refusal exchange payload)]
    (respond! exchange 400 json-content-type
      (encode {:jsonrpc "2.0"
               :id      (when (map? payload) (:id payload))
               :error   {:code code :message message}}))
    (answer! exchange payload nil
      #(mcp/handle-modern payload (assoc % :peer peer)))))


(defn- serve-legacy!
  "A legacy-wire request: its connection rides `Mcp-Session-Id`, opened at
   `initialize`, and is what a stale tool list is announced to."
  [exchange payload peer]
  (let [cid (connection! exchange payload)]
    (answer! exchange payload (when cid #(mcp/tools-changed? cid))
      #(mcp/handle-payload payload (assoc % :peer peer :connection cid)))))


(defn- serve!
  "Parse one POST body and serve it on its wire — stateless when the
   `MCP-Protocol-Version` header names the 2026-07-28 revision, legacy
   otherwise; both on one endpoint, which the revision blesses (§11)."
  [exchange]
  (let [payload (json/read-str (slurp (HttpExchange/.getRequestBody exchange)
                                 :encoding "UTF-8")
                  :key-fn keyword)
        ;; getHostString, not getAddress: the string form without a reverse
        ;; DNS lookup. The port is ephemeral and omitted — tailnet addresses
        ;; are stable per device (§4), the host is the identity.
        peer    (InetSocketAddress/.getHostString
                  (HttpExchange/.getRemoteAddress exchange))]
    (if (= mcp/protocol-version (header exchange "MCP-Protocol-Version"))
      (serve-modern! exchange payload peer)
      (serve-legacy! exchange payload peer))))


(defn- handle-exchange!
  [exchange allowed-origins]
  (try
    (cond
      (not (origin-allowed? exchange allowed-origins))
      (respond! exchange 403 "text/plain" "Forbidden origin")

      ;; The legacy wire's goodbye — never observed from claude-code,
      ;; whose reconnects abandon their old connection in silence
      ;; (measured 2026-08-04), but honored: the connection is forgotten.
      ;; Sessions are not its to end (§11). GET — the client offering to
      ;; open a server-initiated stream this transport does not speak (§4)
      ;; — falls through to the 405 below, which it tolerates.
      (= "DELETE" (HttpExchange/.getRequestMethod exchange))
      (if-let [cid (header exchange "Mcp-Session-Id")]
        (do (session/disconnect! cid)
          (respond! exchange 204 nil nil))
        (respond! exchange 400 "text/plain" "Mcp-Session-Id required"))

      (not= "POST" (HttpExchange/.getRequestMethod exchange))
      (respond! exchange 405 "text/plain" "Method not allowed")

      :else (serve! exchange))
    (catch Throwable t
      (respond! exchange 400 json-content-type
        (encode {:jsonrpc "2.0"
                 :id      nil
                 :error   {:code    -32700
                           :message (str (Class/.getName (class t)) ": " (ex-message t))}})))
    (finally
      (HttpExchange/.close exchange))))


(defn- handle-probe!
  "The sessionless door: identity as plain JSON — nothing minted, no
   registry or session state touched — for the discovery and health
   probes that arrive from several operators, several times a day (§11).
   Same origin policy as `/mcp`; GET only, the whole point being a
   curl-friendly read."
  [exchange allowed-origins]
  (try
    (cond
      (not (origin-allowed? exchange allowed-origins))
      (respond! exchange 403 "text/plain" "Forbidden origin")

      (not= "GET" (HttpExchange/.getRequestMethod exchange))
      (respond! exchange 405 "text/plain" "Method not allowed")

      :else
      (respond! exchange 200 json-content-type (encode (mcp/probe))))
    (finally
      (HttpExchange/.close exchange))))


(defn- handle-upload!
  "The file door: `PUT /upload/<session-id>/<name>` stages the request body
   as `name` in that session's upload directory and answers where it landed
   — the JVM's own absolute path, ready for `load-file`. Bytes arrive here
   rather than as a tool argument so that a file a client can send from its
   shell never has to pass through the model. Same origin policy as `/mcp`,
   and no wider a door: `eval` beside it already writes any file it likes.
   An upload is liveness, like a heartbeat, but never mints the session it
   names."
  [exchange allowed-origins]
  (try
    (cond
      (not (origin-allowed? exchange allowed-origins))
      (respond! exchange 403 "text/plain" "Forbidden origin")

      (not= "PUT" (HttpExchange/.getRequestMethod exchange))
      (respond! exchange 405 "text/plain" "Method not allowed")

      :else
      (let [[_ sid name] (re-matches #"/upload/([^/]+)/(.+)"
                           (URI/.getPath (HttpExchange/.getRequestURI exchange)))
            declared     (some-> (header exchange "Content-Length") parse-long)]
        (cond
          (nil? sid)
          (respond! exchange 400 "text/plain" "PUT /upload/<session-id>/<name>\n")

          ;; Refused before a byte is read when the client says how much is
          ;; coming; `store!` enforces the same bound on what actually arrives.
          (and declared (< upload/max-bytes declared))
          (respond! exchange 413 "text/plain"
            (str "Larger than the " upload/max-bytes " byte limit\n"))

          :else
          (let [stored (upload/store! sid name (HttpExchange/.getRequestBody exchange))]
            (session/refresh! sid)
            (respond! exchange 201 json-content-type (str (encode stored) "\n"))))))
    (catch ExceptionInfo e
      (respond! exchange (:status (ex-data e) 500) "text/plain" (str (ex-message e) "\n")))
    (catch IOException e
      (respond! exchange 409 "text/plain"
        (str (Class/.getSimpleName (class e)) ": " (ex-message e) "\n")))
    (finally
      (HttpExchange/.close exchange))))


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
    (HttpServer/.createContext "/mcp" (handler #'handle-exchange!
                                        {:allowed-origins (set allowed-origins)}))
    (HttpServer/.createContext "/probe" (handler #'handle-probe!
                                          {:allowed-origins (set allowed-origins)}))
    (HttpServer/.createContext "/upload" (handler #'handle-upload!
                                           {:allowed-origins (set allowed-origins)}))
    ;; The default executor runs every exchange serially on the dispatch
    ;; thread, which deadlocks on the second connection. Quiescent's own
    ;; executor rather than a fresh virtual one: handler threads then carry
    ;; the same classloader as every task thread, and a task constructed in
    ;; a handler is rooted, since the handler is not itself a task.
    (HttpServer/.setExecutor q.exec/virtual-executor)
    (HttpServer/.start)))


(defn stop!
  [server]
  (HttpServer/.stop server 0))
