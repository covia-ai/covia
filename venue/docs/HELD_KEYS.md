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
2. **A keyring is its holder's alone.** Its keys are used, and listed, only
   for a request whose caller is the holder, authenticated as itself or acting
   as itself in trusted in-process code, or by a principal the holder has
   explicitly granted that use. A capability scope can narrow the holder's own
   use, as an agent's caps do.
3. **Nothing implicit reaches a keyring.** Nesting, ownership and acting on an
   identity's behalf confer no use of its keys: an identity nested below a root
   (`<root>:u:alice`) and an agent of an owner use the root's or owner's keys
   only under an explicit grant, and `user/sudo`, an activated allowance or the
   venue's power over its users ([VENUE_AUTHORITY.md](./VENUE_AUTHORITY.md))
   never sign as the identity. A signature travels to other venues, and the
   host root holds only at its own venue.
4. **A grant of key use is the holder's own.** It counts only when its chain
   starts with the holder: signed by the holder, or attested on the holder's
   instruction by the venue custodying its identity (COG-17). A grant rooted in
   the host root never confers key use, so the venue cannot grant itself, or
   anyone, the use of a key it holds for someone else.
5. **Keys are never readable.** No operation returns, exports or lends a
   private key, as for the venue's own key.
6. **One signing function** serves identity tokens, UCAN grants, Convex
   transactions and raw payloads, under the same consent and no-amplification
   rules as any issuance (COG-17).
7. **It signs at the point of use.** The venue mints short-lived tokens when
   they are needed, so no long-lived bearer has to be stored.
8. **Custody can end.** Removing a key, or deleting its holder, ends it. The
   venue then signs nothing new with it; tokens already issued last until they
   expire.
9. **Every use is audited,** naming the holder, the public key and the
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

A mnemonic record has the same fields. Its sealed plaintext names the holder
DID, the master node's public key and the phrase, checked the same way on
opening. A passphrase is not kept with it: `crypto:derive-key` takes one by
secret reference as now.

Using a key writes nothing to the store; the audit log records it.

## Storage rules

- **Deleted with its holder.** Deleting a user runs every adapter's
  `deleteUserState` in the deletion transaction, disabled adapters included,
  and the crypto adapter removes the user's holder entry and those nested
  below it.
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
the holder and the ability the operation needs, opens the record, and makes
an `AKeyPair` that lives for that one call. The existing signers then serve
every use: `AKeyPair.sign` for raw bytes, `JWT.signPublic` for identity tokens
and UCAN grants, `AKeyPair.signData` for Convex values, and ECIES for
decryption. Other adapters (`auth:identity-token`, `ucan:issue`,
`convex:transact`) obtain a held key's `AKeyPair` from the crypto adapter in
the same way, for the one call. Other key types need signers of their own;
Convex 0.8.18 provides Ed25519 only.

A held key is named by its public key: its multibase string (`z6Mk…`), or for
Ed25519 its 32-byte account key as a `Blob`, the form the crypto adapter's
results already use.

## Operations

New crypto operations (names open):

- **Add** a key, refused unless the private key yields the public key. The
  private key is a secret field of the operation (`operation.secretFields`),
  so job records keep it redacted.
- **Remove** a key.
- **List** the caller's own keys: public key, label and when it was added.

Existing crypto operations take a held key as well as a `seed` reference:

- **`crypto:sign`** is the standard signing interface: it signs raw bytes.
  `data` is a `Blob`, which JSON callers may give as a hex string with or
  without `0x`, other string encodings being named explicitly; the result's
  `signature` is a `Blob` (64 bytes for Ed25519), which JSON renders as a `0x`
  hex string. The bytes are signed exactly as given, with no hashing or
  prefix, and messages are limited to 1 MiB.
- **`crypto:jwt-sign`**, **`crypto:decrypt`** and **`crypto:public-key`** work
  the same way with a held key.
- **`crypto:generate-key`** and **`crypto:derive-key`** store the new key as a
  secret, as now, or in the caller's keyring, where its private key is born in
  the venue and never seen.
- **`crypto:generate-mnemonic`** likewise keeps the new phrase as a secret or
  in the caller's keyring, and **`crypto:derive-key`** and
  **`crypto:validate-mnemonic`** take either.

Both stay supported, and the caller chooses: a secret it can read back and
lend, or a keyring entry that nothing can read.

The Convex adapter moves onto the same keyrings:

- `convex:transact` takes a held key, under `key/sign/convex`.
- `convex:sign` duplicates `crypto:sign` and is deprecated in its favour.
- `convex:generate-key` is deprecated in favour of `crypto:generate-key`.

## Abilities

A keyring is the resource `<holder DID>/keys/`, and one key is
`<holder DID>/keys/<public key>`, the key in multibase form. Like
`<did>/oauth/<provider>`, they name resources for capability checks, not
lattice paths.

| Ability | On | Allows |
|---------|----|--------|
| `crud/read` | `<did>/keys/` | Listing the keyring: public keys, labels and when each was added |
| `key/add` | `<did>/keys/<pk>` | Adding that key, or generating or deriving it into the keyring |
| `key/remove` | `<did>/keys/<pk>` | Removing it |
| `key/sign` | `<did>/keys/<pk>` | Signing anything with it; covers every `key/sign/…` |
| `key/sign/token` | `<did>/keys/<pk>` | Signing identity tokens (`auth:identity-token`) |
| `key/sign/grant` | `<did>/keys/<pk>` | Signing UCAN grants (`ucan:issue`) |
| `key/sign/convex` | `<did>/keys/<pk>` | Signing Convex transactions (`convex:transact`) |
| `key/sign/raw` | `<did>/keys/<pk>` | Signing content the caller chooses (`crypto:sign`, `crypto:jwt-sign`), which can produce any of the above |
| `key/decrypt` | `<did>/keys/<pk>` | Decrypting with it (`crypto:decrypt`) |
| `key/derive` | `<did>/keys/<pk>` | Deriving keys from a held mnemonic, named by its master node's public key (`crypto:derive-key`) |

`key` covers every `key/…` ability. The kinds of signing let a capability
scope narrow what a key may sign: an agent can hold `key/sign/token` on its
own key without being able to sign transactions. An operation that signs
whatever its caller supplies, such as `crypto:jwt-sign` with its free claims,
needs `key/sign/raw`.

A check passes in one of two ways, and in no other:

1. **The caller is the holder, and its own base covers the ability.** The
   keyring's DID equals the caller's DID. A user holds every `key/…` ability
   over its own keyring. An agent needs caps naming its own keyring by DID URL,
   since a bare path in caps names its owner's namespace. An `nb.gate` on such
   a cap can make signing wait for an approval.
2. **The caller presents a grant of the ability on that key whose chain starts
   with the holder** (principle 4). The grant bounds the use as any grant does:
   to the key, the kind of signing, an expiry, and any `nb.gate`.

Admission, nesting, ownership, `user/sudo` and grants rooted in the host root
never pass. An agent with no caps, whose base is its owner's authority, still
cannot use its owner's keys, because its DID is not its owner's.

A nested identity whose identity token is to be signed by its root's key
needs a grant of `key/sign/token` on that key from the root. The venue's own
key is the venue's keyring entry under the same rules: the venue acting as
itself, in trusted code, uses it, and anyone else only under an explicit grant
from the venue.

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
- **No implicit use.** A key is used only by its holder or under the holder's
  explicit grant, so a deputy acting for the holder, an identity nested below
  it, or the venue acting on its behalf can never make the venue sign as the
  holder.
- **Granting key use is granting a signature.** A grantee of `key/sign/raw`
  can make the key sign anything, so its holder should grant the narrowest
  kind of signing, on one key, for the shortest time. A grant of the venue's
  own key hands out the venue's signature, the sharpest grant there is.

## Open questions

1. When the deprecated Convex signing operations are removed, and how an owner
   moves an existing seed or phrase from the secret store into their keyring.
2. Identities nested below the venue's own `did:key` mint identity tokens with
   the venue's key today without any grant ([AUTH.md](./AUTH.md) §5.2). Under
   these rules they need one: whether the venue grants it to them standing, or
   that use ends.
3. Signing the venue's outbound calls made as a held identity, per call.
4. Whether `crypto:sign` and the HITL `sign` request (#293) share one path.
5. The new operations' names.
