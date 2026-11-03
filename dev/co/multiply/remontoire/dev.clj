(ns co.multiply.remontoire.dev
  "The `bb dev` entry point: Remontoire's MCP endpoint, then an nREPL server
   beside it — the library hosted by its own repo, so a client can connect
   to a JVM running this checkout's source. Dev-only: `dev/` is on the
   `:dev` alias and never in the jar."
  (:require
    [co.multiply.remontoire :as remontoire]
    [nrepl.cmdline :as nrepl]))


(defn -main
  "Start the endpoint with `init!`'s defaults, then hand `args` to nREPL's
   own command line, which blocks for the life of the process."
  [& args]
  (remontoire/init!)
  (apply nrepl/-main args))
