# Asking an agent

When someone other than its owner asks an agent to do something — over A2A or
any other way — who may ask, and with what authority does the agent then act?
[A2A_AGENTS.md](./A2A_AGENTS.md) §"Resolution and authorisation" defers to this
document. For the agent model see [AGENT_SESSIONS.md](./AGENT_SESSIONS.md); for
the capability primitives see [UCAN.md](./UCAN.md).

## Principle

Admission answers one question: who may ask this agent? Whoever is allowed to
ask can ask, through any door. The agent then acts as itself: under its own
name, `<owner>:g:<agentId>`, with its own authority, `config.caps`, deciding
what to do with the request. Nothing narrows or widens that authority
according to who asked.

An LLM agent's reading of a request can be talked round, so `config.caps` is
what the owner offers everyone the agent admits. A bound that depended on the
asker would limit what the agent does without limiting what it knows: one
agent has one memory and one workspace, and serves every pending request and
message in the same cycle. The unit of isolation is the agent, so an owner who
trusts different askers differently gives them different agents, each with its
own state.

## Who may ask

One check serves every door — REST and MCP (`agent:request`, `agent:message`
and the operations built on them), the per-agent A2A endpoint, and chat
bindings. It is the single cross-user gate, `Engine.crossUserAllows`, for
`agent/request` or `agent/message` on `<owner>/g/<agentId>`:

- the **owner**, always;
- a caller the agent's **admission policy**, `config.accepts`, admits:
  `"venue"` (the operator and its agents), exact principal DIDs, or
  `"anyone"`, which includes anonymous callers (`Admission`);
- a caller presenting a **UCAN** granting `agent/request` or `agent/message` on
  the agent (over A2A, in `X-Covia-Ucans`).

Every request comes from a principal. A door that identifies callers itself
says which: a chat binding's message comes from the binding's principal when
its allow-list names the sender, and is anonymous otherwise
([CONFIG.md](./CONFIG.md#who-a-chat-message-comes-from)). A binding an agent
set up identifies its senders as that agent, never its owner.

The asker's own authority still applies to the act of asking: it must allow
invoking the operation that asks. An anonymous caller asks as the venue's
public principal, under the operator's `auth.public.caps`; the read-only
default grants no `invoke`, so anonymous askers are refused until the operator
opts in ([CONFIG.md](./CONFIG.md#a2a-protocol)).

Setting an admission policy hands on the right to ask, so `agent:create`,
`agent:update` and `agent:fork` refuse a policy that admits others unless the
author holds `agent/request` and `agent/message` on the agent
(`Engine.requireConferrable`).

`a2a: {public: true}` publishes the agent's card and admits nobody. An older
config with `a2a.caps` on a public agent reads as `accepts: "anyone"`
(`Admission.policy`); `a2a.caps` bounds nothing. Beside an `accepts` that says
otherwise it would silently override the owner's policy, so create and update
refuse that config (`Admission.configProblem`).

## What the agent does then

- **The request is the asker's.** It is the asker's own Job, recorded under
  the asker's name — the venue's public principal for an anonymous caller. The
  asker follows and cancels it under their own authority; they reach nothing
  else of the owner's. The per-agent A2A endpoint's task calls answer only for
  the asker's own tasks submitted to that agent, and refuse every other id
  alike, so the endpoint is no oracle for other jobs; the owner reaches its
  other jobs on the front door.
- **The agent serves it as itself.** The run is the agent sub-principal
  bounded by `config.caps`, exactly as for a request from the owner.
- **The agent knows who asked, and how.** The task records the asker as its
  caller. When a channel binding authenticated the asker, the request context
  carries the authentication record (AUTH.md §5.3), and the agent's ingress
  records it beside the request, never in it, so nothing a caller sends can
  pose as one.

Every anonymous caller is the one public principal, so they share its jobs:
what any of them may do with an anonymous task is what the operator's public
scope allows. A job id is not a credential.

## Not yet on this model

- **Sessions** do not check who joins: a caller who may ask and knows a
  session id can add to that session.
