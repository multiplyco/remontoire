(ns co.multiply.remontoire.dev
  "The `bb dev` entry point: Remontoire's MCP endpoint, then an nREPL server
   beside it — the library hosted by its own repo, so a client can connect
   to a JVM running this checkout's source. Dev-only: `dev/` is on the
   `:dev` alias and never in the jar.

   The endpoint is a gateway (§15), which with nothing attached is an
   instance like any other; `bb dev:follower <name>` starts further JVMs
   that attach to it, so both roles are at hand in one checkout."
  (:require
    [co.multiply.remontoire :as remontoire]
    [nrepl.cmdline :as nrepl]))


(def ^:private gateway "http://127.0.0.1:7888")


(defn -main
  "With no role, start the gateway with `init!`'s defaults and hand `args`
   to nREPL's own command line, which blocks for the life of the process.

   `follower <name> [port] [gateway]`: start an instance that attaches to
   that gateway as `name`, its own endpoint on `port` — any free one when
   0 or absent. `gateway` is the base address of another gateway to follow
   instead. No nREPL beside it: a second server would overwrite the
   first's `.nrepl-port`, and the follower's REPL is reachable through the
   gateway and at its own endpoint."
  [& [role & [name port other] :as args]]
  (if (= "follower" role)
    (do (remontoire/init! {:follow (or other gateway)
                           :name   (or name (str "follower-" (java.lang.ProcessHandle/.pid (java.lang.ProcessHandle/current))))
                           :port   (if port (parse-long port) 0)})
      @(promise))
    (do (remontoire/init! {:gateway? true})
      (apply nrepl/-main args))))
