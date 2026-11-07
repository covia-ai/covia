# Held Keys (design)

How a venue holds the private key of an identity in its custody, and signs
as that identity. This is a design; none of it is implemented yet.

Tracked by covia#637.

## What a held key is

A held key is custody of an identity, not a piece of data. Whoever holds a
`did:key`'s private key is that identity, at every venue. Held keys therefore
follow the rules of identity, not those of secrets: a secret is data its
owner can read (`secret/extract`) and lend (`secret/use`), and a held key
allows neither.

Today `auth:identity-token` signs only with the venue's own key, for the
venue's `did:key` and identities nested below it ([AUTH.md](./AUTH.md) §5.2).
An embedder whose installation has its own `did:key` keeps that key in its
own code and signs with `VenueAuth.namedKeyPair` itself.

## Principles

1. **Giving the key is the consent.** The venue checks that the key is the
   identity's: a `did:key` is its public key, so nothing else is needed. From
   then on the venue custodies that identity, the condition COG-17 sets for
   signing as an owner's custodian.
2. **It signs only for its own identity.** A held key signs only for a request
   whose caller is its identity or an identity nested below it, authenticated
   as itself or acting as itself in trusted in-process code. Acting on an
   identity's behalf never signs as it: not through `user/sudo`, an activated
   allowance, or the venue's power over its users
   ([VENUE_AUTHORITY.md](./VENUE_AUTHORITY.md)). A signature travels to other
   venues, and the host root holds only at its own venue. The same rule
   covers the venue's own key and the custodial users it signs for.
3. **It signs only what its identity could sign itself,** under the same
   consent and no-amplification rules as any issuance (COG-17).
4. **It is never readable.** No operation returns, exports or lends it, as for
   the venue's own key.
5. **It is named by its DID.** A `did:key` is its public key, so an identity
   has exactly one key and needs no key names or identifiers. A `did:key`
   cannot rotate: a new key is a new identity.
6. **Custody can end.** Removing the key, or deleting its identity, ends it.
   The venue then signs nothing new for the identity; tokens already issued
   last until they expire.
7. **It signs at the point of use.** With the key held, the venue mints
   short-lived tokens when they are needed, so no long-lived bearer has to be
   stored.
8. **Every signing is audited,** naming the identity whose key signed and the
   principal that asked.

## Storage

- Held keys belong to their own adapter and live in its state
  (`:adapters/<name>`, [ADAPTERS.md](./ADAPTERS.md)). Each is the identity's
  Ed25519 seed, stored under its `did:key` and sealed with `SecretStore.seal`
  under keys derived from the venue's seed, as the OAuth adapter seals
  provider tokens.
- **Versioned.** The adapter's state layout is versioned from its first
  release: each change is a migration step, and the venue records the version
  reached in `:adapter-versions` (ADAPTERS.md, "Versioned state and
  migration"). Each sealed record also carries the version of the key that
  sealed it, so records can be re-encrypted when the venue's key rotates
  (#535), independently of the layout version.
- **Deleted with its identity.** Deleting a user runs every adapter's
  `deleteUserState` in the deletion transaction, disabled adapters included,
  so an identity's key goes with it.
- **Disabling the adapter** leaves the keys sealed in its state, and nothing
  signs with them until it is enabled again.
- **The venue's own key stays where it is,** loaded from `seed`, `keystore` or
  `venue.key` ([CONFIG.md](./CONFIG.md), "Venue identity"). It is the root
  every sealed record depends on, so it cannot live in state it unlocks, and
  signing with it does not depend on the adapter.

## Operations

The adapter's operations (names open):

- **Add** a key for an identity, refused unless it matches the DID. The key
  is a secret field of the operation (`operation.secretFields`), so job
  records keep it redacted.
- **Remove** a key, ending custody.
- **List** the identities whose keys the venue holds, by DID only.

`auth:identity-token` signs with a held key when the caller's identity, or the
`did:key` it is nested below, is held, as it signs with the venue's key today.

## Open questions

1. Who may add a key: only a caller authenticated as that identity, or also
   trusted code or the operator for identities the venue manages, such as an
   embedder's installation. Whether the identity must be a venue user.
2. Whether a root may ask for a token for an identity nested below it, which
   `auth:identity-token` refuses today by taking no subject from input.
3. Whether a held key also signs grants as its identity, which verify
   anywhere without the venue's attestation.
4. Signing the venue's outbound calls made as a held identity, per call.
5. A general signing operation, for payloads such as Convex transactions,
   beside the HITL `sign` request (#293).
6. Who may list held keys.
7. Identities other than `did:key`.
8. The adapter's and operations' names.
