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
   the prose half: the conceptual model of this environment — what an id
   is, what retention can be relied on, how sessions share the one JVM,
   and the async semantics that otherwise surprise silently. Returns the
   API reference as data: this namespace's outline, public vars with
   their full docstrings — live, so it is the running build's truth
   where anything delivered at connection time may since have gone stale
   (§4). Publics only; privates stay readable through a real `outline`.
   Reading it also marks this session as informed, which retires the
   unread note from its responses. Once per conversation is enough —
   servers sharing a build share a manual."
  []
  (session/manual-read! (whoami))
  (println (slurp (io/resource "co/multiply/remontoire/api/manual.md")))
  (-> (outline/outline 'co.multiply.remontoire.api {:include-docstrings true})
    (update :vars #(filterv (complement :private) %))))


(defn res
  "The task for eval `id`. Deref for the value — safe to repeat — or compose:
   `(__q/cancel (res 12))` cancels, handlers chain onto it. To read an
   exception, `(__q/get-ex (res 12))` is it as a value — failure or
   cancellation alike, nil otherwise; `catch` also reads a failure, but a
   *cancelled* entry passes cancellation through `catch` to whatever eval
   composes on it. Reading is delivery: it refreshes the entry and admits
   it to the evictable pool once settled."
  [id]
  (registry/res id))


(defn evals
  "One map per live registry entry, oldest first — id, status, peer, time.
   The registry is shared across every session on this JVM, so entries
   another session minted appear too, tagged `:by` with their owner's id
   prefix — attribution, not isolation: what you didn't make is labeled,
   never hidden, and `(res id)` reaches it all the same."
  []
  (registry/evals))


(defn out
  "What eval `id` has printed so far, as a vector of lines — usable
   mid-run, since the capture fills as the work speaks. Both streams,
   `*out*` and `*err*`, interleaved in arrival order — warnings and
   stack traces land here beside the data — with the pending
   unterminated line last: it is the progress line. Lines, because
   reading back is composing: `(take-last 5 (out id))` peeks at a chatty
   eval, `drop-last` then `take-last` pages further back, `filter`
   searches; `(run! println (out id))` is the reading form, arriving
   verbatim under `;; out`. Retention is the bank's 32 KiB tail — chars the cap
   dropped are announced as the first element, never silently absent —
   and reading takes nothing from it: however the vector is sliced, the
   next read has it whole. A read *about* the entry, not of its result:
   it neither refreshes nor delivers."
  [id]
  (some-> (:bank (registry/entry id)) tee/lines))


(defn failure
  "The rendered failure of eval `id`: the exception chain and the
   interesting frames, the eval machinery's own frames subtracted — the
   same rendering a failed eval response carries. A rendering, not the
   throwable — for programmatic access, `(__q/get-ex (res id))` is the
   exception itself as a value, cancellation included, and `ex-data` works
   from there. Reading a failure is delivery, like any `res`. When `id`
   did not fail, says how it stands instead — safe for any state."
  [id]
  (let [task (registry/res id)]
    (if (= :failed (registry/status task))
      (exception/render
        (exception/profile (q/get-ex task)
          (some-> (:baseline (registry/entry id)) deref)))
      (str "Entry " id " is " (name (registry/status task))))))


(defn sessions
  "Who is connected to this JVM: one map per live session — `:sid`,
   `:idle-s` since it last spoke, `:client` name and version, its `:note`
   when it introduced itself, how many `:entries` it owns — with `:you`
   marking the caller's own row. The roster answers what would otherwise
   take guesswork: whose entries are these, when was that session last
   alive, which id am I now."
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
  "Describe this session on the roster — what it is for, in its own words:
   `(__r/introduce! \"debugging the payment retry storm\")`. A commit
   message for a session: other tenants reading `(sessions)` see who is
   here and why, instead of an anonymous id owning entries."
  [s]
  (session/note! (whoami) s)
  s)


(defn release!
  "Punctuation: mark the leg of work behind this session absorbed and
   evictable, instead of sifting the conversation for what has been read
   and what might still be running. `(release!)` is a comma — every
   settled, unread entry this session owns is released; running entries
   are left alone and stay live concerns, settled news included.
   `(release! true)` is a period — running entries are cancelled too.
   Returns {:released [ids] :cancelled [ids]}: what was absorbed versus
   what was stopped — the report is the audit. Also the polite goodbye, in
   a system whose clients rarely send one: punctuate when a leg of work
   ends, and the claims lapse now rather than at the 24 h silence expiry.
   The eval performing the release is exempt from its own period —
   punctuation ends the leg behind you, not the sentence being spoken."
  ([] (release! false))
  ([period?]
   (registry/release! (whoami) (boolean period?) (sc/ask tee/*bank* nil))))


(defn scoped-fn
  "`bound-fn*` for scope: capture the current scope — output attribution
   included — and return a fn of the same arguments that re-establishes it
   wherever it runs. For work that escapes the task tree: raw threads,
   executor callbacks, futures launched from *inside* a nested task.
   Not needed for Quiescent tasks, which carry scope themselves, nor for a
   `future` launched from top-level eval code, which Clojure conveyance
   covers.

   See `co.multiply.scoped` for lower level scope propagation utilities if
   this function proves insufficient."
  [f]
  (let [scope (sc/current-scope)]
    (fn [& args]
      (sc/with-scope scope (apply f args)))))


(defn nses
  "Every loaded namespace, as a sorted vector of symbols — the system at a
   glance, and the top of the drilldown: `nses` to find the namespace,
   `outline` to read it, `doc` and `source` for its vars. An optional
   `match` keeps only matching names: a string (case-insensitive
   substring), a regex, or a symbol matched as its name — so
   `(nses \"session\")` searches, and `(nses 'co.multiply.quiescent)`
   answers whether a namespace is really loaded. Plain data, made for
   composing — filter it, count it, map `outline` over it. The view is
   the running system's: what has actually been loaded, not everything on
   the classpath."
  ([] (outline/nses nil))
  ([match] (outline/nses match)))


(defn vars
  "Global var search — \"where does that function live?\" answered without
   guessing namespaces: every loaded var whose name or docstring matches,
   as outline entries with `:ns` attached. Scored — vars named exactly
   `match` first, then name hits, then docstring-only hits, in namespace
   and source order within each tier — so the definition asked for leads
   and the mentions follow. `match` is a string (case-insensitive
   substring), a
   regex, or a symbol matched as its name; docstring hits quote their
   matching lines as `:matched` — the answer shows its evidence. The
   whole loaded system is searched, clojure.core and dependencies
   included, so a broad term returns plenty — narrow it, or filter the
   hits by `:ns`: they are plain data, made for composing, and each one
   says where `outline`, `doc` and `source` should look next. Loaded
   namespaces only, like `nses`: what has never been required has no
   vars to search."
  [match]
  (outline/vars match))


(defn file
  "The source file of a namespace, verbatim, as a string — comments and
   `(comment …)` blocks included: the whole of what is *written*, where
   `outline` shows what is *live*. Takes the designators `outline` takes.
   Returned rather than printed, for composing — `str/split-lines`, a
   `drop-while` down to a section — and `(println (file 'some.ns))` is
   the reading form: printed output arrives verbatim where a returned
   string renders with its newlines escaped. Mind the size on unfamiliar
   namespaces — `(count (file 'x))` is cheap, and slicing before
   returning beats returning the whole of a large file. Throws, plainly,
   for a namespace with no file on the classpath."
  [ns]
  (outline/file ns))


(defn outline
  "A namespace at a glance: `{:ns … :doc … :vars […]}`, one entry per
   var — name, arglists, `:line`, `:macro`/`:private`/`:dynamic` flags, a
   multimethod's dispatch values — in source order, privates included and
   marked, because the point is the gist of the namespace, not just its
   API. Designate the namespace by symbol, by an alias visible from the
   calling namespace, or with the namespace itself: `(outline '__r)`.
   Bare output is small enough for any namespace, with the ns docstring's
   first sentence for orientation; `{:include-docstrings true}` carries
   every docstring whole — one roundtrip for a full overview when the
   namespace is small or important. `{:match \"evict\"}` (string,
   case-insensitive, or regex) keeps only vars whose name or docstring
   matches; docstring hits quote their matching lines as `:matched`, so
   the answer shows its evidence. `:line` says where to start reading
   when the signature isn't enough. The view is the running system's
   own — after a hot reload it shows what is live, not what is on disk."
  ([ns] (outline ns nil))
  ([ns opts] (outline/outline ns opts)))


(defmacro doc
  "Prints documentation for a var or special form given its name, or for
   a spec if given a keyword. Printed, not returned — the eval response
   carries it as output. The name resolves in the current namespace, and
   an unresolvable one prints nothing rather than throwing — qualify
   (`__r/failure`, `__q/catch`) when in doubt."
  [name]
  `(crepl/doc ~name))


(defmacro source
  "Prints the source of a var, given its name: what is *written*, where
   `outline` and `doc` show what is *live* — after a hot reload the two
   can differ. Printed, not returned — the eval response carries it as
   output. The name resolves in the current namespace — qualify
   (`__r/failure`, `__q/catch`) when in doubt — and the defining file must
   be on the classpath, which holds for anything shipped as source;
   otherwise, and for an unresolvable name, it prints `Source not
   found`."
  [name]
  `(crepl/source ~name))
