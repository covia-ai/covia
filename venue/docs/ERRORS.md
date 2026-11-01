# Errors

How the venue raises, types and reports failures. Each surface's own contract
(REST statuses, MCP and A2A error shapes, job records) lives in that surface's
document; this one covers what they share.

## Expected and unexpected failures

An **expected failure** is one a caller can cause or act on: bad input, a
missing thing, a refusal, a dependency that is down. It reaches the caller as a
`covia.exception.CoviaException` subtype whose message says what failed, why,
and what the caller can do, in words a person or a model can act on, with no
class names. The wording is a constant on the class that owns it, and tests
assert the constant, never the wording.

Anything else that reaches a surface (a JDK exception, a library exception, an
`Error`) is an **unexpected failure**: a runtime or venue bug. The surface logs
it at ERROR with its stack. The caller is told only that an unexpected venue
error occurred and its class, never its message: a library's text may carry a
remote's response body or a credential.

`CoviaException.reasonOf` tells the two apart: a `CoviaException`'s message is
an expected failure's reason, and anything else has none.
`CoviaException.describe` renders either for a caller, as the reason or
`Unexpected venue error (ClassName)`. A surface's answer depends on the
situation its contract describes, not on the exception's type. The type decides
only whether the caller sees the reason or a venue fault.

## Messages

A message is written for the caller in the venue's own words. It never quotes
a remote's response body or free-text error detail, and never carries a
credential, a secret value, another principal's data, a URL that may carry a
credential, or a stack trace. A failure from a remote service or provider says what
failed and its status; the response stays on the exception
(`ResponseException.response`) for code that needs it. A provider's error code
or request id is quoted only when it is an identifier (`AAdapter.identifier`). A model reads these
messages, so a remote's text quoted in one would reach it as the venue's own
statement.

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
| `ConfigurationException` | The operator's setup is wrong or incomplete for what was asked |
| `RateLimitException` | An admission or rate control refused the request; carries a retry hint |
| `JobFailedException` | A job the caller awaited failed; carries the job |

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
knows the arguments came from a model, rethrows as `InvalidInputException`.

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

**Internal invariants stay JDK exceptions.** "Called before prepare", "already
started" or an impossible state are venue bugs, and `IllegalStateException`
reports them as such.

**Checks return reasons.** A check returns `null` when it passes and a reason
when it does not (`CapabilityChecker.allows`, `VenueAuthenticator.Verdict`). Its
caller turns the reason into the type that fits.

**Catch narrowly.** A catch covers only the step whose failure it handles.
Catching a JDK type around a wide block turns a bug deep inside into an
expected answer.

**Awaiting.** `AAdapter.await` rethrows a failed future's runtime cause
unchanged. A timeout becomes `UnavailableException`, and an interrupt a
`CancellationException`. A checked cause stays wrapped, as the venue fault it is.

## Surfaces

| Surface | Expected failure | Contract |
|---|---|---|
| REST | The status the route lists for the situation, with the reason as the error body's `detail` (RFC 9457 problem details, written by `ApiErrors`); a refusal the route does not list is a 400, and an unexpected failure a 500 | COG-21; [READ_API.md](READ_API.md) for `/values` |
| Jobs | `FAILED` with the reason as `error`, and an `errorCode` where the adapter defines one | COG-8; [JOBS.md](JOBS.md) |
| Agent tool loop | An `Error:` result carrying the reason | [AGENT_LOOP.md](AGENT_LOOP.md) |
| MCP | A tool result marked `isError` carrying the reason | [OPERATIONS.md](OPERATIONS.md#mcp-exposure) |
| A2A | The JSON-RPC error the situation maps to | [A2A_AGENTS.md](A2A_AGENTS.md) |
