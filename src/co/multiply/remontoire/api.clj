(ns co.multiply.remontoire.api
  "Your toolkit in this running system — *the* namespace evaluated code
   reaches for. It is aliased as `__r` in every namespace your evals
   touch, so `__r/…` resolves wherever you navigate. The alias is for
   eval code only; never write it into committed source."
  (:require
    [clojure.java.io :as io]
    [clojure.repl :as crepl]
    [co.multiply.quiescent :as q]
    [co.multiply.remontoire.exception :as exception]
    [co.multiply.remontoire.outline :as outline]
    [co.multiply.remontoire.registry :as registry]
    [co.multiply.remontoire.session :as session]
    [co.multiply.remontoire.tee :as tee]
    [co.multiply.scoped :as sc]))


(defn- whoami
  "The caller's session id, read off the same dynamic rails output
   attribution rides — a session must never bake its id into data, because
   reconnects and client restarts mint fresh ones; it asks."
  []
  (sc/ask session/*session* nil))


(defn manual
  "The manual, in one call — read it before your first real eval. Prints
   the prose half: the conceptual model of this environment. Reading it
   marks this session as informed, retiring the unread note from its
   responses. Once per conversation is enough — servers sharing a build
   share a manual.

   Output shape:
   the API reference — this namespace's `outline` with full docstrings,
   publics only, live from the running build (§4)."
  []
  (session/manual-read! (whoami))
  (println (slurp (io/resource "co/multiply/remontoire/api/manual.md")))
  (-> (outline/outline 'co.multiply.remontoire.api {:include-docstrings true})
    (update :vars #(filterv (complement :private) %))))


(defn res
  "The task for eval `id`.

   Accepts:
   - `id`: a registry entry id, any session's.

   Deref for the value — safe to repeat — or compose: `(q/cancel (res 12))`
   cancels, handlers chain onto it. `(q/get-ex (res 12))` reads an
   exception as a value — failure or cancellation alike, nil otherwise;
   `catch` also reads a failure, but a *cancelled* entry passes
   cancellation through `catch` to whatever composes on it. Reading is
   delivery: it refreshes the entry and admits it to the evictable pool
   once settled.

   Output shape:
   Task ; Quiescent"
  [id]
  (registry/res id))


(defn evals
  "One map per live registry entry, oldest first. The registry is shared
   across every session on this JVM — attribution, not isolation: what
   you didn't make is labeled `:by`, never hidden, and `(res id)` reaches
   it all the same.

   Output shape:
   [{:id     Long
     :status Keyword  ; :running :done :failed :cancelled
     :peer   String
     :at     Inst
     :by     String}  ; owner's id prefix — only on entries you didn't mint
    …]"
  []
  (registry/evals))


(defn out
  "What eval `id` has printed so far, as lines — usable mid-run, since
   the capture fills as the work speaks.

   Accepts:
   - `id`: a registry entry id, any session's.

   Both streams, `*out*` and `*err*`, interleaved in arrival order, the
   pending unterminated line last — it is the progress line. Retention is
   the bank's 32 KiB tail; chars the cap dropped are announced as the
   first element, never silently absent. Reading takes nothing from it:
   the next read has it whole. A read *about* the entry, not of its
   result — it neither refreshes nor delivers. `(run! println (out id))`
   is the reading form, as with `file`.

   Output shape:
   [String …]"
  [id]
  (some-> (:bank (registry/entry id)) tee/lines))


(defn failure
  "The rendered failure of eval `id`: the exception chain and the
   interesting frames, the eval machinery's own subtracted — the same
   rendering a failed eval response carries.

   Accepts:
   - `id`: a registry entry id, any session's. Safe for any state — when
     `id` did not fail, says how it stands instead.

   A rendering, not the throwable — for programmatic access,
   `(q/get-ex (res id))` is the exception itself as a value. Reading a
   failure is delivery, like any `res`.

   Output shape:
   String"
  [id]
  (let [task (registry/res id)]
    (if (= :failed (registry/status task))
      (exception/render
        (exception/profile (q/get-ex task)
          (some-> (:baseline (registry/entry id)) deref)))
      (str "Entry " id " is " (name (registry/status task))))))


(defn sessions
  "Who is connected to this JVM: one map per live session, `:you` marking
   the caller's own row.

   Output shape:
   [{:sid     String
     :idle-s  Long    ; seconds since it last spoke
     :entries Long    ; registry entries it owns
     :client  String  ; name and version, when known
     :note    String  ; its introduce! line, when given
     :you     true}   ; present only on the caller's row
    …]"
  []
  (let [caller (whoami)
        counts (registry/owners)
        now    (System/currentTimeMillis)]
    (mapv (fn [{:keys [sid last-seen client note]}]
            (cond-> {:sid     sid
                     :idle-s  (quot (- now (or last-seen now)) 1000)
                     :entries (get counts sid 0)}
              client (assoc :client (str (get-in client [:clientInfo :name])
                                      " " (get-in client [:clientInfo :version])))
              note (assoc :note note)
              (= sid caller) (assoc :you true)))
      (session/roster))))


(defn introduce!
  "Describe this session on the roster — what it is for, in its own
   words: a commit message for a session, read by other tenants via
   `(sessions)`.

   Accepts:
   - `s`: the description — one line, present tense:
     \"debugging the payment retry storm\".

   Output shape:
   `s`, unchanged."
  [s]
  (session/note! (whoami) s)
  s)


(defn release!
  "Punctuation: mark the leg of work behind this session absorbed and
   evictable. Punctuate when a leg of work ends — the claims lapse now
   rather than at the 24 h silence expiry.

   Accepts:
   - `period?`: default false, a comma — every settled, unread entry this
     session owns is released; running entries stay live concerns.
     true is a period — running entries are cancelled too, except the
     eval performing the release: punctuation ends the leg behind you,
     not the sentence being spoken.

   Output shape:
   {:released  [Long …]  ; absorbed
    :cancelled [Long …]} ; stopped — the report is the audit"
  ([] (release! false))
  ([period?]
   (registry/release! (whoami) (boolean period?) (sc/ask tee/*bank* nil))))


(defn scoped-fn
  "`bound-fn*` for scope: capture the current scope, output attribution
   included.

   Accepts:
   - `f`: any fn.

   For work that escapes the task tree: raw threads, executor callbacks,
   futures launched from *inside* a nested task. Not needed for Quiescent
   tasks, which carry scope themselves, nor for a `future` launched from
   top-level eval code, which Clojure conveyance covers. See
   `co.multiply.scoped` for lower-level scope propagation utilities.

   Output shape:
   fn — `f`'s arguments, run under the captured scope."
  [f]
  (let [scope (sc/current-scope)]
    (fn [& args]
      (sc/with-scope scope (apply f args)))))


(defn nses
  "Browse all loaded namespaces.

   Accepts:
   - `match`: String or Symbol (both case-insensitive, can be partial, `*` a
              wildcard), or regex. Exact whole-segment matches first, then name
              hits, then docstring-only hits. Alphabetical within each tier.
   - `opts`: {:search-docstrings false} ; names only, default `true`.

   Returns loaded namespaces only, live in the system.

   Output shape:
   [Symbol …]"
  ([] (outline/nses nil))
  ([match] (outline/nses match))
  ([match opts] (outline/nses match opts)))


(defn vars
  "Global var search — \"where does that function live?\". Where `grep` guesses
   paths over the repo's text, this reads the JVM: every loaded namespace,
   dependency jars included, current after hot reloads.

   Accepts:
   - `match`: String or Symbol (case-insensitive, can be partial, `*` a wildcard),
              or regex. Exact-as-spelled first, after which `var?`, `->Var`, `*var*`
              are exact for \"var\", then name hits, then docstring-only hits.
              Within tiers: namespace, publics before privates, source line.
   - `opts`: {:search-docstrings false} ; names only, default `true`.

   Matches vars in loaded namespaces, live in the system.

   Examples:
   - `(__r/vars 'await)`: Anything containing \"await\"
   - `(__r/vars 'helpers/await)`: Scope to the \"helpers\" ns partial
   - `(__r/vars \"module*impl/handle*user\")`: Globs allow gaps in the matches
   - `(__r/vars \"module/\")`: All vars under any ns containing \"module\"
   - `(__r/vars #\"handle-\\w+\")`: Regex on the name
   - `(__r/vars #\"helpers[^/]*/await\")`: A regex with a `/` matches the whole
     qualified name

   Output shape:
   [{:ns         Symbol
     :name       Symbol
     :arglists   ([arg …] …) ; absent on non-fns
     :line       Long        ; when known
     :private    true        ; flags present only when true
     :macro      true
     :dynamic    true
     :dispatches [… …]       ; multimethods: the dispatch values
     :matched    [String …]} ; matching quote for docstring hits
    …]"
  ([match]
   (outline/vars match))
  ([match opts]
   (outline/vars match opts)))


(defn file
  "The source file of a namespace, as lines — the whole of what is
   *written*, comments and `(comment …)` blocks included, where `outline`
   shows what is *live*.

   Accepts:
   - `ns`: the designators `outline` takes.

   The 1-indexed `:line` of `outline`/`vars` entries indexes the lines:
   `(drop (dec line) (file ns))` starts reading at a var. Slice before
   returning on unfamiliar namespaces — files can be large. `(run! println …)`
   is the reading form, as with `out`. Throws for a namespace with no
   source file on the classpath.

   Output shape:
   [String …]"
  [ns]
  (outline/file ns))


(defn outline
  "A namespace at a glance: one entry per var, in source order, privates
   included and marked. Bare output is small enough for any namespace.

   Accepts:
   - `ns`: Symbol, String, an alias visible from the calling namespace, or the
     Namespace itself. Throws when it designates no loaded namespace.
   - `opts`: {:include-private false}   ; publics only.
             {:include-docstrings true} ; every docstring whole.
             {:match …}                 ; as `match` in `nses`/`vars`; keeps vars
                                        ; whose name or docstring matches.

   The view is the running system's own — after a hot reload it shows what is
   live, not what is on disk.

   Output shape:
   {:ns   Symbol
    :doc  String                    ; first sentence; whole with :include-docstrings
    :vars [{:name       Symbol
            :arglists   ([arg …] …) ; absent on non-fns
            :line       Long        ; when known
            :private    true        ; flags present only when true
            :macro      true
            :dynamic    true
            :dispatches [… …]       ; multimethods: the dispatch values
            :doc        String      ; with :include-docstrings
            :matched    [String …]} ; matching quote for docstring hits, under :match
           …]}"
  ([ns] (outline ns nil))
  ([ns opts] (outline/outline ns opts)))


(defmacro doc
  "Prints documentation for a var or special form given its name, or for
   a spec given a keyword.

   Accepts:
   - `name`: unquoted; resolves in the current namespace — qualify
     (`__r/failure`, `q/catch`) when in doubt. Unresolvable prints
     nothing rather than throwing.

   Output shape:
   nil — printed, not returned; the eval response carries it as output."
  [name]
  `(crepl/doc ~name))


(defmacro source
  "Prints the source of a var, given its name: what is *written*, where
   `outline` and `doc` show what is *live* — after a hot reload the two
   can differ.

   Accepts:
   - `name`: unquoted; resolves in the current namespace — qualify
     (`__r/failure`, `q/catch`) when in doubt. Needs the defining file on
     the classpath, which holds for anything shipped as source;
     otherwise, and for an unresolvable name, prints `Source not found`.

   Output shape:
   nil — printed, not returned; the eval response carries it as output."
  [name]
  `(crepl/source ~name))
