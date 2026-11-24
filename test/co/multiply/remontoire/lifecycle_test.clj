(ns co.multiply.remontoire.lifecycle-test
  "`init!`, `status` and `stop!` as a host calls them."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.remontoire.http :as http]
    [co.multiply.remontoire.mcp :as mcp]
    [co.multiply.remontoire.registry :as registry])
  (:import
    [clojure.lang Var]
    [java.net InetAddress InetSocketAddress ServerSocket URI]
    [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers]))


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
                   (with-redefs-fn {#'http/start! (fn [_] (deliver binding? true) @go (throw (java.net.BindException. "taken")))}
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
