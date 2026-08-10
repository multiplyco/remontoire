(ns co.multiply.remontoire.patterns
  "Recipes: each namespace below demonstrates one shape of async eval
   against this system, as real code — loaded with the library, so a
   recipe that stops compiling breaks the load rather than quietly
   rotting. `(__r/nses \"patterns\")` is the index; `outline` a recipe
   namespace for its signatures and docstrings; `file` it for the
   `(comment …)` walkthroughs, which is where eval-by-eval choreography
   lives. Walkthrough code is *eval* code: it assumes the scratch
   wiring — the `q` alias, the bare refers (`qlet`, `qfor`, …) and
   `__r` — like any eval. These namespaces spell Quiescent `q`, exactly
   as committed source and the scratch namespace do; evaluating a
   walkthrough somewhere else means aliasing Quiescent there yourself
   first. House discipline:
   defns and comment blocks only, nothing effectful at the top level —
   these namespaces load inside the host JVM at boot, so loading must be
   inert."
  (:require
    [co.multiply.remontoire.patterns.bounding]
    [co.multiply.remontoire.patterns.cleanup]
    [co.multiply.remontoire.patterns.integrations]
    [co.multiply.remontoire.patterns.iteration]
    [co.multiply.remontoire.patterns.outliving]
    [co.multiply.remontoire.patterns.parallelism]
    [co.multiply.remontoire.patterns.partial-results]))
