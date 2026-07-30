# 12. Build order

1. **The eval tool and its endpoint.** Registry behind `defonce`, root-level out capture with thread tagging, structured
   errors, task wrapping, `cancel`, dumb print cap — plus `init!`/`stop!`, the `jdk.httpserver` MCP endpoint with a
   virtual-thread executor, tailnet binding and `Origin` validation. About a day and a half. First test: fan out over
   `q/task`s with printlns from several virtual threads.
2. **`shape`**, written through the tool. Replaces the dumb print cap.
3. **`outline` / `form` / `changed-forms`** — pure `rewrite-clj`. *The runtime `outline` built 2026-08-05 (§7) from
   var metadata alone; the rewrite-clj source outline stays here as the file-facing half.*
4. **`usages` / `kw-usages`** over the kondo analysis, with freshness reporting.
5. **Namespaced-keyword rename** and the bare-keyword census.
6. **`q/traced`** — the one-line incision in Quiescent, plus the collector here.

Pin the entry and site element shapes before step 3; every pipeline written afterwards depends on them, while individual
functions stay freely replaceable.

