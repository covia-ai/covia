# Held Keys (design)

How a venue keeps private keys for the identities it hosts, and uses them on
their behalf. Keyrings belong to the crypto adapter. This is a design; none of
it is implemented yet.

Tracked by covia#637.

## What a held key is

Every identity on the venue has a keyring: the private keys it has given the
venue, of any type, indexed by public key. A key may be the key of a
`did:key` identity, in which case holding it is custody of that identity, or
any other key its holder uses, such as a Convex account key.

A held key follows the rules of identity, not those of secrets. A secret is
data its owner can read (`secret/extract`) and lend (`secret/use`); a held
key allows neither, because whoever can sign with a key acts as whatever that
key identifies.

Today keys live elsewhere:

- `auth:identity-token` signs only with the venue's own key, for the venue's
  `did:key` and identities nested below it ([AUTH.md](./AUTH.md) §5.2), and
  `ucan:issue` signs every grant with the venue's key.
- The crypto adapter ([CONFIG.md](./CONFIG.md), "Cryptography") keeps every private
  seed and mnemonic in the caller's secret store: `crypto:generate-key` and
  `crypto:derive-key` store new seeds there, and `crypto:public-key`, `sign`,
  `jwt-sign` and `decrypt` take a `seed` secret reference, resolved in one
  place (`CryptoAdapter.keyPair`).
- The Convex adapter does the same with its own operations:
  `convex:generate-key` stores a seed as a secret, `convex:sign` signs with a
  seed named by secret reference, and `convex:transact` takes a seed by secret
  reference or literally (redacted from job records).
- An embedder whose installation has its own `did:key` keeps that key in its
  own code and signs with `VenueAuth.namedKeyPair` itself.

## Principles

1. **Any identity may hold keys.** An authenticated identity adds a key to its
   own keyring, given the capability to do so. The venue checks that the
   private key yields the public key; nothing else is needed.
2. **A keyring is a resource of its holder.** Its keys are used and listed
   under authority over that resource, by the standard rules and no others:
   the holder's own authority, narrowed by a capability scope as for any
   resource, or a grant (principle 5).
3. **Authority over an identity includes its keys.** Whatever acts with a
   holder's authority can use its keys: an agent whose base covers its owner's
   namespace, `user/sudo` to the holder, a grant of `*` over its namespace.
   What confers no authority over another identity's resources confers no use
   of its keys: admission, and nesting below a root (`<root>:u:alice` gains
   nothing over `<root>`). The venue reaches a holder's keys exactly as it
   reaches the holder's other resources ([VENUE_AUTHORITY.md](./VENUE_AUTHORITY.md)):
   it is the root for the identities it custodies, and today not for a
   self-sovereign holder's (open question 6 there).
4. **An identity token for oneself is not key use.** An identity may always
   obtain an identity token naming itself, signed by its root's key (its own,
   if it is a root), whether that is the venue's key or one the root holds
   here. The token attests only the identity the root's nesting already
   established, so it is authentication, under `auth:identity-token`'s own
   rules, and needs no key ability.
5. **A grant of key use is an ordinary grant.** A key is a resource in its
   holder's namespace, and grants cover it by the standard rules (COG-17): a
   key ability, `key` or `*`, on the key, the keyring or anything containing
   it, from a root for the holder's resources. A holder that grants `*` over
   its namespace grants the use of its keys with it.
6. **Keys are never readable.** No operation returns, exports or lends a
   private key, as for the venue's own key.
7. **One signing function** serves identity tokens, UCAN grants, Convex
   transactions and raw payloads, under the same consent and no-amplification
   rules as any issuance (COG-17).
8. **It can sign when needed.** Because the key stays held, a holder can have
   tokens minted as they are needed instead of storing a long-lived bearer.
   How a call to another service authenticates with a held key is for the
   method it uses to decide, not a rule of the keyring.
9. **Custody can end.** Removing a key, or deleting its holder, ends it. The
   venue then signs nothing new with it; tokens already issued last until they
   expire.
10. **Every use is audited,** naming the holder, the public key and the
   principal that asked.

## Store format

Keyrings are the crypto adapter's state, at `:adapters/crypto`
([ADAPTERS.md](./ADAPTERS.md)):

```
{
  "version": 0,
  "holders": Index<AString, {
    "keys":      Index<Blob, key record>,
    "mnemonics": Index<Blob, mnemonic record>
  }>
}
```

- **`version`** (`CVMLong`) is the layout version. Each layout change is a
  migration step, and the step writes the new version here as the framework
  records it in `:adapter-versions` (ADAPTERS.md, "Versioned state and
  migration"), so the value states its own version. The first release has no
  steps and is version 0.
- **`holders`** is an `Index` keyed by the holder's DID, as an `AString`. An
  `Index` keeps holders in order, so an identity and every identity nested
  below it (`<did>:…`) are adjacent, and deleting a user removes that range:
  the DID itself and the DIDs that continue it with `:`.
- **`keys`** is an `Index` keyed by public key, as a `Blob`: the
  multicodec-prefixed key bytes, `0xed01` followed by the 32-byte key for
  Ed25519. The prefix names the key type, so other types fit without a layout
  change.
- **`mnemonics`** holds the recovery phrases a holder chose to keep here
  rather than as secrets, keyed the same way by the public key of the phrase's
  SLIP-10 master node (no passphrase), which identifies the phrase without
  revealing more than any key derived from it.

A key record is a map:

```
{
  "sealed": { "encrypted": <Blob>, "keyVersion": <CVMLong> },
  "addedAt": <CVMLong, unix ms>,
  "label":  <AString, optional>
}
```

- **`sealed`** is the `SecretStore.seal` record: AES-256-GCM ciphertext and
  the version of the key that sealed it, so records can be re-encrypted when
  the venue's key rotates (#535).
- **The sealed plaintext** is a JSON string naming the holder DID, the public
  key and the private key, both keys in multikey form (`Multikey` in Convex).
  On opening, the venue checks that the holder and public key match the
  record's place and that the private key yields the public key, so a record
  opens only where it was written.
- **The sealing key** is derived from the venue's seed under a label of its
  own, apart from the secret store's, so a sealed key cannot be read as a
  secret. It has no counterpart to the secret store's legacy version 1, a
  plain hash of the seed, which would open records of either store.
- **`label`** is the holder's own name for the key, if it gave one.

A mnemonic record has the same fields. Its sealed plaintext names the holder
DID, the master node's public key and the phrase, checked the same way on
opening. A passphrase is not kept with it: `crypto:derive-key` takes one by
secret reference as now.

Using a key writes nothing to the store; the audit log records it.

## Storage rules

- **Deleted with its holder.** Deleting a user runs every adapter's
  `deleteUserState` in the deletion transaction, disabled adapters included,
  and the crypto adapter removes the user's holder entry and those nested
  below it. Deleting an agent removes the agent's own keyring.
- **Disabling the crypto adapter** leaves the keys sealed in its state.
  Nothing uses them until it is enabled again, and other adapters' operations
  that would sign with a held key are refused meanwhile.
- **The venue's own key stays where it is,** loaded from `seed`, `keystore` or
  `venue.key` ([CONFIG.md](./CONFIG.md), "Venue identity"). It is the root
  every sealed record depends on, so it cannot live in state it unlocks, and
  signing with it does not depend on the crypto adapter.

## Using a key

`CryptoAdapter.keyPair` resolves every private key the crypto adapter's
operations use. It accepts a held key as well as a `seed` reference: it checks
the ability the operation needs on the key, opens the record, and makes
an `AKeyPair` that lives for that one call. The existing signers then serve
every use: `AKeyPair.sign` for raw bytes, `JWT.signPublic` for identity tokens
and UCAN grants, `AKeyPair.signData` for Convex values, and ECIES for
decryption. Other adapters (`auth:identity-token`, `ucan:issue`,
`convex:transact`) obtain a held key's `AKeyPair` from the crypto adapter in
the same way, for the one call. Other key types need signers of their own;
Convex 0.8.18 provides Ed25519 only.

A held key is named by its public key, in any of the forms results already
use: its `did:key`, its multibase string (`z6Mk…`), or for Ed25519 its 32-byte
account key. A bare key names a key in the keyring of the namespace the
request acts in, as a bare `s/NAME` names a secret there, and
`<did>/keys/<key>` names one in another holder's keyring. A held mnemonic is
named the same way by its ID, the master node's public key, or as
`<did>/mnemonics/<id>`.

## Operations

New crypto operations:

| Operation | Input | Output |
|-----------|-------|--------|
| `crypto:add-key` | `seed` or `mnemonic`: a reference to the caller's own secret, as `crypto:derive-key` takes one; optional `label`; `overwrite` to replace the label of a key already held | `accountKey` and `did` of the key, or `did` of the mnemonic's master node |
| `crypto:remove-key` | `key` or `mnemonic`: a held key or mnemonic | `removed` |
| `crypto:list-keys` | optional `did`, whose keyring (default: the namespace the request acts in); `offset`, `limit` as `secret:list` takes them | `keys` and `mnemonics`, each entry with `accountKey` (Ed25519 only), `did`, `label` and `addedAt`; `total`, `nextOffset` |

Adding a key from a secret leaves the secret in place; deleting it is the
caller's choice. A literal private key or phrase is never input, as for every
crypto operation.

Existing operations gain a held key beside a `seed` reference, and every
existing input and output stays as it is:

- **`crypto:sign`**, **`crypto:jwt-sign`** and **`crypto:decrypt`** take
  `key`, a held key, in place of `seed`. `crypto:sign` already signs raw
  bytes exactly as given, and is unchanged otherwise.
- **`crypto:generate-key`** and **`crypto:derive-key`** take `keyring: true`,
  with an optional `label`, in place of `name`: the new key goes into the
  keyring of the namespace the request acts in, where its private key is born
  in the venue and never seen, and the result has `accountKey` and `did` but
  no `secret`.
- **`crypto:generate-mnemonic`** takes `keyring: true` likewise, and returns
  the phrase's `did` and `words`.
- **`crypto:derive-key`** and **`crypto:validate-mnemonic`** take a held
  mnemonic in `mnemonic`, beside an `s/NAME` reference. A key derived from a
  held mnemonic goes only into a keyring, never into a secret, where it could
  be read.
- **`ucan:issue`** takes an optional `key`: the grant is signed with that held
  key, its issuer is the key's `did:key`, bare `with` paths are made absolute
  against that `did:key`, and its roots are checked against it. The result
  names the issuer as `iss`, so a caller can tell when a venue without this
  field signed with its own key instead. Agents still cannot issue grants.
- **`convex:transact`** takes `key`, a held key, in place of `seed`.
- **`auth:identity-token`** takes nothing new: it signs with the caller's
  root's key when the root holds that key in its own keyring, and with the
  venue's key, as now, for identities nested below the venue.

Seeds and phrases as secrets stay supported, and the caller chooses: a secret
it can read back and lend, or a keyring entry that nothing can read.

`convex:sign` and `convex:generate-key` are deprecated in favour of
`crypto:sign` and `crypto:generate-key`. `convex:sign` is not a drop-in:
its `message` is UTF-8 by default, where `crypto:sign` reads hex unless given
`encoding: utf8`.

## Abilities

A keyring is the resource `<did>/keys/`, one key is `<did>/keys/<key>` with
the key in multibase form, and held mnemonics are `<did>/mnemonics/` and
`<did>/mnemonics/<id>`. Like `<did>/oauth/<provider>`, they name resources for
capability checks, not lattice paths.

| Ability | On | Allows |
|---------|----|--------|
| `crud/read` | `<did>/keys/`, `<did>/mnemonics/` | Listing them: public keys, labels and when each was added |
| `key/add` | a key or mnemonic | Adding it, or generating or deriving it into the keyring |
| `key/remove` | a key or mnemonic | Removing it |
| `key/sign` | a key | Signing anything with it; covers every `key/sign/…` |
| `key/sign/grant` | a key | Signing UCAN grants (`ucan:issue`) |
| `key/sign/convex` | a key | Signing Convex transactions (`convex:transact`) |
| `key/sign/raw` | a key | Signing content the caller chooses (`crypto:sign`, `crypto:jwt-sign`), which can produce any of the above |
| `key/decrypt` | a key | Decrypting with it (`crypto:decrypt`) |
| `key/derive` | a mnemonic | Deriving keys from it (`crypto:derive-key`) |

`key` covers every `key/…` ability, and `*` covers them as it covers every
ability. The kinds of signing let a capability scope narrow what a key may
sign: an agent can hold `key/sign/grant` on its owner's key without being
able to sign transactions. An operation that signs whatever its caller
supplies, such as `crypto:jwt-sign` with its free claims, needs
`key/sign/raw`. Identity tokens need no key ability (principle 4).

A check is the standard capability check of the ability on the resource,
made against the request's authority as for any resource, with nothing added
for keys:

- A user's own authority covers its own keyring.
- An agent with no caps acts with its owner's authority, and so uses its
  owner's keys. An agent with caps uses what they cover: a bare `keys/` in its
  caps names its owner's keyring, and `<agent DID>/keys/` its own.
- A request under `user/sudo` acts with the target's authority, keys
  included.
- A grant covers a key as it covers any resource (principle 5), bounded to
  the key, the kind of signing, an expiry and any `nb.gate`, which can make
  signing wait for an approval.

The public principal has no keyring. The venue's own key is the venue's
keyring entry under the same rules: the venue acting as itself, in trusted
code, uses it, and anyone else only under a grant from the venue that covers
it.

## Security

- **Held keys are hot.** A held key, or a held mnemonic, is usable by whoever
  controls the venue's process, or its seed together with its store: the
  sealing protects keys at rest and between principals, not from the venue
  itself. Keyrings suit hot keys, such as service identities and hot wallets.
  A key of lasting value stays with its owner, who signs with it
  self-sovereignly.
- **A secret is the readable choice.** A seed or mnemonic kept as a secret can
  be read back by its owner and lent with `secret/use`; one in a keyring
  cannot. The holder chooses which it wants.
- **Authority over an identity includes its keys.** Whoever acts with a
  holder's full authority can sign as it: an agent created without caps, a
  request under `user/sudo` to it, a grantee of `*` over its namespace. An
  agent that should not sign is given caps that leave the keyring out.
- **Granting key use is granting a signature.** A grantee of `key/sign/raw`
  can make the key sign anything, so its holder should grant the narrowest
  kind of signing, on one key, for the shortest time. A grant of the venue's
  own key, or of `*` over the venue's namespace, hands out the venue's
  signature, the sharpest grant there is.

## Open questions

1. When the deprecated Convex operations are removed.
2. Whether `crypto:sign` and the HITL `sign` request (#293) share one path.
