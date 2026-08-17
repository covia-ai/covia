---
name: telegram
description: Connect a Covia venue to Telegram — declare a bot whose inbound handler is an agent conversation or any operation (via a mapping op for deterministic targets like a SQL write); allow-list who may talk to it, send Telegram messages from agents and workflows, and diagnose a bot that is not answering. Use when the user wants to talk to an agent from Telegram or notify people on Telegram.
argument-hint: "<setup|status|send|allow|test|teach> <bot-name>"
---

# Telegram

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first. Telegram support comes from the **covia-telegram** module (`telegram` adapter) — it is not in `covia.jar`; `setup` covers loading it.

Everything about a bot is operator configuration: `adapters.telegram.bots.<name>` in the venue config (full reference: `venue/docs/CONFIG.md` "Telegram bots"). Users and agents cannot create bots; they use the ones the operator declared. `/adapters` explains the module and runtime-lifecycle mechanics this skill relies on.

## `setup <bot-name>` — Bring a bot up

Walk the user through these steps; each is idempotent.

1. **Get a token from Telegram.** In Telegram, talk to `@BotFather`: `/newbot`, choose a display name and a username ending in `bot`. Copy the token (`123456789:AA…`). Optional but useful for group chats: `/setprivacy` → Disable, so the bot sees all group messages, not only commands and mentions.

2. **Store the token as a secret** (never in the config file):
   ```
   secret_set  name=TELEGRAM_BOT_TOKEN  value=<token>
   ```
   Store it as the user the bot will act as (see step 4) — on a local dev venue over MCP that is the public user, which is exactly right for `"user": "public"`. The venue's own store is the fallback.

3. **Load the module.** Build once — `mvn -pl covia-telegram -am package -DskipTests` produces `covia-telegram/target/covia-telegram-<ver>-module.jar` — then either declare it in the venue config (`"modules": ["modules/covia-telegram-<ver>-module.jar"]`, copying the jar into `modules/`), or load it at runtime with `v/ops/venue/module/load` (venue authority + `dynamicModules.enabled`; see `/adapters load`). Verify: `covia_read path=v/info/adapters/telegram`.

4. **Declare the bot** in the venue config and restart (or apply live with `v/ops/venue/adapter/configure`, which needs venue authority and is not persisted):
   ```json
   "adapters": {
     "telegram": {
       "bots": {
         "<bot-name>": {
           "token": "s/TELEGRAM_BOT_TOKEN",
           "user": "public",
           "agent": "<agentId>",
           "allow": []
         }
       }
     }
   }
   ```
   - `user` is the identity the bot acts as — the **owner of the agent**. `"public"` is right for a local dev venue whose agents were created over MCP; on a real venue name the owner's DID. The bot has that user's full authority, so choose deliberately.
   - **Pick the inbound handler** (ask the user which they want; exactly one). Every inbound message runs as a Job in the bot user's job index — that is the record of the interaction; the module keeps no log of its own:
     - `"agent": "<agentId>"` — each Telegram chat is one `agent:chat` conversation with that agent (create it first with `/agent create`). Always replies.
     - `"operation": "<ref>"` — every message invokes that operation with the record `{bot, chatId, messageId, text, from: {id, username, firstName, lastName}, chat: {id, type, title}, date}` as input. For a deterministic target whose input is *not* that record (a `sql/execute` insert, an HTTP webhook, a classify-then-store step) point it at a small **mapping op the user owns** — an orchestration (`/orchestrate create`), a pinned op — that takes the record and does the work; the same goes for logging messages somewhere (an op that appends to the path its metadata names). The Telegram module never reshapes or logs messages. `"reply": true` (default) sends the result rendered as text, `false` sends nothing, `"Recorded."` sends that fixed acknowledgement.
   - Leave `allow` empty for now; step 6 fills it. Do **not** set `"open": true` unless anyone on Telegram should be able to reach this handler (defensible for a bot whose operation is safe for strangers; dangerous for an agent with authority).
   - Optional: `"parseMode": "Markdown"` for formatted replies, `"greeting": "…"` for `/start`.

5. **Check it came up:** `grid_run operation=v/ops/telegram/bots` → the bot should be `RUNNING` with its Telegram `username`. `PENDING` means it could not start — the `error` field says why (secret missing, bad token/401, API unreachable); it retries (2 s, then every 30 s), so fixing the cause is enough, no restart needed.

6. **Allow yourself.** Send the bot any message in Telegram. Because `allow` is empty it answers *"Not authorised … Your Telegram user id is N"*. Put that id (or your `@username`) into `allow`, re-apply the config, and message it again — this time the handler runs. `/id` in the chat shows chat and user ids at any time.

## `status` — What bots exist and how they are doing

```
grid_run  operation=v/ops/telegram/bots
```

Shows the bots the caller may use (all bots for the venue identity): `state`, Telegram `username`, `target` (`agent X` / `operation Y`), `error` when pending, and `received`/`sent`/`failed` counters. Tokens are never shown. `covia_read path=v/info/adapters/telegram` confirms the module is loaded at all.

## `send <bot-name>` — Send a message

```
grid_run  operation=v/ops/telegram/send  input={"bot": "<bot-name>", "chatId": <id>, "text": "…"}
```

`bot` may be omitted when the caller owns exactly one. `chatId` is a numeric Telegram chat id — a person's private chat is their user id, groups are negative (`/id` in the chat prints it) — or an `@channelusername` the bot administers. Optional `parseMode` (`Markdown`|`MarkdownV2`|`HTML`, falls back to plain text if Telegram rejects the markup), `replyTo` (message id), `silent`. Long text is split at 4096 chars. Sending is gated on `<bot user>/telegram/<bot>` × `telegram/send`: the bot's user and their agents may send; another user gets *Access denied* unless the owner delegated it (`/ucan`).

## `allow <bot-name>` — Manage who may talk to the bot

Access is fail-closed and lives in config: `allow` is a list of Telegram user ids and/or `@usernames`; `open: true` admits everyone. Edit the config and restart, or apply live:
```
grid_run  operation=v/ops/venue/adapter/configure
          input={"name": "telegram", "config": {"bots": {"<bot-name>": {…full bot entry…}}}, "merge": true}
```
(venue authority required — see `/adapters`). Unauthorised private messages are answered with the sender's id so it can be added; unauthorised group messages are ignored silently.

## `test <bot-name>` — Smoke-test the loop

1. `status` shows `RUNNING`.
2. From Telegram: `/start` (greeting), then a message. Agent bot: the reply arrives (typing indicator while it works); `/new` starts a fresh conversation (the persisted session at `w/telegram/<bot>/sessions/<chatId>` is cleared). Operation bot: the result / acknowledgement per `reply`. Either way the turn is a Job in the bot user's `j/` index (`covia_list path=j` as that user).
3. From the venue: `send` a message to your own chat id and confirm it arrives.
4. `status` again: `received` and `sent` advanced, `failed` did not.

## `teach` — Let an agent use Telegram

The module ships the `telegram` agent skill at `v/skills/telegram` (present exactly when the module is loaded). Any agent whose config declares `skills: ["w/skills", "v/skills"]` (all standard templates do) can `skill_load` it and then call `v/ops/telegram/send` / `v/ops/telegram/bots`. The agent must run as the bot's user (or hold a `telegram/send` delegation), which is automatic when the bot's `user` is the agent's owner. Give it the chat id explicitly or let it answer within a Telegram-originated conversation — the skill tells it never to guess chat ids.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `v/info/adapters/telegram` missing | Module not loaded — step 3; check the venue log for a module load error. |
| Bot `PENDING`, error *token secret … not found* | Store the secret as the bot's user (or in the venue store); it retries automatically. |
| Bot `PENDING`, error *401 Unauthorized* | Wrong token — copy it again from @BotFather. |
| Messages get *Not authorised* | Add the id/username to `allow` (or `open: true`), re-apply config. |
| Bot silent in a group | BotFather privacy mode: `/setprivacy` → Disable, or mention the bot / use commands. |
| Operation bot replies with raw JSON | That is the result rendered; set `reply` to a fixed string or `false`, or have the mapping op return `{text: …}`. |
| Reply says *Unknown session* once, then works | The persisted session was deleted (agent recreated) — the bot recovers by starting a new one. |
| `send` fails with *Access denied* | Caller is not the bot's user; use that identity or a `telegram/send` delegation. |
| Replies stop after `adapter/disable` | By design — a disabled adapter is offline; Telegram redelivers the backlog on enable. |
| Bot vanished after restart | It was added with `adapter/configure` (not persisted) — put it in the venue config. |
