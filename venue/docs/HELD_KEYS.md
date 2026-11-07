# Held Keys (design)

How a venue keeps signing keys for the identities it hosts, and signs with
them on their behalf. This is a design; none of it is implemented yet.

Tracked by covia#637.

## What a held key is

Every identity on the venue has a keyring: the signing keys it has given the
venue, of any type, indexed by public key. A key may be the key of a
`did:key` identity, in which case holding it is custody of that identity, or
any other key its holder signs with, such as a Convex account key.

A held key follows the rules of identity, not those of secrets. A secret is
data its owner can read (`secret/extract`) and lend (`secret/use`); a held
key allows neither, because whoever can sign with a key acts as whatever that
key identifies.

Today `auth:identity-token` signs only with the venue's own key, for the
venue's `did:key` and identities nested below it ([AUTH.md](./AUTH.md) §5.2),
and `ucan:issue` signs every grant with the venue's key. An embedder whose
installation has its own `did:key` keeps that key in its own code and signs
with `VenueAuth.namedKeyPair` itself.

## Principles

1. **Any identity may hold keys.** An authenticated identity adds a key to its
   own keyring, given the capability to do so. The venue checks that the
   private key yields the public key; nothing else is needed.
2. **Only the holder uses its keys.** A key signs only for a request whose
   caller is its holder, authenticated as itself or acting as itself in
   trusted in-process code. Only the holder lists its keys. These rights cannot
   be delegated: a grant of them would let another principal sign as the
   holder. A capability scope can narrow them, as an agent's caps do.
3. **Acting on an identity's behalf never signs as it:** not through
   `user/sudo`, an activated allowance, or the venue's power over its users
   ([VENUE_AUTHORITY.md](./VENUE_AUTHORITY.md)). A signature travels to other
   venues, and the host root holds only at its own venue.
4. **Nested identities sign only as themselves.** An identity nested below a
   root (`<root>:u:alice`) may have its root's key sign as the nested identity,
   such as its own identity token, and never as the root. Agents are
   identities with keyrings of their own, and cannot use their owner's keys.
5. **Keys are never readable.** No operation returns, exports or lends a
   private key, as for the venue's own key.
6. **Signing is generic.** One signing function serves identity tokens,
   UCAN grants, Convex transactions and raw payloads, under the same consent
   and no-amplification rules as any issuance (COG-17).
7. **It signs at the point of use.** The venue mints short-lived tokens when
   they are needed, so no long-lived bearer has to be stored.
8. **Custody can end.** Removing a key, or deleting its holder, ends it. The
   venue then signs nothing new with it; tokens already issued last until they
   expire.
9. **Every signing is audited,** naming the holder, the public key and the
   principal that asked.

## Store format

Held keys are their own adapter's state, at `:adapters/<name>`
([ADAPTERS.md](./ADAPTERS.md)):

```
{
  "version": 0,
  "holders": Index<AString, Index<Blob, key record>>
}
```

- **`version`** (`CVMLong`) is the layout version. Each layout change is a
  migration step, and the step writes the new version here as the framework
  records it in `:adapter-versions` (ADAPTERS.md, "Versioned state and
  migration"), so the value states its own version. The first release has no
  steps and is version 0.
- **`holders`** is an `Index` keyed by the holder's DID, as an `AString`. An
  `Index` keeps holders in order, so an identity and every identity nested
  below it (`<did>:…`) are adjacent, and deleting a user removes that range.
- **Each keyring** is an `Index` keyed by public key, as a `Blob`: the
  multicodec-prefixed key bytes, `0xed01` followed by the 32-byte key for
  Ed25519. The prefix names the key type, so other types fit without a layout
  change. The multibase string (`z6Mk…`) appears only where an operation
  shows a key.

A key record is a map:

```
{
  "sealed": { "encrypted": <Blob>, "keyVersion": <CVMLong> },
  "added":  <CVMLong, unix ms>,
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
  secret.
- **`label`** is the holder's own name for the key, if it gave one.

Signing writes nothing to the store; the audit log records it.

## Storage rules

- **Deleted with its holder.** Deleting a user runs every adapter's
  `deleteUserState` in the deletion transaction, disabled adapters included,
  and this adapter removes the user's holder entry and those nested below it.
- **Disabling the adapter** leaves the keys sealed in its state, and nothing
  signs with them until it is enabled again.
- **The venue's own key stays where it is,** loaded from `seed`, `keystore` or
  `venue.key` ([CONFIG.md](./CONFIG.md), "Venue identity"). It is the root
  every sealed record depends on, so it cannot live in state it unlocks, and
  signing with it does not depend on the adapter.

## Signing

An Ed25519 key becomes a Convex `AKeyPair` in memory for one signing call
only. The existing signers then serve every use: `JWT.signPublic` for identity
tokens and UCAN grants, `AKeyPair.signData` for Convex transactions, and
`AKeyPair.sign` for raw payloads. `auth:identity-token` and `ucan:issue` both
sign with an `AKeyPair` today, so choosing the venue's key or a held one,
after checking that the caller is the holder, is the one change to them.
Other key types need signers of their own; Convex 0.8.18 provides Ed25519
only.

## Operations

The adapter's operations (names open):

- **Add** a key, refused unless the private key yields the public key. The
  private key is a secret field of the operation (`operation.secretFields`),
  so job records keep it redacted.
- **Remove** a key.
- **List** the caller's own keys: public key, label and when it was added.
- **Sign** a payload with one of the caller's keys.

## Abilities

A keyring is the resource `<holder DID>/keys/`, and one key is
`<holder DID>/keys/<public key>`, the key in multibase form. Like
`<did>/oauth/<provider>`, they name resources for capability checks, not
lattice paths.

| Ability | On | Allows |
|---------|----|--------|
| `crud/read` | `<did>/keys/` | Listing the keyring: public keys, labels and when each was added |
| `key/add` | `<did>/keys/<pk>` | Adding that key |
| `key/remove` | `<did>/keys/<pk>` | Removing it |
| `key/sign` | `<did>/keys/<pk>` | Signing anything with it; covers every `key/sign/…` |
| `key/sign/token` | `<did>/keys/<pk>` | Signing identity tokens |
| `key/sign/grant` | `<did>/keys/<pk>` | Signing UCAN grants |
| `key/sign/convex` | `<did>/keys/<pk>` | Signing Convex transactions and values |
| `key/sign/raw` | `<did>/keys/<pk>` | Signing arbitrary bytes, which can produce any of the above |

`key` covers every `key/…` ability. The kinds of signing let a capability
scope narrow what a key may sign: an agent can hold `key/sign/token` on its
own key without being able to sign transactions.

A check has two steps, in order:

1. **The caller is the holder.** The keyring's DID must equal the caller's
   DID; nothing else counts, not a presented grant, an admission policy or
   `user/sudo`. That is what makes these rights undelegable, and it keeps an
   agent with no caps, whose base is its owner's authority, away from its
   owner's keys.
2. **The caller's own base covers the ability.** A user holds them all over
   its own keyring. An agent needs caps naming its own keyring by DID URL,
   since a bare path in caps names its owner's namespace. An `nb.gate` on such
   a cap can make signing wait for an approval.

A nested identity signing as itself with its root's key does so only through
`auth:identity-token`, under that operation's rules, and holds no `key/…`
right over its root's keyring. The venue's own key is the venue's keyring
entry under the same two steps, so only the venue acting as itself, in trusted
code, signs with it; a grant from the venue never confers that.

## Open questions

1. What the signing operation accepts: raw bytes, JWT claims, Convex values.
2. Whether a root must opt in before identities nested below it have its key
   sign their identity tokens.
3. Signing the venue's outbound calls made as a held identity, per call.
4. Whether the sign operation and the HITL `sign` request (#293) share one
   path.
5. The adapter's and operations' names.
