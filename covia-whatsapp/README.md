# WhatsApp venue module

`covia-whatsapp` connects a WhatsApp Cloud API business phone number to a
Covia agent or operation. It supplies `whatsapp:send`, `create`, `delete` and
`bots` operations, plus `v/skills/adapters/whatsapp`. It is an optional module,
not a dependency of `covia.jar`, and uses the JDK HTTP client.

## Setup

Build with `mvn -pl covia-whatsapp -am install`. Copy
`covia-whatsapp/target/covia-whatsapp-<version>-module.jar` to the venue's
module directory. Configure an existing Meta app, WhatsApp Business Account,
Cloud API phone number, and access token with messaging permission. Select a
Graph API version enabled for your app; `apiVersion` is required so this
module does not silently choose an API lifecycle for you.

Store the outbound token, Meta app secret and a separately chosen webhook
verification token using `secret:set`. All three settings must be `s/NAME`
references. They resolve in the configured user's secret store, with the
normal messaging adapter venue-store fallback.

```json
{
  "modules": ["modules/covia-whatsapp-<version>-module.jar"],
  "adapters": {
    "whatsapp": {
      "bots": {
        "support": {
          "user": "did:key:YOUR_OWNER_DID",
          "token": "s/WHATSAPP_TOKEN",
          "appSecret": "s/META_APP_SECRET",
          "verifyToken": "s/WHATSAPP_VERIFY",
          "businessAccountId": "123456789012345",
          "phoneNumberId": "987654321098765",
          "apiVersion": "v25.0",
          "agent": "support-agent",
          "allow": ["441234567890"]
        }
      }
    }
  }
}
```

Replace the example IDs and API version with your app's values. Set Meta's
callback URL to `https://YOUR_VENUE/webhooks/whatsapp/c-support` and provide
the **value** of the verification-token secret in Meta's verification UI.
Subscribe the app to the account's messages webhook. The reverse proxy must
forward GET and POST requests with their original body and signature header.
No venue bearer is needed on this provider-authenticated route.

GET challenges require the verification token. POST callbacks require the
raw-body `X-Hub-Signature-256` HMAC using the app secret. Account and phone
IDs must match the binding. Each admitted text message in a batch gets its
own durable receipt, keyed by WhatsApp message ID. Delivery statuses, media,
other accounts/numbers and unadmitted senders are acknowledged without
invoking a Job. Texts older than 24 hours or dated more than five minutes in
the future are ignored. Sender IDs are numeric WhatsApp phone identifiers;
usernames and business-scoped user IDs are not supported in this version.

`allow` defaults to empty (deny everyone). `open: true` admits any supported
sender ID and permits outbound sends to any such ID. An allow-listed binding
can send only to its listed IDs. An agent receives `{text, via}` where `via`
identifies the provider, authenticated sender and phone binding. Provider
identity does not grant additional venue authority.

To route to a deterministic operation, replace `agent` with `operation`.
Its input is `{message, metadata, business_account_id}`, retaining the
provider message object. The result is rendered as text; `reply: false`
suppresses a reply, or a string supplies a fixed reply. Agent handlers cannot
set `reply`.

## Operations

| Operation | Input | Authority / result |
|---|---|---|
| `v/ops/whatsapp/send` | `{bot?, to, text, reply_to?}` | `<owner>/whatsapp/<bot>` × `whatsapp/send`; provider message receipt |
| `v/ops/whatsapp/create` | `{name, ...bot settings}` without `user` | Caller-owned binding; `whatsapp/manage`; returns `webhookPath` |
| `v/ops/whatsapp/delete` | `{name}` | Deletes caller-owned runtime binding and private state; `whatsapp/manage` |
| `v/ops/whatsapp/bots` | `{}` | Own bindings (all for venue principal), readiness, paths and receipt counts |

For example, invoke `v/ops/whatsapp/send` with:

```json
{"bot":"support","to":"441234567890","text":"Your request is ready."}
```

Explicit sends reject texts longer than 4096 Unicode code points. Automatic
replies truncate with an ellipsis to fit one message. Meta enforces its
customer-service window on outgoing text; an automatic reply is additionally
refused once its inbound message is 24 hours old. This module does not send
templates, media, interactive content or provide embedded signup/OAuth.
Provider errors and ambiguous network failures fail the send without an
automatic retry, including rate-limit responses.

## State and recovery

The shared [webhook worker](../venue/docs/ADAPTERS.md#http-messaging-workers)
owns receipts and sessions. Pending events resume when credentials become
available or the venue restarts. Started events remain visible for manual
reconciliation after an interruption; no send or Job is blindly replayed.
`bots.started` includes currently running and potentially uncertain work;
`queued` reports workers in this process. State is durable only when the
venue's configured store is durable. Binding credentials, recipients and
private API endpoints are never published in adapter info/config.

Provider reference: [Meta Cloud API documentation](https://developers.facebook.com/documentation/business-messaging/whatsapp/overview),
[Meta's raw webhook verification description](https://whatsapp.github.io/WhatsApp-Nodejs-SDK/api-reference/webhooks/start/),
and [text message shape](https://whatsapp.github.io/WhatsApp-Nodejs-SDK/api-reference/messages/text/).
The latter two references describe Meta's archived SDK; this module has no SDK dependency.

## Relay: one number, many principals

The module's second adapter, `whatsapp-relay`, lets a hub venue hold one Cloud API
number for many downstream principals: the venues or apps it relays to, each known by
its DID. WhatsApp delivers only to a public webhook, so a principal without one (an app
on someone's laptop) reaches WhatsApp through the hub instead of a number of its own.
Only links are stored: messages wait in memory and are never written to the venue.

```json
{
  "modules": ["modules/covia-whatsapp-<version>-module.jar"],
  "adapters": {
    "whatsapp-relay": {
      "relays": {
        "main": {
          "user": "did:key:YOUR_OWNER_DID",
          "token": "s/WHATSAPP_TOKEN",
          "appSecret": "s/META_APP_SECRET",
          "verifyToken": "s/WHATSAPP_VERIFY",
          "businessAccountId": "123456789012345",
          "phoneNumberId": "987654321098765",
          "apiVersion": "v25.0",
          "number": "447700900000",
          "holdSeconds": 60,
          "maxParts": 10,
          "admit": "v/ops/your-adapter/admit",
          "charge": "v/ops/your-adapter/charge"
        }
      }
    }
  }
}
```

Meta's callback is `https://YOUR_VENUE/webhooks/whatsapp-relay/c-main`, verified and
signed exactly as for the `whatsapp` adapter. Principals call
`/relay/whatsapp-relay/c-main/<action>` with a credential the venue accepts (a
self-issued DID JWT is enough; no venue user is created):

| Action | Request | Answer |
|---|---|---|
| `status` | — | `{enabled, number, linked}` |
| `link` | `POST {}` | `{code, number, link, expires_in}`: the person sends `LINK <code>` from their phone, which `link` (a `wa.me` URL) writes for them; codes last ten minutes |
| `unlink` | `POST {}` | `{unlinked}` |
| `poll` | `POST {wait_ms?}` | `{linked, messages[{id, text, timestamp}]}`, waiting up to 20 s for a message |
| `reply` | `POST {message_id, text}` | `{sent, parts}`: once per message, to its sender, inside 24 hours; split at 4,096 characters |

A text from a linked number goes to that principal's poll. A principal counts as open
while it has a poll waiting or polled in the last minute; with `holdSeconds` at 60 or
less (the default, meaning only while the principal is open), a message for a principal
that is not open is refused and the sender told so; a longer hold keeps it in memory
that long. A reply longer than `maxParts` messages (default 10, at most 100) is refused
with `too_long`, so a principal cannot make the hub send without limit. `STOP` from the phone unlinks; a number
that is not linked is told how to link; non-text messages get a short notice. The
hub's own replies are the relay's `notices` (`linked`, `unlinked`, `notLinked`,
`codeUnknown`, `notOpen`, `held`, `textOnly`, `notAvailable`), each overridable.

Both hooks are optional operations, run without a Job record as the relay's `user`.
`admit` receives `{principal}` before linking, polling and each delivery, and answers
`{allowed: true}` or `{allowed: false, code?, notice?}`; the code is returned to the
principal and the notice sent to the phone. `charge` receives
`{principal, parts, message_id}` after a reply is sent. `v/ops/whatsapp-relay/relays`
lists the caller's relays (all for the venue principal) without credentials.

Links live at `w/adapters/whatsapp-relay/config/<relay>/links` and `/principals` in the
venue's private workspace. Message text, codes and the message→sender map are bounded
and in memory: a restart or reconfiguration loses messages not yet collected, never a link.
