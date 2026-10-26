---
name: slack
description: Connect a Covia venue to Slack — create or reuse a Slack app, run it over Socket Mode (no public URL) or signed Events API callbacks, bind it to an agent conversation or any operation, allow-list who and which channels it serves, send Slack messages from agents and workflows, and diagnose a binding that is not answering. Use when the user wants to talk to an agent from Slack or post to Slack from the venue.
argument-hint: "<setup|create|delete|status|send|allow|test|teach> <binding-name>"
---

# Slack

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first. Slack support comes from the **covia-slack** module (`slack` adapter), which is not in `covia.jar`; `setup` covers loading it.

A binding connects one Slack app installation (app, workspace, bot user) to a handler. It is either **created by its user** with `v/ops/slack/create` (acts as that user, persisted in the venue-private adapter workspace, survives restarts, removed with `v/ops/slack/delete`) or **declared by the operator** in `adapters.slack.bots.<name>` (needed when it must act as another identity, e.g. `"user": "public"` on a local dev venue). Full reference: `covia-slack/README.md`.

## `setup <binding-name>` — Bring a binding up

Walk the user through these steps; each is idempotent.

1. **Choose the transport.** Socket Mode (`appToken`) needs no public URL — the right choice for a laptop, NAT or a private network. A venue left running on a desktop should set `"tray": false` in its config (or `COVIA_NO_TRAY=1`): the tray icon's native AWT code can crash the venue when Windows wakes from sleep. Events API callbacks (`signingSecret`) need the venue reachable over HTTPS at its `webhookPath`. Enabling Socket Mode on an existing app moves *all* its events to sockets, so check nothing else relies on that app's Request URL first.

2. **Create or update the Slack app** at https://api.slack.com/apps. For a new app choose *From a manifest*, the workspace, and paste:
   ```json
   {"display_information": {"name": "Covia"},
    "features": {"app_home": {"messages_tab_enabled": true, "messages_tab_read_only_enabled": false},
                 "bot_user": {"display_name": "Covia", "always_online": true}},
    "oauth_config": {"scopes": {"bot": ["app_mentions:read", "chat:write", "im:history", "channels:history", "groups:history"]}},
    "settings": {"event_subscriptions": {"bot_events": ["app_mention", "message.im", "message.channels", "message.groups"]},
                 "socket_mode_enabled": true, "org_deploy_enabled": false, "token_rotation_enabled": false}}
   ```
   For callbacks set `"socket_mode_enabled": false`. `message.channels`/`message.groups` with their `*:history` scopes let replies in a thread the agent is already in continue without a mention; drop them to require a mention on every channel turn. For an existing app, check the same scopes, bot events and App Home Messages tab, and reinstall if scopes changed.

3. **Collect the tokens and IDs.** Bot token (`xoxb-…`): *OAuth & Permissions → Bot User OAuth Token*, after *Install to Workspace*. Socket Mode app-level token (`xapp-…`, scope `connections:write`): *Basic Information → App-Level Tokens*. Signing secret: *Basic Information → App Credentials*. App ID (A…): *Basic Information*, and the second segment of an `xapp-1-A…-…` token. Workspace (T…) and bot user (U…) IDs: Slack's `auth.test` with the bot token returns `team_id` and `user_id`.

4. **Store the tokens as secrets** (never in config or binding settings), as the user the binding will act as:
   ```
   secret_set  name=SLACK_BOT_TOKEN  value=<xoxb token>
   secret_set  name=SLACK_APP_TOKEN  value=<xapp token>
   ```
   On a local dev venue over MCP that user is the public user, matching `"user": "public"`. The venue's own store and process environment are the fallback, so an operator can instead export `SLACK_BOT_TOKEN`/`SLACK_APP_TOKEN` into the venue process. If the user pastes a token into chat, store it at once, do not repeat it, and suggest regenerating it after testing.

5. **Load the module.** Build once (`mvn -pl covia-slack -am package -DskipTests` produces `covia-slack/target/covia-slack-<ver>-module.jar`), then declare it in the venue config (`"modules": ["modules/covia-slack-<ver>-module.jar"]`) or load it at runtime with `v/ops/venue/module/load` (see `/adapters load`). Verify: `covia_read path=v/info/adapters/slack`.

6. **Create the agent** (skip for an operation handler) from the module's template — it knows `via`, per-DM and per-thread sessions, plain-text replies, memory and `slack:send`:
   ```
   agent_create  agentId="SlackAssistant"  config=["v/agents/templates/slack", {"llmOperation": "v/ops/langchain/anthropic", "model": "claude-sonnet-5-5"}]
   ```
   The agent's owner needs an LLM key it may use (e.g. `ANTHROPIC_API_KEY` in its own secrets). Tell it who is who as memory, as the binding's user: `grid_run operation=v/ops/memory input={"command": "remember", "text": "Slack U0123ABC is Mike, my owner."}`. Slack supplies IDs, not names; the template trusts such a note only when `via.from` matches.

7. **Create the binding.** Preferred — one op, no restart, acts as you:
   ```
   grid_run  operation=v/ops/slack/create  input={"name": "<binding-name>", "token": "s/SLACK_BOT_TOKEN", "appToken": "s/SLACK_APP_TOKEN", "appId": "A…", "teamId": "T…", "botUserId": "U…", "agent": "SlackAssistant", "allow": ["U…"], "allowChannels": []}
   ```
   Use `"signingSecret": "s/SLACK_SIGNING_SECRET"` instead of `appToken` for callbacks, then set the app's Request URL to `https://<venue>` + the returned `webhookPath`. Use `"operation": "<ref>"` (with `"reply": true | false | "fixed text"`) instead of `agent` for a deterministic handler; it receives `{event_id, team_id, api_app_id, event}`. Use `"relay": true` instead to serve many principals, each known by its DID, from this one app; they link by DM and use the returned `relayPath` (see `covia-slack/README.md`). Operator alternative: the same fields plus `"user"` under `adapters.slack.bots.<binding-name>` in the venue config.

8. **Check it came up:** `grid_run operation=v/ops/slack/bots` → `RUNNING`, and for Socket Mode `connection: CONNECTED`. `PENDING` means a token is missing or unresolvable; `RETRYING` means Slack refused the connection (wrong token, Socket Mode off). Both retry automatically once fixed.

## `create <binding-name>` / `delete <binding-name>`

```
grid_run  operation=v/ops/slack/create  input={…as in setup step 7…}
grid_run  operation=v/ops/slack/delete  input={"name": "<binding-name>"}
```

`create` refuses literal tokens, a `user` field and a name you already use. There is no update: replace = delete + create, which also drops the binding's conversation sessions and receipts. `delete` leaves the Slack app and its tokens untouched and refuses operator-declared bindings.

## `status`

`grid_run operation=v/ops/slack/bots` shows the bindings the caller may use (all for the venue identity): `state`, `managed`, `webhookPath` and/or `connection`, and `pending`/`started`/`queued` receipt counts. A `started` receipt may be in flight or uncertain; inspect its Job before any manual retry. Tokens are never shown.

## `send <binding-name>`

```
grid_run  operation=v/ops/slack/send  input={"bot": "<binding-name>", "channel": "C…", "text": "…", "thread_ts": "1700000000.000100"}
```

Plain text only, up to 4000 characters: Markdown, `<@U…>` mentions and `<url|text>` links are sent literally. `channel` must be in the binding's `allowChannels` (a DM's D… ID too). `bot` may be omitted when the caller owns one binding. Needs `slack/send` on `<owner>/slack/<binding>`. A failed send may still have been delivered; do not retry automatically.

## `allow <binding-name>`

Access is fail-closed. `allow` lists Slack user IDs (U…/W…); `open: true` admits anyone in the workspace. Channel mentions and channel sends additionally need the channel ID in `allowChannels` — even when open — and the bot invited (`/invite @Covia`). An admitted DM sender is answered in that DM without `allowChannels`. Find a user ID in their Slack profile (*⋮ → Copy member ID*), a channel ID at the bottom of its details pane. To change a created binding, delete and create it again; for a config binding edit the config and restart.

## `test <binding-name>`

1. `status` shows `RUNNING` (and `CONNECTED`).
2. DM the bot from an allowed account; the agent or the operation's reply arrives in the DM. Mention it in an allowed channel; the reply arrives in a thread. For an agent with `message.channels` subscribed, reply in that thread without a mention; it answers in the same conversation.
3. `status` again: `pending` and `started` back to 0.
4. From the venue: `send` to an allowed channel and confirm it arrives.

## `teach` — Let an agent use Slack

The module ships the `slack` agent skill at `v/skills/adapters/slack` (send, status, `via` semantics), which reveals `slack-bot-management` (create, delete, status, and the `secrets` skill for tokens). Agents with the standard venue skillsets reach it through discovery; agents made from `v/agents/templates/slack` already hold `slack:send`. Reading Slack history or user names is the separate `slack` connection skill (Slack Web API over `http`, needing extra app scopes such as `channels:history` and `users:read`).

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `v/info/adapters/slack` missing | Module not loaded — setup step 5; check the venue log. |
| `PENDING` | A token secret is missing for the binding's user (an operator-declared binding also checks the venue store); it retries automatically. |
| `RETRYING` | Slack refused `apps.connections.open`: wrong or revoked `xapp-` token, missing `connections:write`, or Socket Mode disabled on the app. |
| DM gets no reply | Sender not in `allow`; Messages tab disabled; or `message.im` / `im:history` missing from the app. |
| Channel mention ignored | Channel not in `allowChannels`, bot not invited, or `app_mention` not subscribed. |
| Un-mentioned thread reply ignored | The app lacks `message.channels` + `channels:history` (`message.groups` + `groups:history` for private channels), the handler is an operation, or the agent never joined that thread (a mention starts one). A root channel message always needs a mention. |
| Callback binding never receives | Request URL not set to the `webhookPath`, venue not reachable over HTTPS, or the proxy alters the raw body or Slack headers. |
| Replies show `**` or `<@…>` literally | Replies are plain text by design; the template asks agents to write plain text. |
| Some events vanish with several Socket Mode bindings | Bindings of one app hold different app tokens; give them all the same token so they share one connection. |
| Two replies to one message | Two bindings admit the same workspace and channel; split their `allowChannels` or senders. |
| `create` fails with *Capability denied* | Caller lacks `slack/manage`; use a dev venue with unrestricted public caps or authenticate. |
