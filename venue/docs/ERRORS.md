# Errors

How the venue raises, types and reports failures. Each surface's own contract
(REST statuses, MCP and A2A error shapes, job records) lives in that surface's
document; this one covers what they share.

## Expected failures and faults

An **expected failure** is one a caller can cause or act on: bad input, a
missing thing, a refusal, a dependency that is down. It reaches the caller as a
`covia.exception.CoviaException` subtype whose message says what failed, why,
and what the caller can do, in words a person or a model can act on, with no
class names. The wording is a constant on the class that owns it, and tests
assert the constant, never the wording.

A **fault** is a failure in code the request ran that the caller cannot fix.
It reaches the caller as a `FaultException`, a scope's report of the fault in
its code, and every surface answers it as a server failure.

A `CoviaException`'s message is always written for the caller:
`CoviaException.reasonOf` returns it, and anything else has no reason to give.

## Scopes

A failure is reported by the scope it happens in. Each scope catches what
escapes its own code. A Covia exception with a reason passes through
unchanged. Anything else (a JDK exception, a library exception, an `Error`) is
an unexpected failure of that scope. The scope logs it once, at ERROR with its
stack, and reports it as its own failure (`FaultException.within`): what
failed, then `unexpected <ClassName> in <owner>`, naming whose code it was.
The caller never sees the exception's message, since a library's text may
carry a remote's response body or a credential. Outer scopes pass the report on
unchanged and do not log it again.

| Scope | Where it is caught | The report |
|---|---|---|
| An adapter running an operation | Where the operation's outcome becomes its Job's: `Job.fail(Throwable)` through `Job.report`, from `AAdapter.invoke`, `settleJob`, `JobManager.start` and the adapters that run their own Jobs | `Operation <op> failed: unexpected <ClassName> in adapter <name>`, where `<op>` is the operation as the caller referred to it |
| An adapter taking a message for a running Job | `JobManager` message delivery | `Message delivery to job <id> failed: unexpected <ClassName> in adapter <name>` |
| An agent's transition | `CycleRecord.Failure.of`, in the adapter running it | `Transition failed: unexpected <ClassName> in adapter <name>` |
| An agent's tool call (finding the tool, parsing arguments, built-in tools) | `AbstractLLMAdapter.toolFailure` | `Tool <name> failed: unexpected <ClassName> in the venue`; a goal-tree subgoal names its adapter |
| A venue request (REST, MCP, A2A, other HTTP routes) | The route's catch and the server's catch-all | `Request <method> <path> (id <request id>) failed: unexpected <ClassName> in the venue`; MCP and A2A name the tool call, method or stream |
| The venue's own work around an operation (starting its Job, checking or recording its output) | `JobManager`, `VenueJob`, `AAdapter.completeFromJobFuture` | `Starting the job`, `Checking the operation's output` or `Recording the operation's outcome failed: unexpected <ClassName> in the venue` |

So an adapter that throws `IllegalArgumentException` for input it rejects fails
its operation as the adapter's fault, not the caller's: the caller is told the
operation failed in that adapter, and the adapter's author fixes it by throwing
`InvalidInputException` ([ADAPTERS.md](ADAPTERS.md#writing-an-adapter)).

A scope that states the cause after its own words for what failed ("Agent
create failed: …") uses `AAdapter.describeFailure`, which gives a reason or
`unexpected <ClassName> in adapter <name>` and logs the latter. A scope that
logs the failure itself, such as a bot's handler, describes it with
`FaultException.describe`, which does not log.

A fault a scope finds rather than catches, such as an operation's output that
breaks its declared schema under strict output validation, is a
`FaultException` with the scope's own message.

A surface's answer depends on the situation its contract describes, not on the
exception's type. The type decides only whether the caller sees an expected
failure's reason or a fault's report.

## Messages

A message is written for the caller. It says what failed in the venue's words,
and may follow that with a remote's own error message, marked as the remote's.
The venue's words never carry a credential, a secret value, another principal's
data, a URL that may carry a credential, or a stack trace. A failure from a
remote service says what failed and its status; the response stays on the
exception (`ResponseException.response`) for code that needs it. A remote is
named by its URL's origin (`AAdapter.origin`), since a path or query may carry
a credential. A remote's error code or request id is quoted only when it is an
identifier (`AAdapter.identifier`).

A model provider's error is passed on exactly as the provider sent it, on every
status and whichever key was used, after the venue's words and the status
(`LangChainAdapter.providerNote`). Whoever may use a key may see what the
provider says about it, and a provider that puts a secret in an error message
has made its own mistake, so the venue neither edits nor shortens the text.

A database's error text may quote stored values, so the `sql` adapter never
passes it on. The caller gets what failed, the SQLState and vendor code, and,
from convex-db's Calcite parser and validator, the message for a statement it
cannot parse or validate: that is checked before any row is read and quotes
only the caller's own statement. The venue logs the database's full text.

A remote MCP tool that reports an error has answered the call, and its error
text is the tool's output. The `mcp` adapter fails the call with that text,
attributed to the tool by name; a failure to reach or call the server follows
the rules above.

## Types

| Type | Meaning |
|---|---|
| `InvalidInputException` | The caller's input is wrong; the message names the field or value and why |
| `InvalidDefinitionException` | A definition the request uses (an operation, orchestration, agent or skill) is malformed; not the caller's direct input |
| `NotFoundException` | A named thing does not exist; carries the reference |
| `NotSupportedException` | This venue or adapter does not do this, or the target does not take the request in its current state |
| `ConflictException` | Two values conflict and cannot both stand, such as a new value where one already exists |
| `UnavailableException` | Something the request depends on failed or could not be reached; retrying later may succeed. `RemoteFetchException`, `ShuttingDownException` and `StillStartingException` refine it |
| `ResponseException` | A remote service answered with an error or a malformed response |
| `AuthException` | The caller is not authenticated or not authorised |
| `WrongScopeException` | A scope-bound namespace (`n/`, `c/`, `t/`) is used outside the scope it needs |
| `ConfigurationException` | The setup a request runs under is wrong or incomplete: the operator's configuration, or the settings and credentials the caller supplied for a dependency (a refused key, a missing model). Every request made the same way fails until it is fixed |
| `RateLimitException` | An admission or rate control refused the request; carries a retry hint |
| `JobFailedException` | A job the caller awaited failed; carries the job |
| `FaultException` | A fault in code the request ran, reported by the scope it happened in; names what failed and whose code it was. Not the caller's to fix |

Invalid input and invalid state are different. A bad value in the request is
`InvalidInputException`. A request the target's current state does not allow,
such as a message to a finished job or a resume of a job that cannot pause, is
`NotSupportedException`, or `UnavailableException` when the situation can clear
by itself. A request the state forbids is not a conflict.

## Where a failure is typed

**At the level that knows what was being attempted.** Code that knows what its
caller asked for throws the Covia type: an operation checking its input, a
handler refusing a request.

**A utility may throw a JDK exception.** A utility or parser that does not know
who called it may throw `IllegalArgumentException` for an argument it cannot
take. The code that called it catches it and decides: handle it, or rethrow as
the Covia type for what it was attempting, with the cause chained.
`ToolCallArguments.parse` is one; `AbstractLLMAdapter.parseToolArguments`, which
knows the arguments came from a model, rethrows as `InvalidInputException`. One
that escapes instead is a fault of the scope it escapes.

**A shared parser leaves the choice to each caller.** An adapter's `configure`
throws `IllegalArgumentException` for a malformed setting
([ADAPTERS.md](ADAPTERS.md)). The Engine rethrows it as
`ConfigurationException` when the settings come from the venue's configuration,
and as `InvalidInputException` when an operator supplies them through
`venue:adapter/configure`. A messaging bot spec read from operator config and
from a user's create operation works the same way.

**Library exceptions are wrapped where the call is made.** Convex parsing, JSON,
IO and HTTP client failures are caught by the caller and rethrown as the type
for what was being attempted, with the cause chained.

**Stored data the venue cannot read is an expected failure.** A record that is
damaged or was sealed under a key the venue no longer holds, such as a secret
or a connected account, is refused with `NotSupportedException`. The message
names the record, never its content, and says who can fix it and how: the
owner by setting it again, or the operator by restoring the key or removing the
record. When such a record is used, rather than read out by its owner, the
venue also logs a warning naming the user and the record, so the operator learns
of a lost key.

**Internal invariants stay JDK exceptions.** "Called before prepare", "already
started" or an impossible state are bugs, and `IllegalStateException` reports
them as such: a fault of the scope it escapes.

**Checks return reasons.** A check returns `null` when it passes and a reason
when it does not (`CapabilityChecker.allows`, `VenueAuthenticator.Verdict`). Its
caller turns the reason into the type that fits.

**Catch narrowly.** A catch covers only the step whose failure it handles.
Catching a JDK type around a wide block turns a bug deep inside into an
expected answer.

**Awaiting.** `AAdapter.await` rethrows a failed future's runtime cause
unchanged. A timeout becomes `UnavailableException`, and an interrupt a
`CancellationException`. A checked cause stays wrapped, as the fault it is,
for the scope awaiting to report.

## Surfaces

| Surface | Expected failure | Fault | Contract |
|---|---|---|---|
| REST | The status the route lists for the situation, with the reason as the error body's `detail` (RFC 9457 problem details, written by `ApiErrors`); a refusal the route does not list is a 400 | 500 with the report as `detail`; one the venue reports for the request names its id (`X-Request-Id`) | COG-21; [READ_API.md](READ_API.md) for `/values` |
| Jobs | `FAILED` with the reason as `error`, and an `errorCode` where the adapter defines one | `FAILED` with the report as `error` | COG-8; [JOBS.md](JOBS.md) |
| Agent tool loop | An `Error:` result carrying the reason | An `Error:` result carrying the report | [AGENT_LOOP.md](AGENT_LOOP.md) |
| MCP | A tool result marked `isError` carrying the reason | A tool result marked `isError` carrying the report | [OPERATIONS.md](OPERATIONS.md#mcp-exposure) |
| A2A | The JSON-RPC error the situation maps to | `INTERNAL` with the report | [A2A_AGENTS.md](A2A_AGENTS.md#errors) |
| Other HTTP routes (sign-in, OAuth, WebDAV, embedder routes) | The route's own answer; a refusal it does not answer itself is a 400 with the reason | 500 with the report, which names the request id; HTML for a browser, Javalin's JSON otherwise | [CONFIG.md](CONFIG.md) for request ids |
