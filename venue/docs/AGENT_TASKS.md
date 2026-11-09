# Agent Tasks

A **task** is a discrete piece of work asked of an agent, which the asker can
wait on and which ends with an explicit outcome.

This document says what a task is, how it runs and how it ends.
[AGENT_LOOP.md](./AGENT_LOOP.md) covers the run loop that works on tasks,
[AGENT_SESSIONS.md](./AGENT_SESSIONS.md) the sessions they run in, and
[AGENT_CONTEXT.md](./AGENT_CONTEXT.md) §5.13 how one appears to a model.

## 1. What a task is

A task is created by `agent:request` and is that request's Job: the task's id
is the Job's id, and the Job holds its status and result
([JOBS.md](./JOBS.md)). While the task is open, the agent also holds a row for
it at `g/<agent>/tasks/<id>`: its input, the further inputs queued behind that,
and when it next runs. The row goes when the task ends. A task's `t/` scratch
space lives on the Job.

Every task belongs to one session, named in the request or minted for it. A
session can hold several tasks and chats.

An agent can be asked in three ways:

| Asked with | Asker waits | Finished by |
|------------|-------------|-------------|
| `agent:request` | Yes, on the Job | An explicit outcome (§4) |
| `agent:chat` | Yes, on the Job | The agent's next response on the session |
| `agent:message` | No | Nothing: no outcome is expected |

## 2. Asking

| `agent:request` input | Meaning |
|-----------------------|---------|
| `agentId` | The agent |
| `input` | What the agent is asked to do |
| `sessionId` | The session to continue; omitted, a new one is minted |
| `loads` | Context to pre-load, only when the request creates the session |
| `responseSchema` | The shape wanted for the result, shown to the agent |
| `strict` | Enforce `responseSchema` when the task completes; requires one |
| `outputPath` | Write the result here, as the asker. The Job then ends with a receipt `{status, outputPath, bytes}` instead of the result. A write that fails fails the task; a failed or cancelled task writes nothing |
| `timeout` | How long a synchronous call waits for the result, in milliseconds (default 5000; 0 does not wait), before returning the Job's id and status. It does not affect the task: tasks have no deadline |

A request needs the `agent/request` ability ([UCAN.md](./UCAN.md)), and is
refused if the agent does not exist or is suspended. Any other agent accepts
it; nothing checks whether that agent can resolve a task (§5.3).

An external agent, such as one on `remoteagent`, handles the request through
its own adapter, and the rest of this document does not apply to it.

## 3. Running

The run loop takes at most one task per cycle: the oldest that is ready. A
task is ready unless it is parked (§6). The cycle runs in the task's session.

Further input for an open task queues behind its original input, and each
cycle is shown the next one not yet shown. It comes from `agent:message` with
the task's `taskId`, the answer to a human ask the task's cycle made, or the
outcome of a call that outran its time limit. A cycle cannot resolve a task
that has input it has not been shown: the attempt returns status `STARTED` with
a reason, and the task runs again.

## 4. How a task ends

| Outcome | Job status | Cause |
|---------|------------|-------|
| Completed | `COMPLETE` | The agent resolved it with a result (§5) |
| Failed by the agent | `FAILED` | The agent resolved it with an error (§5) |
| Cycle failed | `FAILED` | The cycle working on it failed, for example at the tool-call iteration limit or on a request the provider refused ([AGENT_LOOP.md](./AGENT_LOOP.md) §3.4) |
| Nothing to resume it | `FAILED` | A cycle ended with the task open and nothing to bring it back (§6) |
| Stuck | `FAILED` | A run presented it for its whole iteration budget without resolving it |
| Wait lost | `FAILED` | It was parked on something that a restart or a session's removal ended |
| Cancelled | `CANCELLED` | `agent:cancelTask`, whose optional `reason` becomes the Job's error, or cancelling the request Job. A cycle already working on the task is not stopped |

An agent that cannot continue is suspended instead, and the task stays queued
for after it resumes ([AGENT_LOOP.md](./AGENT_LOOP.md) §3.4).

Resolving moves the task out of the queue and records its outcome on the agent
in one transaction, so it cannot then be cancelled or resolved again, and the
outcome survives a restart or a later failure of the same cycle. The Job
settles when the cycle's work merges, so the asker sees the cycle's writes
first. The cycle's timeline entry records the outcome in `taskResults`.

## 5. Resolving a task

`agent:complete-task` (`result`) and `agent:fail-task` (`error`) resolve the
task of the cycle that calls them. They take no id. Only a cycle with a task in
scope can use them, and an agent's capability limits do not restrict them.

When the task is `strict`, a result that does not conform to `responseSchema`
is refused, and a string that parses as conforming JSON is taken as that value.

### 5.1 From an LLM agent

Both runtimes wrap the operations as the harness tools `complete_task` and
`fail_task`.

- **Offered** at the root frame on every inference, whether or not a task is
  in play, because the tool list is the start of the cached prompt. Never in a
  child frame. With no task in scope, a call is refused.
- **With tool calling off** for the model, no tools are presented.
- **Written as text**, a call is honoured while a task is outstanding, when
  the text is the tool's name, alone or followed by a JSON object.
- **An empty `result` or `error`** falls back to the text of the same turn. With
  no text either, the call is refused and the model can try again.
- **A rejected result** returns the reason and the schema to the model, which
  can try again. It does not stop later calls in the batch.
- **A successful call** ends the batch: later calls in it are not run.

### 5.2 Where the runtimes differ

| | `llmagent` | `goaltree` |
|-|------------|------------|
| After a successful call | One more inference, with every tool still available. Its text stays in the session's history; the task's result and the cycle's response are unaffected | The frame ends with no further inference |
| A final text reply with the task open | The task yields (§6) | The text completes the task |

### 5.3 Agents that cannot resolve a task

Acceptance (§2) does not depend on any of this. An agent can accept a task it
has no dependable way to resolve: an `llmagent` agent with tool calling off,
which can resolve one only by writing the call as text, or a custom transition
that never calls the §5 operations. Such a task fails when its first cycle ends
(§6).

## 6. Yielding

A cycle that ends with its task open has yielded. The task is parked if
something will bring it back:

- an open human ask made for it;
- a call from its cycle still running past its time limit;
- a wake time returned by the transition;
- input or session messages that arrived during the cycle.

The run loop passes over a parked task until something new arrives for it.
With none of these, the task fails at once.

## 7. Restarts

Open tasks and their Jobs survive a restart. A task resolved in a cycle the
restart stopped is handed back at boot, and one interrupted mid-cycle runs
again. A parked task keeps waiting only if what it waits on survives
([AGENT_LOOP.md](./AGENT_LOOP.md) §6.1).
