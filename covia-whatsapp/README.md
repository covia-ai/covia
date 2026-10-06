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
