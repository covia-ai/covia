# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

### Changed

- LangChain4j 1.23.0, A2A 1.5.1.Final, Nimbus OAuth/OIDC SDK 11.39, CommonMark 0.32.0; the Documents module uses Apache POI 6.0.0.
- Disabling a messaging adapter (Telegram, Discord, Slack, WhatsApp) stops its bots at once, closing their polls and connections, and enabling it starts them again; a replaced one's bots stop before its replacement's start.

### Fixed

- `user:delete` no longer leaves cleanup pending for an adapter registered while it runs.
- Stopping or reconfiguring a Telegram bot just as it polls no longer leaves that poll running for up to 30 seconds or holds up the change for 5.

## [0.11.0] - 2026-11-10

### Added

- Agents have a `controller`, the principal whose authority they act with, narrowed by their `caps`: whoever creates or forks the agent, or one it names that the creator holds the authority of. Changing an agent's config or controller needs that controller's authority; `agent:info` and `agent:list` show it.
- `user:info` and `user:list` report each user's storage: workspace and DLFS drives, in bytes.
- `agent:history` reads a session's history exactly as stored, by position; `agent:sessionRead` stays for existing callers (#597).
- Agent `accepts: "anyone"` admits every caller, anonymous ones included.
- Agent template `v/agents/templates/admin`: an unrestricted administrator that asks its owner through HITL before anything destructive, irreversible, security-sensitive or externally visible.
- An LLM agent's context names who each turn comes from (COG-20 §4).
- `ucan:issue` takes `prf` to pass on part of a grant made to the venue (#636).
- `outbound/any`: the venue's standing to dial any outbound target, delegable by grant.
- Venues prove their DID by address or challenge (`auth.venueProof`, `auth.requireVenueProof`).
- An agent tool call past `toolCallTimeoutMs` names the recorded job it runs as, and its outcome reaches the agent when it ends, citing the call, after a restart too (#607).
- Model option `lateToolResults: "tool"`, also set in an agent's `modelProfile`, shows a late tool call's outcome as a `tool_call_outcome` tool result instead of a message.
- `recordToolCalls`, per agent with a venue default, records an agent's tool calls as jobs from the start.
- An MCP read-only tool call past its ceiling names a job that `grid_job_status` and `grid_job_result` can find.
- Per-user preferences at `x/preferences`, edited with the `preferences` tool and pinned by the standard and chat-bot agent templates (`adapters.preferences.maxBytes`).
- Keyrings: the venue holds keys and recovery phrases that can be used but never read, with `crypto:add-key`, `crypto:remove-key`, `crypto:list-keys` and the `key/…` abilities (#637).
- `crypto:sign`, `jwt-sign`, `decrypt`, `convex:transact` and `ucan:issue` take a held `key`; `crypto:generate-key`, `derive-key` and `generate-mnemonic` take `keyring: true` (#637).
- `auth:identity-token` signs for a `did:key` that holds its own key, and the identities nested below it (#637).
- `ucan:issue` results name their issuer as `iss`.
- Agents hold keyrings of their own, removed when the agent is deleted (#637).
- Agents can issue grants over their own resources and their owner's, and owners over their agents'.
- Audit events `keyring.add`, `keyring.remove` and `keyring.use`.
- `scheduler.maxConcurrency` bounds how many due scheduled events a venue starts at once, 2 by default (#671).
- `hitl:expire`, which the venue alone invokes when a HITL request's timeout falls due (#671).
- `agent:park-task` and the LLM agent tool `park_task` keep a task open and bring it back after a delay, or sooner when new input arrives for it; the agent's reply after a park is the task's progress, its working state over A2A, and the task goes to the back of the agent's queue.

### Changed

- Agents explicitly distinguish ordinary chat from assigned tasks before using task-completion tools; context previews include the live reply guidance.
- HTTP redirects recheck credential grants at each destination, preventing same-origin redirects from escaping a granted URL path.
- Outbound HTTP URLs are normalised before destination checks and sending, preventing dot-segment escapes from URL-scoped credential grants.
- `invoke` scopes generic operation lookup by location; direct tools use their action permissions without an invocation grant. Rebuild adapter modules with the venue.
- Requires Convex 0.8.19.
- `agent:message`, `agent:cancelTask` and a job named in an agent's context take an id with or without `0x`, as other id inputs do (#669).
- Whitespace around a hash or hex id in a path or asset reference no longer names the same thing.
- An agent of the venue's holds venue authority as far as its caps reach, all of it when unrestricted; `agent:info` shows `caps: "unrestricted"`.
- Padded values are refused where they were trimmed: `http` `method`, `wait` and integer query parameters, `pages`, `skills:import` `skillset`, MCP server URLs, venue references, `oauth:connect` `scopes` and `returnTo`, `telegram:call` `method`, Telegram targets and speech voice numbers.
- Configuration values are read exactly as written: whitespace around one is no longer removed.
- A leading slash changes nothing in any resource name, including a capability's `with` and before a DID.
- `asset:get`, `asset:getContent` and `asset:pin` check a path as a path: reading one needs `crud/read`.
- `covia:read`, `list`, `slice`, `inspect` and `aggregate`, and the `/values` routes, refuse an empty path, `/` or a DID alone, naming the namespaces to use instead: a user's record is never returned whole.
- A venue roots grants over every identity it hosts, self-sovereign users included, valid at that venue only.
- `ucan:issue` hands on only what its caller could grant: what it is root of, where its caps include `grant/<can>`, or what a presented `grant/<can>` right covers, through the grant's expiry.
- An agent's `caps` and `accepts` take no check of their own when set: `caps` can never exceed the authority of the agent's controller, so what an agent creates stays within what it can do.
- A controller has authority over the whole of the agents it controls (`g/<agent>/`), whatever its own `caps` say.
- A bare `n/` in an agent's `caps` names that agent's own notes; it used to name nothing.
- A `did:key` roots grants over the identities nested below it, at any venue; `ucan:verify` reports such a root as `owner`.
- The context an agent's config names (`config.context`, `config.loads` and the skills index) is assembled by the harness with its controller's authority, as the system prompt already was; the agent's tool calls, and the loads it or a caller adds, stay under its `caps`.
- Asking an external agent's backend (an A2A endpoint, a Claude Code project, another Covia agent) acts with the agent's controller's authority, as a native agent's thinking does, not under its `caps`; the backend job keeps that authority across a restart.
- `auth:identity-token` takes any `lifetime` the caller chooses, no longer capped at an hour.
- `ucan:issue` reads a bare `with` in the namespace the request acts in.
- Job records name the principal that made the call as `caller` and its namespace as `owner`, present only when it differs; `actor` is gone.
- Scheduled events name `caller` and `owner`; `scheduler:list` shows them in place of `by`.
- HITL requests name the asker as `caller`, not `from`, and a delegate's or agent's namespace as `owner`; `hitl:list` shows `caller`.
- A capability gate is told `{operation, input, caller, owner?}`, where `caller` is the principal that acted, also for a held grant.
- A chat bot an agent created names it `caller`, not `principal`.
- A Claude Code session restored after a restart acts as the principal that started it, with the grants it presented, and every turn checks project access again.
- A `remoteagent` call to an agent on the same venue, and an external agent served by one, resumes after a restart as its caller, with the grants it presented; one whose caller cannot act then fails, naming the agent's task.
- An agent's `outputPath` write acts as its requester stands at write time, after a restart too; the worker's task no longer records `outputPath` or the requester's context.
- A request an agent has completed or failed is settled with it after a restart, its `outputPath` written; while its cycle runs, the agent's record shows it under `resolved`.
- An agent's running work (orchestration steps, tool calls past their timeout, live Claude Code sessions, the rest of a cycle) acts under its caps as they stand at each check, and under none once it is deleted; `agent:update` no longer warns that a running transition keeps its old caps.
- The venue migrates to version 2, renaming those fields in stored records once.
- User exports are at version 2; `user:import` converts older ones, and `user:import` and `user:clone` leave out the user's jobs.
- A `covia` path given as an array takes strings only, none containing `/`, a list position written in digits as in a string path; any other element is refused (#617).
- The covia read operations and `/values` return the value at a path as stored; a path to a whole account reads its record with the venue's bookkeeping, whose layout may change.
- The covia read operations and `/values` refuse a path that names no place data could be, such as `wrok/notes` or a job id that is not hex, naming the part of it where nothing can be, where they returned nothing (#617).
- A DID starts a path only as its first element: `/did:…` and `<did>/n/`, `c/`, `t/` or `v/` name no place, and an array whose first element is a DID is that DID URL (#617).
- `v/` is the venue's public read-only view: any principal reads it, whatever its caps, and nothing changes through it (#617).
- An agent reaches its session's `c/`, and work running in a job its `t/`, without a grant; a job's record needs its owner or a grant (#617).
- A path given where an asset is wanted, as by `/assets` and `/content`, is read as data and needs `crud/read`; a bare hash is the caller's asset when their store holds it, otherwise the venue catalog's (#617).
- A user's `meta`, `generation`, `deleting` and `deleted` are venue bookkeeping that no path reads (#617).
- `user:info` and `/users/{did}` show a user's `meta` to operators only, and `user:export` leaves it out.
- A task an agent leaves open waits for a wake time, its HITL ask or a late tool call; with none, the agent's reply is the task's result, judged against a strict `responseSchema`, and a cycle with no reply fails it.
- An agent takes one chat per cycle, with the messages queued ahead of it in its session, and its reply answers that chat alone; a task's cycle takes no chat, and a chat whose cycle ends without a reply completes with no `response`.
- A `goaltree` agent's task is settled as an `llmagent` agent's: its final reply completes the task only when nothing, such as an open HITL ask, will bring the task back.
- An agent takes its work oldest first across tasks and chats; tasks used to go first.
- A forced `agent:trigger` no longer presents a parked task.
- An LLM agent's job results show an open HITL ask as still waiting, not as ended with no reason.
- A HITL answer or a late tool call's outcome goes to the task that waited for it, not to whichever task in its session runs next.
- A message sent to a task with `agent:message` is shown to the agent as from its sender, not the task's requester.
- An agent is suspended only when it cannot continue: its model setup is wrong (an `llmOperation` or `systemPrompt` entry that does not resolve among it), its provider cannot be reached, or the venue fails. Other failures, `fail_task` among them, fail only the work (#652).
- `agent:context` and `agent:step` fail with the reason when the agent's `llmOperation`, or its model's provider, does not resolve.
- A suspended agent keeps its queued work; waiting jobs say it is suspended, and the work runs after `agent:resume`.
- Cancelling an `agent:request` job takes its task out of the agent's queue.
- An agent can call only the harness tools it is offered; any other is answered as not offered.
- Goal-tree subgoals nest, up to nine below the root, and every subgoal can return with `complete` or `fail`; only the root resolves the task.
- Context, skill and tool loads work in a call with no session, and last for that call.
- Goal-tree agents are no longer told to compact after 20 turns; in both runtimes the context-budget warning alone prompts compaction.
- A login being verified when an adapter is enabled, disabled, replaced or reconfigured completes instead of failing; later logins use the new configuration.
- `covia:write`, `append` and `copy` need write authority at the nearest existing level of a path whose missing levels they create (#617).
- Listings need `crud/read` on the collection they list: `agent:list` and `GET /api/v1/agents` on `g/`, `hitl:list` on `h/`, and `GET /api/v1/jobs` on `j/`. `/status` leaves out the caller's job count without it.
- The venue keeps no caller's sign-in credential for a remote job: after a restart, a grid or remote-agent job that signed in with `authenticateAs: "caller"` ends, naming the remote job and its venue.
- A scheduled grid hop with `authenticateAs: "caller"` fails, saying to schedule it at the target venue.
- After a restart, an outbound A2A job reads its bearer secret again, so a rotated secret is used; one sent with a literal `bearerToken` ends, naming the remote task.
- After a restart, an outbound A2A job whose caller can no longer use its bearer secret (deleted, or another user's used under a grant) ends, naming the remote task, instead of waiting as `auth-required`.
- A job keeps the secret fields and output schema its operation declared when it was admitted, across a restart and later changes to the operation.
- The venue recovers jobs from its own entries for unfinished jobs, which venue version 2 adds on upgrade.
- Job records no longer carry `delegation.authRef`, and an external agent's job no longer shows `agentOwner`, `agentInteraction` or `agentBackendJob`.
- Chat jobs for messages a chat bot hands to an agent on its venue no longer show `deliveryAccepted`.
- An A2A send's job names its remote task only as `delegation.remoteId`; `remoteTaskId` is gone.
- A remote job's record shows only `delegation.remoteId`, `delegation.remoteStatus` and, once the venue stops following the remote job, `delegation.notFollowed` saying why; `protocol`, `target`, `ownsRemote` and observation health are gone, from older records too (venue version 2).
- The venue keeps no `remote-job-<id>` secrets; upgrading removes them, those left by a crash included.
- Every DID is its own namespace; scheduled fires and restored contexts keep the one they recorded.
- `Engine.context` replaces `principalContext` and `publicContext`.
- `RequestContext.of(did, proofs)` removed; use `of(did).withProofs(proofs)`.
- Discovery routes stay anonymous while public access is off.
- Deleting an agent removes the events it scheduled.
- Scheduled events fire with their caller's authority at fire time; caps are no longer stored.
- `llmagent:chat` and `goaltree:chat` run as their agent under its current caps.
- Records that name only their caller act for an owner only through an existing agent.
- A venue shutting down answers 503.
- A message to an external agent whose backend is unloaded or whose definition no longer validates answers 503, not 404.
- Storing an asset or reading a user path unauthenticated through the REST routes answers 403, not 400.
- `/run` answers 403 only when the venue refuses the run; an operation that starts and is then refused fails with 400.
- A chat bot that is not running says whether it is starting, retrying or stopped.
- Compaction summaries name the turns they stand for.
- Session titles are the first message's words, not its JSON.
- Content uploads and downloads stream, checked against their hash (#629).
- A `claudecode:session` takes no pool slot until its first turn.
- Connected accounts complete from the account that started the connect.
- Every user turn in an agent's history keeps its caller; `config.recordCaller` is gone.
- A DLFS drive is created before use; only `createDrive`, WebDAV `MKCOL`, and built-in features that need their own drive (on their first write) create one (#641).
- `dlfs:createDrive` and `deleteDrive` take the drive in `drive`; `name` stays an alias.
- `file:move` needs `crud/read` and `crud/delete` on its source.
- Creating a workspace key, file, directory or drive needs `crud/write` on what holds it.
- A cross-user write reaches only a user the venue already has.
- Archive operations authorise as file operations do.
- Refusals that were plain `RuntimeException`s are Covia exception types; a plain `RuntimeException` is a fault.
- `t/` outside a job or task reads as absent, as `n/` and `c/` do.
- `VenueServer.launch` throws a startup failure as its own type.
- `ConflictException` means two values that cannot both stand; a job, session or interaction that does not take the request is `NotSupportedException`, still 409.
- A remote agent that answers for a different task answers 502.
- A fault is reported by the scope it happened in, naming whose code it was and the exception's type, never its text, and logged once (#644).
- An exception escaping an adapter, `IllegalArgumentException` included, fails its operation as that adapter's fault: `Operation <op> failed: unexpected <type> in adapter <name>`.
- Output that breaks its operation's declared schema under strict validation is the adapter's fault and answers `/run` with 500.
- A REST refusal a route does not list answers 400; a fault answers 500.
- A store collection cancelled by `venue:gc` ends CANCELLED.
- Model provider, MCP server and A2A failures are typed; a refused operator key is a configuration error, a caller's an input error.
- A2A params that do not fit their method answer invalid params; a fault on an A2A stream arrives as one error event.
- A secret or connected account the venue cannot decrypt is refused with how to fix it; a use of one is logged for the operator.
- SQL failures quote no database text: callers get the SQLState and vendor code, or convex-db's parse and validation message; the venue logs the rest.
- Error pages outside the REST API show a fault's report, which names the request id, never its message, and a refusal answers 400 (#540).
- `covia:list`, `covia:slice` and `covia:aggregate` name a blob key in `0x` hex, as a value's JSON shows it.
- A connection opened for a `did:web` DID compares it as did:web writes it: a port is `%3A8080`, while `:8080` names a path.
- An agent cycle stopped by `agent:suspend` or `agent:delete` can no longer complete or fail its task, and its result is not merged; the task runs again after resume.
- `agent:create` and `agent:update` warn of tools that name an operation on another venue, and no longer fetch them to check them.
- An agent fetches a tool on another venue only when it calls it, not on every turn; the model sees the tool entry's name and description.

### Deprecated

- `convex:sign` and `convex:generate-key`: use `crypto:sign` (with `encoding: utf8` for text) and `crypto:generate-key`.

### Removed

- The `venue/relay` ability: users hop with `authenticateAs: "caller"` and grants.
- `auth.audience`: replaced by `auth.requireAud`.
- `FileAdapter.resolve`, which resolved a root path without authorising it.
- `answered` in `agent:chat` results: each reply answers one chat.
- `agent:info`'s `unavailableTools`: reading an agent no longer resolves its tools. `agent:create` still warns of configured tools the agent cannot use, and the agent's context names them.

### Fixed

- A venue starts when one of its agents cannot be recovered, such as one whose controller cannot act: that agent is logged and left asleep with its work queued.
- `agent:info`, `GET /agents/{id}` and an agent's event stream answer for every agent, one whose controller cannot act included.
- A DID URL naming this venue, by its `did:web` too, or a user it hosts resolves here and is never fetched over the network.
- An asset's metadata by hash is read with `asset/read` or `crud/read` over it on every route, however the reference is written: `asset:get`, `GET /assets/{ref}` and operation definitions, as `covia:read` already did.
- A venue told to stop (SIGTERM, `docker stop`) stops taking requests before its final save, then closes completely, as the tray's Close and Exit do.
- An agent's session history keeps a failed tool call's whole message; the model is shown the first 1024 characters and how many more there are.
- A model provider's HTTP 403 no longer reports a refused API key, and model call failures carry the provider's error code and message exactly as sent.
- A HITL request fails with a message saying why when the person asked loses their account, and the agent that asked is told (#674).
- A HITL grant reaches the audit log only once the answer carrying it commits (#674).
- A request, chat or message sent while its agent is being deleted fails rather than waiting for ever, as does a chat on a session being deleted.
- Two concurrent `user:create` calls for one username with different `authenticationKeys` cannot both install them.
- A request's session, an A2A agent's asset and a cycle's token usage are added to the job record without losing a change made alongside.
- A goal-tree subgoal whose model call fails reaches its parent as a failed subgoal (`{status: "failed", result}`), and a failed child leaves the frame stack.
- A `goaltree:chat` agent with `config.caps` calls its model without an invoke grant for it, as an `llmagent:chat` agent does: the model call is the harness's, not the agent's.
- `agent:context` and `agent:step` on an `llmagent` agent show a task's request turn, as the live cycle sends it.
- `agent:step` answers `compact` with the turn count a live cycle gives.
- Cancel, pause, resume and messages by id reach a just-submitted job.
- REST errors share one body: RFC 9457 problem details (`application/problem+json`), with `error` repeating `detail` (COG-21).
- Cancelling a finished job answers its record, even once it has left memory.
- A shutting-down venue sends nothing new to remote venues; status checks, cancels and requests already sent get the shutdown grace window.
- A message to a job that does not take messages answers 409 instead of being dropped.
- A malformed job id or `ucans` field answers 400.
- Memory edits made at once by a user and their agents all take effect.
- Concurrent `agent:update` calls no longer undo each other's config changes.
- A chat is no longer answered with the reply to an earlier message (#654).
- A Claude Code session restored after a restart keeps the options its call asked for (model, tools, permission mode, limits, system prompt, JSON schema), and ends if its project no longer allows its permission mode.
- A path starting with a `did:web` DID that encodes its port as `%3A` names that DID's data (#617).
- `agent:cancelTask` with a task id that is not hex fails as invalid input.
- Waiting on a cancelled job fails with the reason it was cancelled.
- An agent cycle stopped by `agent:suspend` or `agent:delete` is recorded as stopped however its transition ends, even when the agent is resumed first, and frees its session.

### Security

- `authenticateAs: "venue"` is only for the venue itself.
- `secret/use` never yields possession through `crypto:derive-key` or `crypto:jwt-decode`.
- Venue-local SQL databases are per user; the public principal has none.
- An agent of the venue's public principal acts with that principal's authority, as any owner's agent acts with its owner's: within the public caps, its harness and its tool calls alike, and not at all while public access is off.
- A Claude Code session resumes only in its own project.
- Convex calls go to the operator's peer unless `adapters.convex.anyPeer` is set.
- Every outbound venue target passes the outbound guard, with a stricter address rule.
- Credentials go only to a venue proven to hold the DID they name.
- Bearer credentials must name the venue in `aud` by default.
- OAuth connections are sealed `oauth` adapter state, gated by `oauth/*` abilities and
  revoked when their account is deleted.
- Per-agent A2A requests and tasks belong to the asker (#619).
- Capability scopes bound job and scheduled-event access in every context.
- Chat bindings authenticate each sender; unlisted senders are anonymous.
- REST secret listing, operation lookup, job streams and agent lookup check the caller.
- `auth:identity-token` refuses restricted self-tokens and the public principal, and checks its invoke grant before it uses a key.
- Errors never quote a remote service's response body, free text or full URL, nor an unexpected failure's message.
- `covia:inspect` and `GET /values/inspect` check read access as `covia:read` does.
- `dlfs:deleteDrive` and `listDrives` check the drives they act on, whatever `path` the input names.
- `agent:step` and `agent:context` refuse a deleted agent, as other agent operations do.
- A delegate acting for a user (`user/sudo`) uses the user's secrets only under a `secret/use` grant.
- An agent's pinned context and skills index show only what its caps cover.
- An agent is unrestricted only when its config says `caps: "unrestricted"`; one created without `caps`, or given `caps: null`, has no authority.
- `agent:step` needs the new `admin/agent` ability on the agent; admission to talk to an agent and `agent/…` grants do not cover it.
- Using a secret needs `secret/use` on it whoever names it, the caller or the operation it invokes: an agent uses its owner's secrets only as its caps allow, and its harness as its controller may.
- Every route to data at a path checks it as the data's owner decides, by its DID address: a grant on `<owner>/g/<agent>/n/` covers that agent's `n/`, and one on `<owner>/j/<job>` that job's record and `t/` (#617).

### Migration

- Venue-local SQL databases from earlier versions are not carried over.
- The venue changes its catalog at `w/global/…`, which `v/…` shows; a write or delete through `v/…` is refused, the venue's own included.
- A call naming a Convex peer other than the venue's needs `adapters.convex.anyPeer`.
- A private-network `did:web` identity needs its host in `adapters.http.allowedHosts`.
- Grant `oauth/use` on `x/oauth/<provider>` instead of `secret/use` on `s/oauth/<provider>`.
- A public agent's `a2a.caps` becomes `accepts: "anyone"`; beside another `accepts` it is refused.
- A delegate that acts for a user through `user:sudo` needs that user's `secret/use` grant for each secret it uses.
- Changing the config or controller of an agent its owner controls needs the owner's authority: `agent/write` alone no longer allows it for a capped agent or a delegate, including one acting through `user:sudo`.
- A `user:sudo` delegate holding `agent/create` creates agents its target controls: grant the pair only where you would grant that user's own authority.
- An agent created by another agent, or by a delegate in another user's namespace without `user:sudo`, is controlled by its creator: it acts with the creator's authority narrowed by its `caps`, not the owner's, so a creating agent's caps must cover `invoke` on the new agent's transition and model operations and `secret/use` on the model's key, and bound what the new agent's tool calls can do.
- Every narrowed context, an agent with caps or a request under a restricted token, needs `secret/use` on each of its owner's secrets it uses: a `bearerSecret`, an `apiKey`, a model's key when it calls a model as a tool, a speech key, an MCP bridge's credential, or an A2A agent's when it calls one as a tool, a secret-backed signing key or seed. Grant it in the agent's caps, bound with `nb.url` where the secret is sent, and unbound for a local use such as signing. An agent still thinks, and an external agent still reaches its backend, with the keys its controller may use.
- Review `accepts` on external agents: whoever one admits reaches its backend with its controller's authority, which its `caps` no longer bound.
- Review `auth.public.caps` for agents the venue's public principal owns: they act only as far as those caps reach.
- Pass `caps: "unrestricted"` to `agent:create` (and `agent:fromSkills`, or with a template other than `reader` or `admin`) for an agent that acts with its controller's full authority; existing agents keep the authority they had.
- To let someone other than its owner step an agent, grant them `admin/agent` on it.
- Anonymous callers need `auth.public.caps` to grant `invoke` on `v/ops/agent/request`
  and `v/ops/agent/message`, and `crud/read` on `j` to follow their jobs.
- An open chat bot's agent needs `accepts: "anyone"`, and the public scope `invoke` on
  `v/ops/agent/chat` or the bot's operation.
- A capped agent behind a chat bot needs write on the media drive to receive media.
- A grant on a folder creates nothing above it: create the folder, such as `w/reports`,
  before a capped agent writes into it.
- Recreate bots that agents created earlier, so they come from the agent.
- Agents no longer receive `via`; `mediaOperation` and `replyOperation` receive `authentication`.
- A webhook message accepted before the upgrade and not yet handled fails once.

## [0.10.0] - 2026-10-26

### Convex 0.8.18 lattice applications

- Requires Convex 0.8.18.
- Messaging intake and HITL responses commit in one venue transaction (#616).
- Venue components share atomic updates and transactions.
- Venue state is versioned, with ordered start-up migrations in one transaction (#621).
- Adapters version and migrate their own state (#621).
- Opt-in `x/<adapter>/...` views of adapter state.

### Migration

- No rollback below 0.10.0: keep the state from before the upgrade.
- External login: `auth.loginRedirectUris` replaces `auth.loginRedirectOrigins`, Microsoft
  needs `tenantId`, and legacy email accounts confirm identity links (#298).
- Creating an agent names `config.caps` the creator holds; leaving them out needs an
  unrestricted caller.

### Security

- External login uses single-use state, PKCE and OIDC nonce; matching emails never
  auto-link providers (#298).
- JWT verification rejects malformed `exp`/`nbf` claims.
- Secret store keys derived with HKDF and versioned per secret (#535).
- `sql:query` no longer applies data-changing statements; registered databases take `readOnly`.
- MCP venue-side targets require venue authority.
- User-created messaging bots no longer use the venue's credentials.
- Agent `caps`, `accepts` and `a2a.caps` confer only what their author holds.
- MCP `tools-call` and `tools-list` apply the SSRF policy.
- `bearerSecret: "oauth/<provider>"` needs `secret/use`.
- Capability-scoped agents reach only their own jobs and scheduled events, or those
  their caps cover.
- Naming another user's path needs read access to it.
- A bare `nb.gate` binds to the grant's issuer.

### Added

- MCP `tools/list` names each tool's operation in `_meta` (#558).
- Authentication method bindings, `auth.methods` (#297).
- `agent:suspend` records who suspended and why (#590).
- `p2p` adapter: an opt-in Convex P2P node (#587).
- Slack Socket Mode, agent template, skills and thread replies.
- `scheduler:list` describes each event (#583).
- `relay` handler for WhatsApp and Slack bindings (#584).
- `telegram:format`: Markdown to Telegram HTML.
- Venue `"tray": false`.
- WhatsApp voice notes, photos and files; `whatsapp:download`.
- `covia-speech` module: transcription and speech, local or over an OpenAI-compatible
  API, with live dictation.
- `user:export`, `user:import` and `user:clone` (#615).

### Changed

- Module jars use the venue's Kotlin, Jackson, Gson and Protobuf.
- The build and venue warn on JDK 25 before 25.0.3; CI requires a fixed JDK.
- Javalin 7.2.4, MCP Java SDK 2.1.0 and Logback 1.6.6.
- A standalone venue saves its root once more on shutdown.

### Fixed

- A tool called before its skill is loaded names the skills that provide it (#643).
- Expected tool failures no longer show Java class names.
- `/run` answers 400, not 500, for an operation's own failure.
- `GET /jobs/{id}` no longer reports a venue error as 404.
- Distinct secret refusals for existing, missing and undecryptable secrets (#586).
- Deleted DLFS drives stay deleted (#589).
- A deliberate agent stop survives late failures (#590).
- Discord splits long messages without breaking surrogate pairs.
- Messaging sends honour url-bound `secret/use` grants.
- Adapters share one HTTP client per class.
- Oversized messaging downloads are refused on JDK 27.

## [0.9.11] - 2026-10-15

### Security

- `secret:extract` runs only as a direct owner call (ability renamed `secret/extract`).
- `/api/v1/run` responses are never cached.

### Added

- Telegram media: attachments, images passed to the model, `telegram/download`, media
  and reply hooks.
- `crypto` adapter: hashes, Ed25519 and JWT signatures, encryption, BIP39 keys.
- `remoteagent` adapter: agents backed by A2A, Claude Code or remote Covia agents.
- `secret:list` and `secret:delete`.
- Adapter-specific job `errorCode` (#462).
- `GET /api/v1/adapters/{name}/operations`.

### Changed

- Cancelling a grid, A2A or remote-agent job completes at once, recording the remote outcome.
- Unrecorded jobs, such as `/run` calls, stay out of job status queries.
- Secret names reject `.`, `..` and control characters.
- SLF4J 2.0.20, Logback 1.6.5, A2A 1.4.0.Final, JDA 6.7.0, Telegram Bot API 10.3.0.

### Fixed

- Shutdown keeps running agents and their queued tasks.
- DLFS DID URL references with encoded paths resolve.

## [0.9.10] - 2026-10-10

### Security

- Using a secret needs `secret/use` on `<owner>/s/NAME`, with no fallback to the public
  store or the environment.
- `secret/use` grants can be bound to a destination URL.

### Changed

- Shared provider keys (`secrets.public`) are granted through `auth.public.caps`.
- MCP bridges backed by a venue secret serve other callers under a `secret/use` grant.
- SLF4J 2.0.19, A2A 1.3.1.Final, JDA 6.6.0.

### Fixed

- LangChain accepts a caller's own `s/NAME` key with a custom `url` (#568).
- Browser hardening headers leave an embedder's own policy in place (#569).

## [0.9.9] - 2026-10-09

### Security

- Social login checks `redirect_uri` against the allowed origins.
- Discord allow-lists match user ids and usernames only.
- LangChain sends the venue's key only to the operation's own endpoint.
- `X-Forwarded-*` headers are trusted only from `trustedProxies`.
- Secret deletion over REST is capability-checked and audited.
- Public status reports only the anonymous access mode (#562).

### Added

- Audit and access logs, request ids and JSON log format (#538, #547).
- Rate limiting of failed authentication (#539).
- `trustedProxies` for venues behind a reverse proxy (#539).
- Browser security headers and a documented production profile (#537).
- Account deletion: `DELETE /users/{did}` and `user:delete` (#536).
- `agent:query` answers one-off questions over a session; `agent:summarise` caches summaries.
- `agent:from-skills` builds a native agent from SKILL.md skills in one call (#484, #490).
- `skills:import` takes inline `text` and records who created a skill (#525).
- `agent:info` and `agent:list` report jobs awaiting input or authorisation (#557).
- Job records name their `parent` job and `adapter` (#500, #520).
- `GET /assets?kind=operation|data` (#530).
- OAuth provider client secrets can be `s/NAME` references (#522).
- `auth.loginRedirectOrigins` lets social login return to an app.
- `project` adapter and its skills.
- WhatsApp Cloud API and Slack Events API messaging modules.
- Remote jobs track observation health, with bounded retries.
- Online `venue:gc` can keep a checkpoint backup (#556).

### Changed

- Convex 0.8.17; LangChain4j 1.22.0 with native Anthropic options.
- Anthropic defaults move to Claude Sonnet 5.5, Opus 5.5, Fable 5.1 and Haiku 5.5.
- Anthropic structured output and thinking modes follow current Claude models (#563).
- Bearer credentials must expire (`auth.requireExp`, `auth.maxTokenLifetime`) (#534).
- A refused bearer's 401 says why (#503, #548).
- Unhandled errors answer 500 with details (#540).
- Allow-listed Telegram bots message only their allowed users (#532).
- SSE streams honour `X-Covia-Ucans` proofs; CORS exposes Covia's headers.
- Job records keep the reference that was invoked (#499).
- The default tool pack includes `covia:inspect` (#514).
- `skill_load` returns a structured result (#504).
- Startup logs one line per adapter.

### Fixed

- Concurrent creates of the same agent: exactly one succeeds.
- Tasks and messages never land on a just-removed agent; deleted jobs stay deleted.
- Last-modified stamps never move backwards.
- A2A continuations of a completed task report its final state (#506).
- Replies to an `a2a:send` job waiting for input reach the remote task (#507).
- Remote job waiters no longer hang after polling stops (#513).
- HTTP credential references are preserved in persisted job inputs (#561).
- `operations.log-config-file` is honoured (#538).
- Authentication refusals no longer echo exception text.
- Agent operations describe their inputs and outputs.
- Module skills no longer shadow the venue's connection skills (#510).
- External DIDs list their authentications; `stats.users` counts every user (#524).
- An agent wake never overwrites a newer suspend.
- Windows launches no longer warn about example content checksums.

## [0.9.8] - 2026-09-03

### Added

- Private `/run` calls (`private: true`) leave no job behind.
- `providerOptions` pass provider-native fields such as Claude thinking and effort.
- Convex keys generated into the caller's secret store, signing without exposing the seed.
- `convex:encode-cad3` and `convex:decode-cad3`.
- Connection catalog for 20 providers, with discovery and status.
- HTTP operations resolve `{s/NAME}` in URL paths and default to JSON bodies.
- Job-free reads for DLFS browsing and venue users (#474, #476).
- `memory:recall`.
- `operation.activityLabel` for in-flight tool-call labels (#463).
- Agent admission policy `config.accepts` (#447).
- Turn provenance names a foreign caller's user, agent and venue.
- Etch garbage collection at startup and online with `venue:gc` (#451, #452).
- In-flight jobs finish or suspend cleanly at shutdown (`shutdown.graceMs`).

### Changed

- Convex 0.8.16.
- `secret:set` overwrites only when asked.
- llmagent and goaltree share one durable context frame; prompt caching is on by default.
- `agent:request` and `agent:chat` jobs survive a venue restart.
- `agent:update` works on a running agent.

### Fixed

- Agent skill and tool loading is checked against the advertised surface (#477).
- Tool bindings and tool-call ids stay consistent across replays (#470, #471, #472, #479).
- Anthropic thinking blocks survive tool calls.
- Compaction keeps current skills, tools and context.
- Long DLFS names survive an Etch reopen (#469).
- Agents keep their final state across shutdown.
- Missing `readOnly` metadata warns instead of stopping an adapter (#459, #465).
- OAuth login callbacks carry a pseudonym, never the user's email (#448).
- Module jars carry their real dependencies, without bundled venue libraries.
- `covia-documents` logs through SLF4J.
- Quiet builds and clean shutdowns.

## [0.9.7] - 2026-08-29

### Added

- `agent:reloadContext` rebuilds a session's cached prefix.
- `agent:compactSession` compacts an idle session, keeping its full history for audit.
- `v/ops/venue/show-config`: a public view of the venue's effective behaviour.
- Live agent events over `GET /agents/{id}/sse` (#394).
- `agent:chat` results name every chat a reply answered (#416).
- `http:*` follows redirects safely (#423).
- `covia-documents` module: PDF and Office text extraction (#427).
- Connected accounts: the `oauth` adapter connects users to OAuth providers for `http:*`.
- OAuth 2.1 authorization server for registered clients (`auth.oauth.provider`).

### Changed

- llmagent and goaltree share one durable session frame, with an opt-in `compact` tool.
- Agent context separates trusted instructions from data (#434, #436).
- `context_unload` accepts several keys.
- Agent loads append to history once, keeping the cached prefix stable (#443, #445).
- `agent:update` refreshes session skill catalogs only when they change (#438).
- An A2A `SendMessage` with a `contextId` is its own task (#416).
- `adapters.http` configures the user agent, allowed and blocked hosts, and redirects.
- The Telegram skill keeps bot management in its own child skill.
- Module fat jars are published as GitHub Releases artifacts.

### Fixed

- Releases include the `covia-documents` module jar.
- An agent's `hitl:request` returns at once and wakes the session on answer (#442).
- Session input is fenced against superseded runners.
- Skill loads keep Anthropic cache marks in place (#446).
- `config.context` honours `volatile` (#444).
- Parallel Anthropic tool calls stay valid alongside context loads (#441).
- Context budget notices give accurate advice (#437).
- Personal venues can grant capabilities to the users they issued (#440).
- An LLM timeout fails only that cycle's inputs (#439).
- Telegram startup failures warn once (#435).
- `covia:inspect` validates `path` and `paths`.
- `agent:context` shows loaded exchanges (#418).
- An embedded venue reports its own version (#420).
- `http:*` sends a descriptive `User-Agent` (#422) and accepts numeric and boolean
  header and query values.
- A venue that fails to start says why in one line.
- `GET /api/v1/status` answers when public access is off, so clients can find a venue.

## [0.9.6] - 2026-08-27

### Added

- Cancel with a reason.
- `config.systemPrompt` may come from a workspace path, file, operation or job.
- Loads take `text`, `op`, `job` or `ref` sources at every tier.
- Volatile loads render after the cached prefix.
- Recurring scheduled events (`repeat: {every}`) (#407, #408).
- Parallel tool calls in the agent cycle.
- `v/ops/skills/parse` and `v/ops/skills/import` for SKILL.md files, which also resolve
  as skills from `file://` and `dlfs/` references.
- `covia-sonnylabs` module: prompt scanning through the SonnyLabs AI firewall.

### Changed

- `agent:chat` accepts concurrent chats on one session.
- Loaded context renders as tool exchanges.

### Fixed

- Agent session and pinned-skill interoperability (#411, #412, #413, #415).
- SKILL.md folded and multi-line descriptions parse; unknown keys are reported.

## [0.9.5] - 2026-08-25

### Migration

- Remote `grid:run` and `grid:invoke` take an explicit `authenticateAs`.
- UCANs are grants, presented through `ucans`; a bearer UCAN has empty `att` and the
  receiving venue as audience.

### Added

- `v/ops/venue/restart`, including upgrade to a new jar.
- `v/ops/user/sudo`: run one operation in another user's namespace, capability-checked.

### Changed

- Agent capabilities are additive: `config.caps` plus presented grants.
- Attribution in agent conversations is provenance, not instruction (#405).

### Fixed

- Structured tool results reach the model.
- Cross-user execution is explicit through `user:sudo` (#406).
- Listings tolerate entries vanishing mid-traversal (#404).
- Telegram and Discord templates discover the venue skill library (#409).

## [0.9.4] - 2026-08-24

### Migration

- Skill directories move from `config.skills` to `config.skillsets`.
- `v/ops/skills` becomes `v/ops/skills/list` and `v/ops/skills/read`.
- Harness tools are opt-in by name in `config.tools`.
- Explicit MCP URL paths are used verbatim.
- Timeline cycle token totals and tool failures come from `inferences`.

### Added

- `covia-discord` module: Discord bots for agents and operations.
- Hierarchical skills and skillsets; the venue skill library starts at `v/skills/root`.
- Model definitions as data assets under `v/models/`, with per-model hints and budgets.
- Prompt caching on Anthropic.
- Timeline entries record every inference (#392).
- `agent:step` runs one harness iteration on a supplied reply.
- `agent:context` simulates a call and reports tool provenance (#393).
- `GET /api/v1/assets?expand=metadata` (#381).
- `lattice` venue skill and `/agent-test-drive` skill.
- Clearer skill errors: `agent:create` names missing sources, `skill_load` lists the
  available skills and reports newly revealed ones.

### Changed

- Convex 0.8.15.
- Agent operations accept `g/<id>` and `<did>/g/<id>` paths (#375).
- llmagent and goaltree share harness tools, task tools and completion rules.
- `toolCalling: false` is honoured end to end.
- Agent context assembly is one shared, cache-friendly sequence.

### Fixed

- Anthropic `maxTokens` default, with one retry on truncation (#391).
- `GET /api/v1/values/*` honours delegation proofs (#399).
- Explicit MCP endpoints keep their path (#398).
- `VenueHTTP` instances share one HTTP client (#400).
- Malformed secret records fail visibly (#402).
- The prompt cache stays warm across turns.
- Skills without a `name` index by path.

## [0.9.3] - 2026-08-19

### Added

- Asset references (hashes, paths, DID URLs) resolve across HTTP and the Java client.

### Changed

- Javalin 7.2.3, LangChain4j 1.19.0, MCP Java SDK 2.0.1, Logback 1.6.3.

### Fixed

- DLFS timestamps, signer policies and streamed blobs on Convex 0.8.14 (#387).
- Module reloads replace their adapters (#386).

## [0.9.2] - 2026-08-17

### Added

- `covia-telegram` module: Telegram bots for agents and operations.
- `covia-claude-code` module: drive Claude Code in venue-authorised projects.
- Runtime adapter lifecycle: enable, disable and configure adapters, load and unload
  modules; `/adapters` skill.
- LangChain providers `mistral` and `openrouter`.
- Adapters publish their own skills, config and facts under `v/adapters/<name>/`.
- `covia` and `venue` agent skills.
- Job-free `GET /api/v1/schedules` and `GET /api/v1/assets?scope=own` (#369, #382).
- `GET /api/v1/assets/content/<ref>` (#368).
- `responseSchema` on `agent:request` (#376).
- `v/info/url` and DLFS WebDAV details in `v/info`.

### Changed

- Convex 0.8.13.
- Unresolved skill sources no longer produce warnings.

### Fixed

- MCP `covia_list` accepts `fields` as a JSON string (#379).
- `agent:request` delegation turns record the calling job (#378).
- Relative config paths work with `java -jar covia.jar`.
- Asset refs ending in `content` resolve (#368).
- An empty `complete_task` delivers the turn's text.

## [0.9.1] - 2026-08-13

### Changed

- The `vault` adapter targets a neutral `vault` drive, configurable per application.
- Named-user key-pair JWTs carry the DID document's key id (#352).
- Legacy UCAN JWTs without the `ucv` marker verify again (#353).

### Fixed

- Asset hashes are scoped to the caller's `/a` namespace (#368).

## [0.9.0] - 2026-08-10

UCAN JWTs use the versioned Convex profile (Convex 0.8.11): re-issue outstanding
grants and use covia-sdk / covia-sdk-py releases that emit it.

### Added

- Operator-declared venue identity (`did`), with `did:web` federation (#343).
- `file:move` and `file:copy` (#321).
- Outbound A2A agents as assets (#340).
- Result-oriented run execution (#316).
- Agent session titles.
- DLFS roots scoped to subpaths (#326).
- Venue admission documented and exposed (#318).
- Encrypted Etch stores (`etch` config).

### Changed

- Convex 0.8.11: genuinely non-expiring UCANs (#322).
- Configurable HITL grant expiry (#314).
- Agent creation is exclusive (#329).
- Structured admission errors (#327).
- A2A Java SDK 1.2.0.Final.

### Fixed

- Standard bearer auth for outbound A2A (#339).
- A2A long-turn reattachment (#338).
- Typed and nested tool results preserved in agent loops (#334).
- Unavailable configured agent tools surfaced (#317).
- Denied CORS origins get 403 (#320).
- Actionable MCP connection failures.
- Orchestrator strict schemas resolved at the target venue.
- Reliable agent session title updates.
- Asset content semantics and bound agent shutdown (#331, #333).
- Agent execution durability (#332).

## [0.8.0] - 2026-07-31

### Added

- `covia-python` and `covia-python-adapter`: Python operations on embedded CPython, as
  fixed scripts or per-user stateful instances.

### Changed

- Releases publish checksummed Python and SQL module jars.

### Fixed

- Anonymous callers never share Python instances.

## [0.7.0] - 2026-07-30

### Added

- HITL requests (COG-16): typed asks and echo-consent grants over the per-user `h/` inbox.
- HITL `token` ask for cross-venue access (COG-19, #292).
- Agents are sub-principals with their own DID (#280); job records name the acting agent.
- `ucan:issue` granting surface (COG-17).
- Federated job observation carries caller proofs.
- Authenticated callers get public-user access (#254).
- Archive adapter (zip/jar); `file` reads see into archives.
- Orchestration array bindings (#281) and bounded `foreach` steps.
- Structural agent output handoffs (`go.outputPath`).
- Job-free reads of task and session state.
- Named venue-user keys with rotation, revocation and short-lived client credentials.
- MCP discovery metadata says how to authenticate.
- Configurable venue root page.
- Config validation, with opt-in strict mode.

### Changed

- Convex 0.8.10, LangChain4j 1.18.1, Logback 1.6.1, A2A 1.1.0.Final.
- Agents hold no granting authority (COG-17).
- Private Network Access on by default for loopback venues (#286).
- MCP tool schemas work with strict client SDKs (#275).
- Scheduled events are owned by the user and fire as the agent that queued them.
- Hardened job lifecycle.
- Caller-side polling timeouts no longer fail jobs.
- Job pause and resume are adapter opt-in.
- Time-bounded agent LLM calls (`llmTimeoutMs`).
- Read-only default agent tool pack (#60).
- Bare UCAN grant resources mean the issuer's own namespace.
- Prompt-cache-friendly agent context.
- MCP tools carry operation safety annotations.

### Removed

- Wire self-attenuation on `/invoke`: presented proofs only add (#131).
- MCP `notifications/jobUpdate` broadcast (#274).

### Fixed

- The GHCR venue image is anonymously pullable (#212).
- Failed agent tool calls recorded once (#290).
- Cross-user lattice writes work with a `crud/write` proof (#295).
- `GET /assets/{id}/content` serves every content kind (#289).
- Sessions recover from a mid-call abort (#271).
- A failed orchestration step stops its dependents; specs are validated up front (#281).
- `a/<hash>` references resolve without a leading slash.
- MCP sessions are bound to their creator.
- MCP-supplied UCAN proofs are never persisted.
- Revoked named-user keys lose access immediately.
- MCP configuration is validated.

## [0.6.0] - 2026-07-17

### Added

- Agent skills: instruction and tool bundles agents load on demand.
- Venue skill library: 20 skills covering every Covia mechanism.
- `v/ops/skills` lists and reads skills; in the default MCP tool palette.
- Skills-first agent templates: `skilled`, `reader` and `minimal`.
- Venue modules ship their own skills.
- `langchain:models`: provider and model discovery (#221).
- Per-agent A2A publishing (`a2a.public`, `a2a.caps`).
- `agent:request` with `sessionId` reports back into a conversation.
- Token usage on jobs, timelines, sessions and `agent:context` (#217).
- Capability gates (#216).
- MCP tool bridging: external MCP tools as catalog operations (#80).
- `operation.default`: declarative argument defaults.
- Venue modules: external adapter jars loaded at boot (#226).
- `covia-sql` module: per-user databases and registered JDBC connections (#227).
- Typed outputs on Anthropic (#81).
- `temperature` and `topP` on all LangChain operations (#218); venue-level Ollama URL (#224).
- `agent:create` warns on raw credentials in `config.apiKey`.
- System tray icon on desktop launches.

### Changed

- Convex 0.8.9.
- MCP tool results and schemas pass through as declared.
- DLFS WebDAV advertises `DAV: 1` only.
- `GET /api/v1/agents` returns enriched entries (#233).
- `GET /api/v1/jobs` is paged; job stats on `/status` (#229).
- A persistent store without a configured venue identity refuses to start (#232).
- `covia-sql` supports single-column tables.
- Agent lifecycle tools run as real, owner-attributed jobs.

### Fixed

- Loopback venues answer on both 127.0.0.1 and ::1 (#231).
- Outbound A2A: SSRF checks, correct endpoint URLs, task polling for anonymous
  senders and use as an agent tool (#234).
- Task input reaches models as plain text or JSON (#215).
- Text-form control tool calls are honoured (#215).
- A task that exhausts its loop budget fails cleanly (#215).
- Job SSE streams send every status change and close at the end (#225).
- Job SSE content negotiation (#222).
- Bridged MCP tool errors carry the remote error (#80).

## [0.5.0] - 2026-07-15

### Added

- Lattice-resident goal-tree frames: durable, observable, resumable after a crash.
- Venue identity from a PKCS12 keystore (#208).
- Startup guard against the wrong venue key (#208).
- Resource-scoped `invoke` grants (#211).
- `agent:create` warnings for non-tool-capable models and unresolvable tools (#205).
- Configurable tool-loop iteration limit.
- User activity stamps.
- Java client parity: private jobs, `ucan:verify`, job-free reads.

### Changed

- Crash recovery stabilises jobs, never re-executes (#214).
- Capability denials name the missing capability (#206, #209, #211).
- Agent tool failures visible through `agent:context` (#211).

### Fixed

- Job SSE stream route (#200).
- Suspended or deleted agents no longer hang callers (#201, #202).
- MCP tool calls keep scalar results.
- Crash resume never re-dispatches a pre-crash tool call (#214).

## [0.4.0] - 2026-07-10

### Added

- Cross-venue trust: self-sovereign grants and identity tokens (#100).
- `ucan:verify`.
- Client-side UCAN minting.
- Rate limiting and backpressure.
- Cross-user DLFS access under UCAN proof (#98).
- Reference-addressed content: assets, lattice values and DLFS paths through one resolver.
- LangChain vision and `maxTokens` on Anthropic (#198).
- Audience-bound client authentication (#199).
- Job-free agent reads: `GET /api/v1/agents` (#180).
- Authenticated A2A agent catalogue (#187).
- Values API field projection (#191).
- Private jobs (#192).
- Context scope chain: agent, session and frame (#142).
- Per-turn caller attribution in session history (#84).
- `covia:slice maxSize` (#78).
- `agent:deleteSession`.
- Maven Central publishing under `ai.covia` (#33).

### Changed

- Convex 0.8.8; UCAN verification on Convex's authority layer (#196).
- DLFS capability resources use DID-scoped paths (#196).
- Job reads are delegable; mutations stay owner-only (#102).
- Job deletion is permanent.
- One canonical agent `config` (#144).
- Harness tool misuse fails at the point of use (#143).
- `asset:store` validates `metadata.content` (#173).
- Agent templates hand off by reference (#71).

### Fixed

- Confused deputy in cross-user DLFS writes.
- `dlfs:write` from a plain content asset.
- `/openapi`, OpenAPI route parameters, build version and ReDoc.
- Jetty URI compliance (#153).
- Maven Central snapshots endpoint.

## [0.3.0] - 2026-07-06

### Added

- Job-free lattice reads: `GET /api/v1/values/*` (#177).
- Secret deletion through `covia:delete` (#166).
- `did:web` alias publication for public hostnames (#167).
- `POST /invoke` `wait` window (#140).
- Optional output-schema validation (#51).
- LLM providers Gemini, xAI and DeepSeek (#158).
- Configurable HTTP `bindAddress` (#129).
- Venue-wide agent defaults.
- Build version in `GET /status` (#139).

### Changed

- Durable deletions: the venue lattice is a single whole-value node (Convex 0.8.7).
- Tool-call arguments parsed once at the wire boundary (#58, #89).
- `schema:*` operations never reparse JSON-looking strings.
- Adapter asset installation fails loudly (`strictAssets`).
- Convex 0.8.6, Javalin 7, Jetty 12 (#152); MCP SDK 2.0.0 (#156); A2A 1.0.0.Final (#155).
- Agents strict-allowlist tools by default (#92, #134).
- `covia:write` requires a `value`.
- Assets retrievable by lattice address (#150).
- Default Anthropic model `claude-sonnet-4-6`.
- Private Network Access behind `allowPrivateNetwork` (#130).
- CRUD operations return meaningful outcomes (#132, #147).

### Security

- Capability enforcement: read-only public ceiling and `auth.public.caps` (#148).
- JWT audience validated; UCANs classified by `att` (#149).
- UCAN bearer signatures bound to the claimed issuer.
- Failed bearer verification is a hard 401.
- Malformed JSON bodies return 400.

### Fixed

- Path navigation surfaces abnormal errors (#175).
- Remote-fetch failures are errors, not "not found" (#174).
- `v/` startup writes after restart (#159).
- Ordered job listings under concurrency.
- Tool-loop exhaustion fails the task (#138).
- Deep-write shape conflicts surfaced.
- Indefinite blocking paths bounded.
- Token attenuation enforced on `/invoke` (#131).
- Agent run loop executes as the owner (#91).

## [0.2.0] - 2026-06-15

### Added

- User memory: one `memory` tool over a durable per-user list.

### Changed

- Cross-venue references denote definitions: `did:web` refs fetch hash-verified and run
  locally; `grid:run` and `grid:invoke` execute remotely.
- `did:web` port encoding and localhost http.
- Named catalog references resolve at the publisher.
- `asset:pin` adopts verified remote assets.
- Fetched definitions cached by content hash.
- Agent context resolution fails loudly.

### Fixed

- `covia:delete` is durable.

## [0.1.0] - 2026-06-12

First release under independent SemVer per artifact, with TypeScript SDK 1.5.0 and
Python SDK 0.2.0.

### Added

- Grid scheduler for deferred invocation.
- Goal-tree progressive ancestor compaction.
- Per-user secret bootstrap from venue config.
- Persistence resilience: periodic fsync.
- README, CONTRIBUTING, SECURITY, changelog and CI gate.
- Docker image `ghcr.io/covia-ai/covia`, with a `:stable` channel.

### Changed

- Convex 0.8.5 from Maven Central.
- Consistent `GET /assets`; `asset:list` scoped to the caller.
- Strong consistency for lattice CRUD.
- Hardened deployment.
- Licensing: platform EPL-2.0, SDKs Apache-2.0.

### Fixed

- LangChain fails fast on a missing API key (#91).
- `agent:trigger` waits are cancellable.

## [0.0.1] - 2026-01-23

Initial public release: venue server with the adapter framework, lattice-backed
content-addressed assets, the async job model with SSE, multi-protocol surface
(REST / MCP / A2A / DID), and strategy-based authentication.

[Unreleased]: https://github.com/covia-ai/covia/compare/0.11.0...HEAD
[0.11.0]: https://github.com/covia-ai/covia/compare/0.10.0...0.11.0
[0.10.0]: https://github.com/covia-ai/covia/compare/0.9.11...0.10.0
[0.9.11]: https://github.com/covia-ai/covia/compare/0.9.10...0.9.11
[0.9.10]: https://github.com/covia-ai/covia/compare/0.9.9...0.9.10
[0.9.9]: https://github.com/covia-ai/covia/compare/0.9.8...0.9.9
[0.9.8]: https://github.com/covia-ai/covia/compare/0.9.7...0.9.8
[0.9.7]: https://github.com/covia-ai/covia/compare/0.9.6...0.9.7
[0.9.6]: https://github.com/covia-ai/covia/compare/0.9.5...0.9.6
[0.9.5]: https://github.com/covia-ai/covia/compare/0.9.4...0.9.5
[0.9.4]: https://github.com/covia-ai/covia/compare/0.9.3...0.9.4
[0.9.3]: https://github.com/covia-ai/covia/compare/0.9.2...0.9.3
[0.9.2]: https://github.com/covia-ai/covia/compare/0.9.1...0.9.2
[0.9.1]: https://github.com/covia-ai/covia/compare/0.9.0...0.9.1
[0.9.0]: https://github.com/covia-ai/covia/compare/0.8.0...0.9.0
[0.8.0]: https://github.com/covia-ai/covia/compare/0.7.0...0.8.0
[0.7.0]: https://github.com/covia-ai/covia/compare/0.6.0...0.7.0
[0.6.0]: https://github.com/covia-ai/covia/compare/0.5.0...0.6.0
[0.5.0]: https://github.com/covia-ai/covia/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/covia-ai/covia/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/covia-ai/covia/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/covia-ai/covia/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/covia-ai/covia/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
