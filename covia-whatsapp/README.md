# WhatsApp venue module

`covia-whatsapp` connects a WhatsApp Cloud API business phone number to a
Covia agent or operation, or relays it to many principals. It supplies `whatsapp:send`, `create`, `delete` and
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

## Voice notes, photos and files

An agent binding receives images, voice notes and other audio, video, documents
and stickers as attachments. When the message is handled, the adapter looks the
media id up and downloads the short-lived link, both with the binding's token, and
stores the bytes in the owner's private DLFS `whatsapp` drive (at most 20 MiB).
The turn is `{text, via, attachments, content?}`:

- `text` is the caption, or empty for a bare voice note.
- Each attachment has `kind` (`audio`, `image`, `video`, `document`, `sticker`),
  `id`, `mediaType`, `voice: true` for a voice note, `ref`, `size` and `fileName`.
  A document keeps its own name only when it is a safe single filename; otherwise,
  and for everything else, the name comes from the binding and the media id.
- Images also arrive as image blocks for a vision-capable model; the agent needs
  `crud/read` on the files.

There is no implicit speech provider. Set `mediaOperation` on an agent binding to
an operation that transcribes or extracts, such as `v/ops/speech/transcribe` from
the speech module, which transcribes voice notes and passes other media through: it runs as the owner once per
attachment, with the attachment plus `caption` and `via`, and returns `{}` to pass
the attachment through or an object whose non-empty `text` becomes the
attachment's `text`; the whole result is kept under `attachment.result` and cannot
replace the reference or media type. A failed download or hook fails the turn like
any other processing failure. Operation bindings stay text-only. Reactions are
ignored. The download link is on a different host from the Graph API, so a
`secret/use` grant bound to a url must cover both.

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

A binding whose handler is `relay`, in place of `agent` or `operation`, lets a hub
venue hold one Cloud API number for many downstream principals: the venues or apps it
relays to, each known by its DID. WhatsApp delivers only to a public webhook, so a
principal without one (an app on someone's laptop) reaches WhatsApp through the hub
instead of a number of its own. Only links are stored: messages wait in memory and are
never written to the venue. The relay protocol is the same for every webhook provider;
see [Relays](../venue/docs/ADAPTERS.md#relays-one-provider-binding-many-principals).

```json
{
  "modules": ["modules/covia-whatsapp-<version>-module.jar"],
  "adapters": {
    "whatsapp": {
      "bots": {
        "main": {
          "user": "did:key:YOUR_OWNER_DID",
          "token": "s/WHATSAPP_TOKEN",
          "appSecret": "s/META_APP_SECRET",
          "verifyToken": "s/WHATSAPP_VERIFY",
          "businessAccountId": "123456789012345",
          "phoneNumberId": "987654321098765",
          "apiVersion": "v25.0",
          "number": "447700900000",
          "relay": {
            "holdSeconds": 60,
            "maxParts": 10,
            "admit": "v/ops/your-adapter/admit",
            "charge": "v/ops/your-adapter/charge"
          }
        }
      }
    }
  }
}
```

`"relay": true` takes the defaults. Meta's callback is
`https://YOUR_VENUE/webhooks/whatsapp/c-main`, verified and signed as for any binding.
Principals call `/relay/whatsapp/c-main/<action>`; a runtime binding made with
`whatsapp:create` reports its own `relayPath`. On WhatsApp:

- `number` is the number people message. With it, `link` and `status` return it and
  `link` adds a `wa.me` URL that opens the chat with `LINK <code>` already typed.
- A reply must follow its message within WhatsApp's 24-hour window. It is split at
  4,096 characters; the first part quotes the message and later parts do not.
- A relay binding admits every number, since linking is its admission; `open: false`
  narrows it to `allow`. The binding's `whatsapp:send` stays as closed as `open` and
  `allow` are written.
- Voice notes, photos and files are relayed too. A polled message lists them under
  `media` (`kind`, `mediaType`, `fileName`, `voice`) with the caption as its text, and
  the principal fetches each one's bytes with the `media` action. The hub downloads
  them from Meta on each request and keeps no copy, so a principal can transcribe a
  voice note itself.

Links live under `w/adapters/whatsapp/config/<bot>/relay/` in the venue's private
workspace (a runtime binding's under its owner's adapter state). Message text, codes
and the message→sender map are bounded and in memory: a restart or reconfiguration
loses messages not yet collected, never a link.
