# Job execution — what bounds it

**Status:** current. What happens to an adapter's jobs whether
or not it is started is in [ADAPTERS.md](ADAPTERS.md#an-adapter-that-is-not-running)
and [JOBS.md](JOBS.md#recovery-on-restart-214).

This document settles what limits how much work the venue runs at once. The
rule: **limit what is scarce, not jobs.** Nothing counts a user's jobs,
waiting or running; a scarce resource has a limit of its own, held only
while it is in use.

## What a running job costs

- **CPU.** Virtual threads run on a bounded pool: the JVM's carrier threads,
  one per core by default. A virtual thread that blocks on network I/O, a
  future, a lock or a sleep leaves its carrier, so a waiting job uses no CPU
  and blocks no other job. This holds on JDK 24 and later (the venue
  recommends 25), with one exception: a native call (Python through FFM)
  keeps its carrier. On JDK 21 to 23 a thread blocked inside `synchronized`
  keeps its carrier too.
- **Memory.** A job in flight holds its state whether it is computing or
  waiting on a thread. A job whose wait is recorded as state (the paused
  family, a followed remote job, an agent's queued task) holds none.
- **Scarce outside resources**: model calls (money, provider rate limits),
  database connections, Claude Code processes, outbound requests to other
  venues.

## Where job code waits

Some waits are state and hold no thread: a HITL request, an idle agent, an
agent's queued task, a remote job followed by `RemoteJobs` (one shared
timer; a request permit is held for one request only), an HTTP call
(asynchronous client), a Claude Code session between turns. Others block a
virtual thread:

| Wait | Where | How long |
|------|-------|----------|
| orchestration on its steps; each step on its sub-job; each `foreach` item | `Orchestrator` | unbounded: days for a step that asks a person |
| agent run loop on the cycle's transition | `AgentAdapter.executeRunLoop` | unbounded |
| transition on its model call; the model call's network I/O | `AbstractLLMAdapter`, `LangChainAdapter` | 120 s each by default |
| agent tool call on its sub-job; a wave on its calls | `AbstractLLMAdapter`, `ToolCycleEngine` | 300 s, then tracked as state |
| `agent:trigger` with a wait | `AgentAdapter` | unbounded by default |
| capability gate on its gate operation | `JobManager.evaluateGate` | 30 s |
| messaging facade turn on a remote agent | `ConversationRouter` | unbounded |
| MCP tool call | `MCPAdapter` | its request timeout |

None of these uses CPU while it waits; each holds memory, and none survives a
restart. A wait that holds a thread for days or must survive a restart, such
as an orchestration step that asks a person, becomes state in its own
adapter's change.

## No limit on jobs or threads

- **The venue counts neither jobs nor threads.** The carrier pool bounds
  CPU, and a waiting job leaves it. A count of unfinished jobs includes
  waiting ones, so a user whose jobs wait on people could start nothing. A
  limit on jobs in flight deadlocks once a job waits for a sub-job it
  started: the parent holds its place, and the child needs one. Exempting
  sub-jobs avoids that only by letting a job fan out without bound. A count
  of running tasks has the same flaw unless every wait above, and every
  blocking call in module and embedder adapters, gives its place up.
- **Invoking is consuming.** An operator who lets a user invoke operations
  accepts that the user's work uses the venue's capacity. The request rate
  (`rps`, `burst`) is the venue-wide control on how fast a user asks.
- **Scarce resources have limits of their own**, set by the operator where
  the resource is used, such as model calls, and held only while it is in
  use, as `RemoteJobs` holds its request permit for one request. The limits
  that exist stay (remote requests, Claude Code processes, scheduler fires,
  the local speech engine); a new one is its own change.
