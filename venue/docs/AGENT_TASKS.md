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

A request is refused if the asker may not ask the agent (`config.accepts`, or
the `agent/request` ability in [UCAN.md](./UCAN.md)), if the agent does not
exist, or if it is suspended. Otherwise it is accepted; nothing checks whether
the agent can resolve a task (§5.2).

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
| Completed | `COMPLETE` | The agent resolved it with a result, or a cycle ended on it with a reply (§5) |
| Failed by the agent | `FAILED` | The agent resolved it with an error (§5) |
| Reply refused | `FAILED` | A cycle's reply did not conform to a `strict` task's `responseSchema` (§5.2) |
| Cycle failed | `FAILED` | The cycle working on it failed, for example at the tool-call iteration limit or on a request the provider refused ([AGENT_LOOP.md](./AGENT_LOOP.md) §3.4) |
| No reply | `FAILED` | A cycle ended without a reply, leaving the task open with nothing to bring it back (§5.2) |
| Stuck | `FAILED` | A run presented it for its whole iteration budget without resolving it |
| Wait lost | `FAILED` | It was parked on something that a restart or a session's removal ended |
| Cancelled | `CANCELLED` | `agent:cancelTask`, whose optional `reason` becomes the Job's error, or cancelling the request Job. A cycle already working on the task is not stopped |

An agent that cannot continue is suspended instead, and the task stays queued
for after it resumes ([AGENT_LOOP.md](./AGENT_LOOP.md) §3.4).

Resolving moves the task out of the queue and records its outcome on the agent
in one transaction, so it cannot be resolved again or reached by
`agent:cancelTask`, and the outcome survives a restart or a later failure of
the same cycle. The Job settles when the cycle's work merges, so the asker sees
the cycle's writes first; cancelling the Job before then still cancels it and
discards the outcome. The cycle's timeline entry records the outcome in
`taskResults`.

## 5. Resolving a task

A cycle settles its task in one of three ways: it resolves it (§5.1), parks it
to come back later (§6), or ends with a reply, which is then the result (§5.2).

### 5.1 By the resolving operations

`agent:complete-task` (`result`) and `agent:fail-task` (`error`) resolve the
task of the cycle that calls them. They take no id. Only a cycle with a task in
scope can use them, and an agent's capability limits do not restrict them.

When the task is `strict`, a result that does not conform to `responseSchema`
is refused, and a string that parses as conforming JSON is taken as that value.

### 5.2 By the cycle's reply

A cycle that ends with its task neither resolved nor parked, and nothing to
bring it back (§6), settles it with its reply: the task completes with the
reply as its result, judged as `agent:complete-task` judges one, so a `strict`
task whose reply does not conform fails with the reason. A cycle with no reply
fails the task (`AgentAdapter.TASK_YIELDED_IDLE`).

So acceptance (§2) needs nothing from the agent: one with no dependable way to
call the §5.1 operations, such as an `llmagent` agent with tool calling off or
a custom transition that never calls them, completes a task by replying.

### 5.3 From an LLM agent

Both runtimes wrap the operations as the harness tools `complete_task`,
`fail_task` and `park_task` (§6).

- **Offered** at the root frame on every inference, whether or not a task is
  in play, because the tool list is the start of the cached prompt. Never in a
  child frame. With no task in scope, a call is refused.
- **With tool calling off** for the model, no tools are presented.
- **Written as text**, a `complete_task` or `fail_task` call is honoured while
  a task is outstanding, when the text is the tool's name, alone or followed by
  a JSON object.
- **An empty `result` or `error`** falls back to the text of the same turn. With
  no text either, the call is refused and the model can try again.
- **A rejected result** returns the reason and the schema to the model, which
  can try again. It does not stop later calls in the batch.
- **A successful `complete_task` or `fail_task`** ends the batch: later calls
  in it are not run. `park_task` does not: the turn carries on, and its reply
  goes to the session's conversation.

The outstanding-task message shown on each inference names the three tools
and says that a plain reply is the task's result.

### 5.4 Where the runtimes differ

| | `llmagent` | `goaltree` |
|-|------------|------------|
| After a successful call | The loop carries on with every tool still available, until the model replies in text: at least one more inference. The task's result is what the call delivered and is also the cycle's response; the closing text goes only to the session's history | The frame ends with no further inference |
| A final text reply with the task neither resolved nor parked | The run loop settles the task with it (§5.2), unless something will bring the task back (§6) | The runtime completes the task with it through `agent:complete-task`, even when something would bring the task back |

## 6. Parking

A task can stay open across cycles. It is parked, and the run loop passes over
it until something new arrives for it, when a cycle ends with it open and any
of these will bring it back:

- a wake time: one set by `agent:park-task` during the cycle, one returned by
  the transition, or one set earlier that had not come when the cycle took
  the task;
- an open human ask made for it;
- a call from its cycle still running past its time limit;
- input or session messages that arrived during the cycle.

The cycle's reply then goes to the session's conversation, not to the task.

`agent:park-task` takes `delay`, a positive number of milliseconds, and an
optional `reason`, which becomes the task Job's progress for its requester to
see. Like the §5.1 operations it takes no id and needs a task in scope. It
returns `{agentId, taskId, status: "STARTED", wakeTime}`, and the task comes
back at `wakeTime`, or sooner if new input arrives for it. Brought back
sooner, it keeps its revisit unless that cycle resolves it or parks it again,
so a reply there goes to the conversation; at the revisit, a reply settles it
(§5.2). A task resolved, failed or cancelled first is not brought back: its
wake goes with it.

## 7. Restarts

Open tasks and their Jobs survive a restart. A task resolved in a cycle the
restart stopped is handed back at boot, and one interrupted mid-cycle runs
again. A parked task keeps waiting only if what it waits on survives
([AGENT_LOOP.md](./AGENT_LOOP.md) §6.1).
