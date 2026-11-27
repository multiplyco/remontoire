
## This server is a gateway

Everything above holds here: a session created without naming a node lives in this JVM, like any other.

Besides that, other machines running Remontoire attach to this server as *nodes*, and it routes sessions to them.

- **Open a session on a node** with `create-session`'s `node` argument. The session lives in that machine's JVM:
  its evals, tasks, scratch namespace and uploads are all there, and nothing else about using it differs. The
  answer names the node the session landed on — without `node`, that is this gateway.
- **A session is on one machine for its whole life.** To work on two machines, hold two sessions and name the
  right `session-id` in each call. Entry ids, `res` and `(__r/sessions)` are per machine.
- **`(__r.gw/nodes)` lists the fleet**, evaluated in a session on the gateway. The alias exists only there.
- **`(__r.gw/node "name")` waits for a node**: a task that settles when a node of that name attaches. Return it
  from an eval to wait for a machine you have just started, then open a session on it. It fails if the gateway
  stops while you wait.
- **`(__r.gw/change seen)` waits for the fleet to change**: a task that settles with the fleet, as `nodes` lists
  it, once a node has attached, gone away or been forgotten, or another machine has attached under a node's name.
  `seen` is the fleet as you last had it, from `nodes` or from this, and a fleet that already differs from it
  settles the task at once, so nothing that happens between your look and your wait is missed. Without `seen` it
  waits for the next change from now. A node being heard from, or its sessions coming and going, is not a change.
- **A node can go away** — asleep, restarting, unreachable. Calls to a session there are refused with the reason
  while it is away, and the session is still there when the machine returns unless its JVM restarted, in which
  case the refusal says so and a new session is needed.
