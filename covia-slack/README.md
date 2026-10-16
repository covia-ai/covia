# Slack venue module

`covia-slack` connects a workspace-installed Slack app to a Covia agent or
operation through the HTTP Events API or Socket Mode. It supplies
`slack:send`, `create`, `delete` and `bots`, plus `v/skills/adapters/slack`.
The optional module uses the JDK HTTP and WebSocket clients and is not bundled
in `covia.jar`.

## Setup

Build with `mvn -pl covia-slack -am install`, then copy
`covia-slack/target/covia-slack-<version>-module.jar` into the venue's module
directory. Create and install a Slack app with a bot user. Grant `chat:write`,
`app_mentions:read` and `im:history` bot scopes; subscribe to `app_mention`
and `message.im`. Enable the app's direct-message surface if needed. Invite
the bot into each channel it will use. These subscriptions follow Slack's
[mention](https://docs.slack.dev/reference/events/app_mention/) and
[DM](https://docs.slack.dev/reference/events/message.im/) event contracts.

Store the bot access token and app signing secret using `secret:set` as the
binding's owner. Both settings require `s/NAME` references. Secret lookup has
the usual messaging adapter venue-store fallback. Record the app, workspace
and bot-user IDs (not display names).

```json
{
  "modules": ["modules/covia-slack-<version>-module.jar"],
  "adapters": {
    "slack": {
      "bots": {
        "support": {
          "user": "did:key:YOUR_OWNER_DID",
          "token": "s/SLACK_BOT_TOKEN",
          "signingSecret": "s/SLACK_SIGNING_SECRET",
          "appId": "A0123456789",
          "teamId": "T0123456789",
          "botUserId": "U0123456789",
          "agent": "support-agent",
          "allow": ["U0987654321"],
          "allowChannels": ["C0123456789"]
        }
      }
    }
  }
}
```

Set the Slack Events Request URL to
`https://YOUR_VENUE/webhooks/slack/c-support`. Signed URL verification is
handled automatically. The proxy must preserve the raw body and Slack
signature/timestamp headers; these routes do not require a venue bearer.

Requests use [Slack HMAC verification](https://docs.slack.dev/authentication/verifying-requests-from-slack/)
with a five-minute timestamp window. Event callbacks must match `appId` and
`teamId` and include a workspace authorization. Optional `enterpriseId`
also constrains that authorization. Enterprise-wide installations and OAuth
installation management are outside this version's scope. Bot authorization
metadata is distinct from the message's sender.

### Socket Mode

[Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/)
delivers the same events over an outbound WebSocket, so a venue on a laptop or
behind NAT needs no public URL. Enable Socket Mode on the app and create an
app-level token with the `connections:write` scope. Store it with `secret:set`
and give its reference as `appToken`, in place of `signingSecret`:

```json
"support": {
  "user": "did:key:YOUR_OWNER_DID",
  "token": "s/SLACK_BOT_TOKEN",
  "appToken": "s/SLACK_APP_TOKEN",
  "appId": "A0123456789",
  "teamId": "T0123456789",
  "botUserId": "U0123456789",
  "agent": "support-agent",
  "allow": ["U0987654321"],
  "allowChannels": ["C0123456789"]
}
```

The app token authenticates the connection, so no request signature is
involved. A binding without `signingSecret` has no webhook route: its path
returns 404 and `bots` reports `connection` instead of `webhookPath`. A
binding may hold both credentials while an app moves between transports.
Both feed one inbox, so conversations continue across them and each
`event_id` runs once.

Slack spreads an app's events across all of its open connections, whatever
the workspace. So all Socket Mode bindings whose `appToken` resolves to the
same token share one connection, across owners and including the venue-store
secret fallback. Each event goes to every binding whose `appId`, `teamId` and
`enterpriseId` match it, and each applies its own sender and channel
admission. One app can therefore serve several workspaces. Two bindings for
the same workspace both act on an event they both admit, so split their
`allowChannels` or senders to avoid double replies. Events for a workspace
with no binding are acknowledged and dropped.

Holding the token is the only test for sharing. Anyone with an app token can
already open a connection and receive all of the app's events, so sharing
grants nothing new. A binding with a different token, even one claiming the
same app and workspace, gets its own connection and never receives events
from another token's connection. Give every binding of one app the same token
value. A second token for the same app opens a second connection, which
receives, and drops, a share of the other bindings' events.

A binding opens or joins its token's connection once both tokens resolve, and
the last binding to leave closes it. Each connection uses a fresh single-use
URL from `apps.connections.open`.
Slack's periodic `disconnect` refresh reconnects at once. A failed or silent
connection is replaced after the retry interval; the module pings every 30
seconds and treats two silent intervals as lost. `bots` reports `connection` as
`CONNECTED`, `CONNECTING` or `RETRYING`. Events pass the same app, workspace,
sender and channel checks as callbacks. Each envelope is acknowledged only
after every matching binding has durably accepted it. Slack redelivers
unacknowledged envelopes, and each inbox deduplicates them by `event_id`.
Slash-command and interactivity envelopes are acknowledged and ignored.

`allow` contains Slack user IDs and defaults to empty; `open: true` opens
sender admission. Channel publication always requires `allowChannels`, even
with `open: true`. An admitted direct-message sender can receive an automatic
reply in that DM. Explicit `slack:send` requires the destination channel,
including DM channel IDs, in `allowChannels`.

Channel conversations require an `app_mention` on each inbound turn, including
thread replies. Other channel-message subscriptions are ignored to avoid
handling one message as both `message` and `app_mention`. Edits, deletions,
bot messages and self echoes do not trigger work. Channel replies stay in the
originating thread; root mentions start a thread. DMs share a conversation
per channel, with separate conversations for explicitly threaded DMs. Slack
timestamp IDs remain strings throughout.

Agents receive `{text, via}`. `via` identifies sender, channel, workspace and
thread; Slack identities grant no additional venue authority. Replies in
channels are visible to other channel members. With `operation` instead of
`agent`, the input is `{event_id, team_id, api_app_id, event}`. Legacy Slack
verification tokens are not retained. `reply: false` makes operation handlers
silent; a string supplies a fixed reply.

## Operations

| Operation | Input | Authority / result |
|---|---|---|
| `v/ops/slack/send` | `{bot?, channel, text, thread_ts?}` | `<owner>/slack/<bot>` × `slack/send`; Slack message receipt |
| `v/ops/slack/create` | `{name, ...bot settings}` without `user` | Caller-owned binding; `slack/manage`; returns `webhookPath` and/or `connection` |
| `v/ops/slack/delete` | `{name}` | Deletes caller-owned runtime binding and private state; `slack/manage` |
| `v/ops/slack/bots` | `{}` | Own bindings (all for venue principal), readiness, paths, connection state and receipt counts |

For example, invoke `v/ops/slack/send` with:

```json
{"bot":"support","channel":"C0123456789","text":"The report is ready.","thread_ts":"1700000000.000001"}
```

Sends use [chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/).
This module sends plain text: Slack control characters are escaped, markdown
and unfurls are disabled. Explicit text is limited to 4000 Unicode code
points; longer automatic replies are truncated with an ellipsis. Blocks,
files, interactive actions and slash commands are not supported. Slack
`ok: false`, HTTP errors and ambiguous delivery failures fail without an
automatic retry, including rate-limit responses.

## State and recovery

Each accepted `event_id` is flushed before acknowledgement. Processing and
replies run asynchronously so agent duration does not delay acknowledgement.
See the shared [webhook worker contract](../venue/docs/ADAPTERS.md#http-messaging-workers)
for private state paths and restart behavior. Pending work resumes after
restart or credential provisioning. Started work is never replayed
automatically after interruption. `bots` reports pending/started/queued
counts and a sanitized failure reason; a started receipt may represent an
in-flight Job or uncertain delivery and needs inspection before manual retry.

Credentials, private endpoints and admission lists do not appear in public
adapter info/config. Durable receipts contain message content; use the venue's
normal storage controls and retention policy for that data.
