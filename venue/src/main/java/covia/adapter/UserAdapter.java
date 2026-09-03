package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.lattice.Covia;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.Users;

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
		if ("sudo".equals(subOperation)) return sudo(ctx, input);
		try {
			return CompletableFuture.completedFuture(switch (subOperation) {
				case "create" -> create(ctx, input);
				case "info" -> info(ctx, input);
				case "list" -> list(ctx);
				case "authentication-add" -> authenticationAdd(ctx, input);
				case "authentication-revoke" -> authenticationRevoke(ctx, input);
				case "authentication-list" -> authenticationList(ctx, input);
				default -> throw new IllegalArgumentException(
					"Unknown user operation: " + subOperation);
			});
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
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
			&& ctx.getAgentId() == null;
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
		AString did = targetManagedUser(ctx, input, Abilities.USER_READ);
		AString id = engine.managedUserName(did);
		return Maps.of(
			Fields.DID, did,
			Fields.AUTHENTICATION_KEYS, engine.getAuth().getAuthenticationKeys(id));
	}

	/**
	 * Resolve a local managed-user target. Self-management is allowed only for
	 * the named user itself (never an agent sub-principal); every other target
	 * goes through the venue-rooted user authority seam.
	 */
	private AString targetManagedUser(RequestContext ctx, ACell input, AString ability) {
		AString did = RT.ensureString(RT.getIn(input, Fields.DID));
		if (did == null) did = ctx.getCallerDID();
		if (did == null) throw new AuthException("Authentication required");
		if (engine.managedUserName(did) == null) {
			throw new IllegalArgumentException(
				"Authentication keys belong only to venue-managed named users");
		}
		if (did.equals(ctx.getCallerDID()) && ctx.getAgentId() == null) return did;
		requireVenueUserAuthority(ctx, ability);
		return did;
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
			if (DID.fromString(value.toString()) != null) return value;
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
