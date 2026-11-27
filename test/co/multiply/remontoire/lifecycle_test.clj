(ns co.multiply.remontoire.lifecycle-test
  "`init!`, `status` and `stop!` as a host calls them."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.remontoire.fleet :as fleet]
    [co.multiply.remontoire.follower :as follower]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session])
  (:import
    [clojure.lang Var]
    [com.sun.management UnixOperatingSystemMXBean]
    [com.sun.net.httpserver HttpExchange]
    [java.lang.management ManagementFactory]
    [java.net InetAddress InetSocketAddress ServerSocket URI]
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpRequest$Builder HttpResponse
     HttpResponse$BodyHandlers]))


(defn- free-port
  []
  (with-open [free (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
    (ServerSocket/.getLocalPort free)))


(deftest a-start-that-fails-leaves-the-host-as-it-was
  (remontoire/init! {:port 0 :capacity 9 :describe "the first"})
  (remontoire/stop!)
  (with-open [taken (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
    (let [out (Var/.getRawRoot #'*out*)
          err (Var/.getRawRoot #'*err*)]
      (is (thrown? java.io.IOException
            (remontoire/init! {:port (ServerSocket/.getLocalPort taken) :capacity 1 :describe "the second"})))
      (is (nil? (remontoire/status)))
      (is (identical? out (Var/.getRawRoot #'*out*)) "its output is not left wrapped")
      (is (identical? err (Var/.getRawRoot #'*err*)))
      (is (= 9 (registry/capacity)) "and what the attempt asked for does not stay")
      (is (= "the first" (:describe (mcp/probe)))))))


(deftest a-start-refused-for-its-options-does-not-keep-the-port
  (doseq [bad [{:capacity 0} {:allowed-origins 5}]]
    (let [port (free-port)]
      (is (thrown? Throwable (remontoire/init! (assoc bad :port port))))
      (is (nil? (remontoire/status)))
      (try
        (is (= port (get-in (remontoire/init! {:port port}) [:opts :port])) "the same port, asked for properly")
        (finally (remontoire/stop!))))))


(deftest a-door-that-cannot-be-opened-does-not-keep-the-port
  (let [port (free-port)
        opts {:port port :bind "127.0.0.1"}]
    (is (thrown? IllegalArgumentException
          (http/start! opts {:contexts {"no-leading-slash" (fn [_ _] nil)}})))
    (let [server (http/start! opts)]
      (try
        (is (= port (InetSocketAddress/.getPort (http/address server))) "the same port, with doors that open")
        (finally (http/stop! server))))))


(deftest a-port-that-is-taken-costs-nothing-to-ask-for
  (let [timers #(count (filter (fn [thread] (str/includes? (Thread/.getName thread) "idle-timeout"))
                         (keys (Thread/getAllStackTraces))))
        os     (ManagementFactory/getOperatingSystemMXBean)
        ;; Where the JVM can count them.
        open   #(when (instance? UnixOperatingSystemMXBean os)
                  (UnixOperatingSystemMXBean/.getOpenFileDescriptorCount os))]
    ;; Those of servers earlier tests stopped are on their way out.
    (loop [n 0]
      (when (and (pos? (timers)) (< n 100))
        (Thread/sleep 20)
        (recur (inc n))))
    (with-open [taken (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
      (let [refused? (fn [role]
                       (is (thrown? java.io.IOException
                             (remontoire/init! (assoc role :port (ServerSocket/.getLocalPort taken))))
                         (pr-str role)))
            before   (open)]
        (dotimes [_ 3] (refused? {}))
        (when before
          (is (<= (open) before) "nor a descriptor"))
        (dotimes [_ 3] (refused? {:gateway? true :name "g"}))
        ;; A follower has made what it dials with by the time it asks for
        ;; its port. Asked of each one itself: the JVM's count of
        ;; descriptors moves for reasons that are not a client's.
        (let [start!  follower/start!
              clients (atom [])]
          (with-redefs [follower/start! (fn [opts]
                                          (let [started (start! opts)]
                                            (swap! clients conj (:client @@#'follower/running))
                                            started))]
            (dotimes [_ 3] (refused? {:follow "http://127.0.0.1:9" :name "f"})))
          (is (= 3 (count @clients)))
          (is (every? HttpClient/.isTerminated @clients) "nor what it would have dialled with"))))
    (is (zero? (timers)) "no server is left behind by a start that could not bind")
    (remontoire/init! {:port 0})
    (try
      (is (pos? (timers)) "and the count is of something: a server that starts has its timer")
      (finally (remontoire/stop!)))))


(deftest what-a-host-asks-for-opens-no-door
  (let [answer        (fn [exchange _]
                        (HttpExchange/.sendResponseHeaders exchange 200 -1)
                        (HttpExchange/.close exchange))
        {:keys [url]} (remontoire/init! {:port 0 :contexts {"/extra" answer} :route (fn [_ _] true)})]
    (try
      (is (= 404 (HttpResponse/.statusCode
                   (HttpClient/.send (HttpClient/newHttpClient)
                     (HttpRequest$Builder/.build (HttpRequest/newBuilder (URI/create (str/replace url "/mcp" "/extra"))))
                     (HttpResponse$BodyHandlers/ofString))))
        "the doors are the transport's and the role's")
      (is (= 200 (HttpResponse/.statusCode
                   (HttpClient/.send (HttpClient/newHttpClient)
                     (HttpRequest$Builder/.build (HttpRequest/newBuilder (URI/create (str/replace url "/mcp" "/probe"))))
                     (HttpResponse$BodyHandlers/ofString)))))
      (finally (remontoire/stop!)))))


(deftest a-start-that-fails-leaves-no-role-behind
  (let [refused (fn [opts]
                  (let [port (free-port)]
                    (is (thrown? Throwable (remontoire/init! (merge {:port port :name "n"} opts))) (pr-str opts))
                    (is (nil? (remontoire/status)))
                    (is (nil? (fleet/role)) (pr-str opts))
                    (is (= {} @@#'session/fleet-hooks) (pr-str opts))
                    (try
                      (is (= port (get-in (remontoire/init! {:port port}) [:opts :port]))
                        "the port it asked for is free")
                      (finally (remontoire/stop!)))))
        start!  follower/start!
        attach! follower/attach!]
    ;; Before anything is adopted.
    (refused {:gateway? true :follow "http://127.0.0.1:9"})
    ;; When the port is asked for.
    (with-open [taken (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
      (refused {:gateway? true :port (ServerSocket/.getLocalPort taken)}))
    ;; When the role has been taken up, before the port is asked for.
    (with-redefs-fn {#'follower/start! (fn [opts]
                                         (start! opts)
                                         (throw (ex-info "taken up, and then" {})))}
      #(refused {:follow "http://127.0.0.1:9"}))
    ;; And once it dials, on a server already answering.
    (with-redefs-fn {#'follower/attach! (fn []
                                          (attach!)
                                          (throw (ex-info "dialled, and then" {})))}
      #(refused {:follow "http://127.0.0.1:9"}))))


(deftest an-instance-alone-opens-no-session-once-it-has-stopped
  ;; Its claim, as a request it accepted still holds it.
  (remontoire/init! {:port 0})
  (let [alone  (session/fleet)
        held   (session/mint! alone)
        before (set (session/handles))]
    (try
      (remontoire/stop!)
      (is (thrown? clojure.lang.ExceptionInfo (session/mint! alone)) "a mint on it fails")
      ;; What the JVM became, which answers for its sessions now.
      (remontoire/init! {:port 0 :gateway? true :name "g"})
      (is (thrown? clojure.lang.ExceptionInfo (session/mint! alone)) "whatever the JVM is by then")
      (is (= before (set (session/handles))) "and leaves no session")
      (finally
        (remontoire/stop!)
        (session/drop! held)))))


(deftest a-session-being-opened-by-an-instance-alone-is-opened-before-its-stop-returns
  (remontoire/init! {:port 0})
  (let [{:keys [claim]} (session/fleet)
        inside   (promise)
        go       (promise)
        ;; Held where the claim has said yes and is making the session.
        taking   (future (claim "bcdfg" #(do (deliver inside true) @go)))
        _        (deref inside 2000 nil)
        stopping (future (remontoire/stop!))]
    (try
      (is (= ::waiting (deref stopping 200 ::waiting)) "a stop waits for it")
      (deliver go true)
      (is (= :stopped (deref stopping 2000 ::waiting)) "and returns once it is made")
      (is (true? (deref taking 2000 ::waiting)))
      (finally
        (deliver go true)
        (remontoire/stop!)))))


(defn- a-batch-that-replaces-its-instance
  "One post of two calls to an instance started with `opts`, nothing
   held: the first stops the endpoint it was sent to and starts a gateway
   in the JVM, the second asks for a session. The stop closes the socket
   the post was made on, so what the second call's mint came to is read
   where it is made."
  [opts]
  (let [{:keys [url]} (remontoire/init! (assoc opts :port 0))
        sid     (session/mint!)
        before  (set (session/handles))
        mint!   session/mint!
        outcome (promise)
        call    (fn [id tool arguments]
                  {:jsonrpc "2.0" :id id :method "tools/call" :params {:name tool :arguments arguments}})
        become  (str "(co.multiply.remontoire/stop!) "
                  "(co.multiply.remontoire/init! {:port 0 :gateway? true :name \"next\"}) "
                  ":became")]
    (try
      (with-redefs-fn {#'session/mint! (fn [& args]
                                         (let [made (try (apply mint! args) (catch Throwable t t))]
                                           (deliver outcome made)
                                           (if (instance? Throwable made) (throw made) made)))}
        (fn []
          (try
            (HttpClient/.send (HttpClient/newHttpClient)
              (-> (HttpRequest/newBuilder (URI/create url))
                (HttpRequest$Builder/.header "Content-Type" "application/json")
                (HttpRequest$Builder/.POST
                  (HttpRequest$BodyPublishers/ofString
                    (json/write-str [(call 1 "eval" {:code become :session-id sid})
                                     (call 2 "create-session" {})])))
                (HttpRequest$Builder/.build))
              (HttpResponse$BodyHandlers/ofString))
            (catch java.io.IOException _ nil))
          (is (instance? clojure.lang.ExceptionInfo (deref outcome 5000 nil))
            "the second call's mint fails: it was asked of an instance that has stopped")))
      (is (= "next" (fleet/node-name)) "the first call was served")
      (is (= before (set (session/handles)))
        "and the gateway that came after holds no session it was not asked for")
      (finally
        (remontoire/stop!)
        (session/drop! sid)))))


(deftest a-batch-that-stops-an-instance-alone-opens-no-session-after-it
  (a-batch-that-replaces-its-instance {}))


(deftest a-batch-that-stops-a-gateway-opens-no-session-after-it
  (a-batch-that-replaces-its-instance {:gateway? true :name "first"}))


(deftest any-free-port-is-reported-as-the-port-it-got
  (let [{:keys [url opts]} (remontoire/init! {:port 0})]
    (try
      (is (pos? (:port opts)))
      (is (= url (:url (remontoire/status))))
      (is (= 200 (HttpResponse/.statusCode
                   (HttpClient/.send (HttpClient/newHttpClient)
                     (HttpRequest$Builder/.build (HttpRequest/newBuilder (URI/create (str/replace url "/mcp" "/probe"))))
                     (HttpResponse$BodyHandlers/ofString))))
        "and the address returned answers")
      (finally (remontoire/stop!)))
    (is (nil? (remontoire/status)))))


(deftest the-address-reported-is-the-address-bound
  ;; However it was written: each of these binds.
  (doseq [bind ["127.0.0.1" "127.1" "::1" "[::1]"]]
    (let [uri (URI/create (#'remontoire/url (InetSocketAddress. ^String bind 7888)))]
      (is (= (InetAddress/getByName bind) (InetAddress/getByName (URI/.getHost uri))) bind)
      (is (= 7888 (URI/.getPort uri)))
      (is (= "/mcp" (URI/.getPath uri))))))


(deftest a-start-that-fails-once-it-answers-is-stopped
  (remontoire/init! {:port 0 :capacity 9 :describe "the first"})
  (remontoire/stop!)
  (let [port (free-port)
        out  (Var/.getRawRoot #'*out*)]
    (with-redefs-fn {#'remontoire/url (fn [_] (throw (ex-info "no address" {})))}
      #(is (thrown? clojure.lang.ExceptionInfo
             (remontoire/init! {:port port :capacity 1 :describe "the second"}))))
    (is (nil? (remontoire/status)))
    (is (identical? out (Var/.getRawRoot #'*out*)))
    (is (= 9 (registry/capacity)))
    (is (= "the first" (:describe (mcp/probe))))
    (try
      (is (= port (get-in (remontoire/init! {:port port}) [:opts :port])) "and nothing is left answering there")
      (finally (remontoire/stop!)))))


(deftest a-capacity-set-during-a-start-that-fails-is-kept
  (remontoire/init! {:port 0 :capacity 9})
  (remontoire/stop!)
  (let [binding? (promise)
        go       (promise)
        starting (future
                   (with-redefs-fn {#'http/start! (fn [& _] (deliver binding? true) @go (throw (java.net.BindException. "taken")))}
                     #(try (remontoire/init! {:port 0 :capacity 1}) (catch java.net.BindException _ :failed))))
        _        @binding?
        setting  (future (remontoire/capacity! 37))]
    (is (= ::waiting (deref setting 200 ::waiting)) "it waits for the start to come out one way or the other")
    (deliver go true)
    (is (= :failed (deref starting 2000 ::waiting)))
    (is (= 37 (deref setting 2000 ::waiting)))
    (is (= 37 (registry/capacity)))))


(deftest a-start-nobody-can-be-told-of-is-still-a-start
  (binding [*out* (doto (java.io.OutputStreamWriter. (java.io.ByteArrayOutputStream.)) java.io.Writer/.close)]
    (try
      (is (= (:url (remontoire/init! {:port 0})) (:url (remontoire/status))))
      (finally (is (= :stopped (remontoire/stop!)))))
    (is (nil? (remontoire/status)))))
