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
		installAsset("user/create", "/adapters/user/create.json");
		installAsset("user/info", "/adapters/user/info.json");
		installAsset("user/list", "/adapters/user/list.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		try {
			return CompletableFuture.completedFuture(switch (getSubOperation(meta)) {
				case "create" -> create(ctx, input);
				case "info" -> info(ctx, input);
				case "list" -> list(ctx);
				default -> throw new IllegalArgumentException(
					"Unknown user operation: " + getSubOperation(meta));
			});
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell create(RequestContext ctx, ACell input) {
		requireVenueUserAuthority(ctx, Abilities.USER_CREATE);
		AString suppliedDID = RT.ensureString(RT.getIn(input, Fields.DID));
		AString username = RT.ensureString(RT.getIn(input, USERNAME));
		if ((suppliedDID == null) == (username == null)) {
			throw new IllegalArgumentException(
				"Specify exactly one of 'did' or 'username'");
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
		return Maps.of(
			Fields.DID, did,
			REGISTERED, CVMBool.TRUE,
			CREATED, CVMBool.create(created));
	}

	private ACell info(RequestContext ctx, ACell input) {
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

	private ACell list(RequestContext ctx) {
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

	private AMap<AString, ACell> summary(User user) {
		AMap<AString, ACell> out = Maps.of(
			Fields.DID, user.getDID(),
			REGISTERED, CVMBool.TRUE);
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
		AString caller = (ctx != null) ? ctx.getCallerDID() : null;
		if (engine.getDIDString().equals(caller) && ctx.getAgentId() == null) return;
		AString resource = Strings.create(engine.getDIDString() + "/users");
		if (engine.crossUserAllows(ctx, resource, ability)) return;
		throw new AuthException("User administration denied: requires " + ability
			+ " on " + resource + " from the venue (call as the venue or present "
			+ "a venue-issued delegation)");
	}
}
