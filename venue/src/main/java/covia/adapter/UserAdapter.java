package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.Audit;
import covia.exception.AuthException;
import covia.lattice.Covia;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.UserExports;
import covia.venue.Users;
import covia.venue.VenueJob;

/**
 * Venue user registration and discovery operations.
 *
 * <p>A runtime user ID is always a DID. {@code user:create} accepts any valid
 * DID directly, including self-sovereign did:key identities, or derives a
 * venue-managed did:web identity from a username. Registration is an explicit
 * administrative action; successful authentication alone does not grant it.</p>
 */
public class UserAdapter extends AAdapter {

	private static final AString USERNAME = Strings.intern("username");
	private static final AString REGISTERED = Strings.intern("registered");
	private static final AString CREATED = Fields.CREATED;
	private static final AString USERS = Strings.intern("users");
	private static final AString TOTAL = Strings.intern("total");
	private static final AString ADDED = Strings.intern("added");
	private static final AString REVOKED = Strings.intern("revoked");
	private static final AString EXPORT = Strings.intern("export");
	private static final AString ACCOUNT_KEY = Strings.intern("accountKey");
	private static final AString SECRETS = Strings.intern("secrets");

	public static final String EXPORT_DIRECT_ONLY = "User export requires a direct run with no recorded job, agent or session context";
	public static final String EXPORT_DEFINITION_REQUIRED = "User export requires the published operation definition";
	public static final String INVALID_ACCOUNT_KEY = "accountKey must be a 32-byte Ed25519 public key";
	public static final String INVALID_SECRETS = "secrets must be a boolean";
	public static final String SEED_REF_REQUIRED = "seed must be a secret reference (s/NAME or <did>/s/NAME), never a raw seed";
	public static final String INVALID_SEED = "seed must name a secret holding a 32-byte hexadecimal Ed25519 seed";

	@Override
	public String getName() {
		return "user";
	}

	@Override
	public String getDescription() {
		return "Explicit venue user registration and discovery. User IDs are arbitrary DIDs; "
			+ "a username is a convenience for creating a venue-managed did:web identity.";
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/users lives and dies with this adapter.
		installSkill("admin/users", "/skills/users.json");
		installAsset("user/create", "/adapters/user/create.json");
		installAsset("user/delete", "/adapters/user/delete.json");
		installAsset("user/export", "/adapters/user/export.json");
		installAsset("user/import", "/adapters/user/import.json");
		installAsset("user/clone", "/adapters/user/clone.json");
		installAsset("user/sudo", "/adapters/user/sudo.json");
		installAsset("user/info", "/adapters/user/info.json");
		installAsset("user/list", "/adapters/user/list.json");
		installAsset("user/authentication-add", "/adapters/user/authentication-add.json");
		installAsset("user/authentication-revoke", "/adapters/user/authentication-revoke.json");
		installAsset("user/authentication-list", "/adapters/user/authentication-list.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOperation = getSubOperation(meta);
		String event = switch (subOperation) {
			case "create" -> Audit.USER_CREATE;
			case "delete" -> Audit.USER_DELETE;
			case "export" -> Audit.USER_EXPORT;
			case "import" -> Audit.USER_IMPORT;
			case "clone" -> Audit.USER_CLONE;
			case "sudo" -> Audit.USER_SUDO;
			case "authentication-add" -> Audit.KEY_ADD;
			case "authentication-revoke" -> Audit.KEY_REVOKE;
			default -> null;   // reads are not audited
		};
		CompletableFuture<ACell> result;
		if ("sudo".equals(subOperation)) {
			result = sudo(ctx, input);
		} else try {
			result = CompletableFuture.completedFuture(switch (subOperation) {
				case "create" -> create(ctx, input);
				case "delete" -> engine.deleteUser(ctx, requireDID(RT.ensureString(RT.getIn(input, Fields.DID))));
				case "export" -> export(ctx, meta, input);
				case "import" -> importUser(ctx, input);
				case "clone" -> cloneUser(ctx, input);
				case "info" -> info(ctx, input);
				case "list" -> list(ctx);
				case "authentication-add" -> authenticationAdd(ctx, input);
				case "authentication-revoke" -> authenticationRevoke(ctx, input);
				case "authentication-list" -> authenticationList(ctx, input);
				default -> throw new IllegalArgumentException(
					"Unknown user operation: " + subOperation);
			});
		} catch (Exception e) {
			result = CompletableFuture.failedFuture(e);
		}
		if (event != null && engine.audit().enabled()) {
			// The target user, the key, and for sudo the operation run — never its input.
			Object target = RT.getIn(input, "clone".equals(subOperation) ? Fields.TO : Fields.DID);
			Object key = RT.getIn(input, Fields.KEY);
			Object operation = "sudo".equals(subOperation) ? RT.getIn(input, Fields.OPERATION) : null;
			result.whenComplete((r, t) -> engine.audit().event(event,
				Audit.K_DID, ctx.getCallerDID(), Audit.K_TARGET, target, "key", key,
				Audit.K_OPERATION, operation, Audit.K_OUTCOME, (t == null) ? Audit.OK : Audit.FAILED));
		}
		return result;
	}

	/** Explicitly execute one operation in another user's namespace. */
	private CompletableFuture<ACell> sudo(RequestContext ctx, ACell input) {
		try {
			AString userDID = requireDID(RT.ensureString(RT.getIn(input, Fields.DID)));
			AString operation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
			if (operation == null) throw new IllegalArgumentException("operation is required");
			RequestContext sudo = engine.sudoContext(ctx, userDID);
			return engine.jobs().invokeInternal(operation, RT.getIn(input, Fields.INPUT), sudo);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell create(RequestContext ctx, ACell input) {
		requireVenueUserAuthority(ctx, Abilities.USER_CREATE);
		AString suppliedDID = RT.ensureString(RT.getIn(input, Fields.DID));
		AString username = RT.ensureString(RT.getIn(input, USERNAME));
		AVector<ACell> authenticationKeys =
			RT.ensureVector(RT.getIn(input, Fields.AUTHENTICATION_KEYS));
		if ((suppliedDID == null) == (username == null)) {
			throw new IllegalArgumentException(
				"Specify exactly one of 'did' or 'username'");
		}
		if (suppliedDID != null && authenticationKeys != null) {
			throw new IllegalArgumentException(
				"authenticationKeys are supported only for venue-managed usernames");
		}

		AString did;
		if (suppliedDID != null) {
			did = requireDID(suppliedDID);
		} else {
			did = requireDID(engine.managedUserDID(username));
		}
		// A user identity must never be agent-shaped: <owner>:g:<id> resolves to
		// the owner's namespace, so registering one would grant its bearer that
		// account.
		engine.requireNotSubPrincipal(did);

		boolean created = engine.getVenueState().users().create(did);
		if (username != null) {
			engine.getAuth().ensureManagedUser(username, did);
			if (authenticationKeys != null) {
				AMap<AString, ACell> existing =
					engine.getAuth().getAuthenticationKeys(username);
				if (existing.isEmpty()) {
					engine.getAuth().addAuthenticationKeys(
						username, authenticationKeys, ctx.getCallerDID());
				} else {
					// Preserve create's idempotency: a repeated identical request
					// is harmless, but rotation uses the explicit add operation.
					for (long i = 0; i < authenticationKeys.count(); i++) {
						AString key = RT.ensureString(authenticationKeys.get(i));
						if (!engine.getAuth().isAuthenticationKeyActive(username, key)) {
							throw new IllegalArgumentException(
								"Named user already has authenticator history; "
								+ "use user:authentication-add for rotation");
						}
					}
				}
			}
		}
		return Maps.of(
			Fields.DID, did,
			REGISTERED, CVMBool.TRUE,
			CREATED, CVMBool.create(created));
	}

	/**
	 * A user's whole state as one value. Its result is never recorded: a
	 * recorded job would hold a copy of the user in the caller's own namespace,
	 * and the next export would carry it again.
	 */
	private ACell export(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (!(ctx.getJob() instanceof VenueJob job) || !job.isDirectRun()
				|| ctx.getExecutionScope().agentId() != null || ctx.getExecutionScope().sessionId() != null || ctx.getExecutionScope().taskId() != null
				|| !ctx.getCallerDID().equals(ctx.getUserDID())) throw new AuthException(EXPORT_DIRECT_ONLY);
		// A caller-controlled output schema could put the state in validation logs.
		if (!getOperations().get(EXPORT).get(Fields.OPERATION).equals(meta.get(Fields.OPERATION))) {
			throw new AuthException(EXPORT_DEFINITION_REQUIRED);
		}
		AString did = requireDID(targetDID(ctx, input));
		requireSelfOrVenueUserAuthority(ctx, did, Abilities.USER_EXPORT);
		ACell secrets = RT.getIn(input, SECRETS);
		if (secrets != null && !(secrets instanceof CVMBool)) throw new IllegalArgumentException(INVALID_SECRETS);
		AccountKey recipient = null;
		ACell suppliedKey = RT.getIn(input, ACCOUNT_KEY);
		if (suppliedKey != null) {
			ABlob key = Blobs.parse(suppliedKey);
			if (key == null || key.count() != AccountKey.LENGTH) throw new IllegalArgumentException(INVALID_ACCOUNT_KEY);
			recipient = AccountKey.create(key);
		}
		Blob export = new UserExports(engine).exportUser(did, recipient, !CVMBool.FALSE.equals(secrets));
		// Hex, as JSON carries it: the published output is a string, and import reads either form.
		return Maps.of(Fields.DID, did, Fields.VERSION, UserExports.VERSION,
			EXPORT, Strings.create("0x" + export.toHexString()));
	}

	/**
	 * Fills an account from an export. Venue authority only: a user importing
	 * into their own account would find it already holding the import's job.
	 */
	private ACell importUser(RequestContext ctx, ACell input) {
		AString did = requireDID(RT.ensureString(RT.getIn(input, Fields.DID)));
		requireVenueUserAuthority(ctx, Abilities.USER_IMPORT);
		ABlob export = Blobs.parse(RT.getIn(input, EXPORT));
		if (export == null) throw new IllegalArgumentException(UserExports.NOT_AN_EXPORT);
		boolean created = new UserExports(engine).importUser(export, did, seed(ctx, input));
		return Maps.of(Fields.DID, did, CREATED, CVMBool.create(created));
	}

	private ACell cloneUser(RequestContext ctx, ACell input) {
		AString from = requireDID(RT.ensureString(RT.getIn(input, Fields.FROM)));
		AString to = requireDID(RT.ensureString(RT.getIn(input, Fields.TO)));
		requireSelfOrVenueUserAuthority(ctx, from, Abilities.USER_EXPORT);
		requireVenueUserAuthority(ctx, Abilities.USER_IMPORT);
		boolean created = new UserExports(engine).cloneUser(from, to);
		return Maps.of(Fields.FROM, from, Fields.TO, to, CREATED, CVMBool.create(created));
	}

	/** The key pair named by the optional {@code seed} secret reference. */
	private AKeyPair seed(RequestContext ctx, ACell input) {
		ACell supplied = RT.getIn(input, Fields.SEED);
		if (supplied == null) return null;
		AString ref = RT.ensureString(supplied);
		if (ref == null || !Engine.isSecretRef(ref.toString())) throw new IllegalArgumentException(SEED_REF_REQUIRED);
		String value = engine.resolveSecret(ref.toString(), ctx);
		ABlob seed = (value == null) ? null : Blobs.parse(value);
		if (seed == null || seed.count() != AKeyPair.SEED_LENGTH) throw new IllegalArgumentException(INVALID_SEED);
		return AKeyPair.create(seed.toFlatBlob());
	}

	/** Job-free REST reads (#255) call this directly, reusing the same
	 *  capability checks as the {@code user:info} operation. */
	public ACell info(RequestContext ctx, ACell input) {
		AString did = RT.ensureString(RT.getIn(input, Fields.DID));
		if (did == null) did = ctx.getCallerDID();
		if (did == null) throw new AuthException("Authentication required");
		did = requireDID(did);
		if (!did.equals(ctx.getCallerDID())) {
			requireVenueUserAuthority(ctx, Abilities.USER_READ);
		}
		User user = engine.getVenueState().users().get(did);
		if (user == null) {
			throw new IllegalArgumentException("User is not registered at this venue: " + did);
		}
		return summary(user);
	}

	/** Job-free REST reads (#255) call this directly, reusing the same
	 *  capability checks as the {@code user:list} operation. */
	public ACell list(RequestContext ctx) {
		requireVenueUserAuthority(ctx, Abilities.USER_READ);
		Users store = engine.getVenueState().users();
		AMap<AString, ACell> all = store.getAll();
		AVector<ACell> result = Vectors.empty();
		if (all != null) {
			for (var entry : all.entrySet()) {
				User user = store.get(entry.getKey());
				if (user != null) result = result.conj(summary(user));
			}
		}
		return Maps.of(USERS, result, TOTAL, CVMLong.create(result.count()));
	}

	private ACell authenticationAdd(RequestContext ctx, ACell input) {
		AString did = targetManagedUser(ctx, input, Abilities.USER_AUTH_MANAGE);
		AString id = engine.managedUserName(did);
		AString key = RT.ensureString(RT.getIn(input, Fields.KEY));
		AString label = RT.ensureString(RT.getIn(input, Fields.LABEL));
		if (key == null) throw new IllegalArgumentException("key is required");
		boolean added = engine.getAuth().addAuthenticationKey(
			id, key, ctx.getCallerDID(), label);
		engine.flush();
		return Maps.of(
			Fields.DID, did,
			Fields.KEY, key,
			ADDED, CVMBool.create(added));
	}

	private ACell authenticationRevoke(RequestContext ctx, ACell input) {
		AString did = targetManagedUser(ctx, input, Abilities.USER_AUTH_MANAGE);
		AString id = engine.managedUserName(did);
		AString key = RT.ensureString(RT.getIn(input, Fields.KEY));
		if (key == null) throw new IllegalArgumentException("key is required");
		boolean directVenue = engine.getDIDString().equals(ctx.getCallerDID())
			&& ctx.getExecutionScope().agentId() == null;
		boolean revoked = engine.getAuth().revokeAuthenticationKey(
			id, key, ctx.getCallerDID(), directVenue);
		engine.flush();
		return Maps.of(
			Fields.DID, did,
			Fields.KEY, key,
			REVOKED, CVMBool.create(revoked));
	}

	/** Job-free REST reads (#255) call this directly, reusing the same
	 *  capability checks as the {@code user:authentication-list} operation. */
	public ACell authenticationList(RequestContext ctx, ACell input) {
		AString did = requireDID(targetDID(ctx, input));
		requireSelfOrVenueUserAuthority(ctx, did, Abilities.USER_READ);
		AString id = engine.managedUserName(did);
		if (id != null) {
			return Maps.of(
				Fields.DID, did,
				Fields.AUTHENTICATION_KEYS, engine.getAuth().getAuthenticationKeys(id));
		}
		// A registered external DID authenticates with its own key, so the venue
		// holds no authenticators for it: the answer is empty, not an error, and
		// clients list every user's authenticators uniformly (#524).
		if (engine.getVenueState().users().get(did) == null) {
			throw new IllegalArgumentException("User is not registered at this venue: " + did);
		}
		return Maps.of(
			Fields.DID, did,
			Fields.AUTHENTICATION_KEYS, Maps.empty());
	}

	/**
	 * Resolve a local managed-user target. Self-management is allowed only for
	 * the named user itself (never an agent sub-principal); every other target
	 * goes through the venue-rooted user authority seam.
	 */
	private AString targetManagedUser(RequestContext ctx, ACell input, AString ability) {
		AString did = targetDID(ctx, input);
		if (engine.managedUserName(did) == null) {
			throw new IllegalArgumentException(
				"Authentication keys belong only to venue-managed named users");
		}
		requireSelfOrVenueUserAuthority(ctx, did, ability);
		return did;
	}

	/** The addressed user DID, defaulting to the caller. */
	private AString targetDID(RequestContext ctx, ACell input) {
		AString did = RT.ensureString(RT.getIn(input, Fields.DID));
		if (did == null) did = ctx.getCallerDID();
		if (did == null) throw new AuthException("Authentication required");
		return did;
	}

	private void requireSelfOrVenueUserAuthority(RequestContext ctx, AString did, AString ability) {
		if (did.equals(ctx.getCallerDID()) && ctx.getExecutionScope().agentId() == null) return;
		requireVenueUserAuthority(ctx, ability);
	}

	private AMap<AString, ACell> summary(User user) {
		AMap<AString, ACell> out = Maps.of(
			Fields.DID, user.getDID(),
			REGISTERED, CVMBool.TRUE,
			Fields.MANAGED, CVMBool.create(engine.managedUserName(user.getDID()) != null));
		ACell state = user.get();
		if (state instanceof AMap<?, ?> map) {
			ACell metadata = ((AMap<?, ?>) map).get(Covia.K_META);
			if (metadata != null) out = out.assoc(Covia.K_META, metadata);
		}
		return out;
	}

	private AString requireDID(AString value) {
		try {
			if (value != null && DID.fromString(value.toString()) != null) return value;
		} catch (RuntimeException ignored) {
			// Converted below into a short, caller-recoverable validation error.
		}
		throw new IllegalArgumentException("Invalid user DID: " + value);
	}

	/**
	 * Administrative user operations are venue-owned. A null capability scope
	 * is intentionally not enough: only direct venue execution or a presented
	 * venue-rooted delegation over {@code <venue DID>/users} can authorise them.
	 */
	private void requireVenueUserAuthority(RequestContext ctx, AString ability) {
		engine.requireVenueAuthority(ctx, "users", ability);
	}
}
