package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.auth.ucan.Capability;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.api.Abilities;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;
import covia.venue.Audit;
import covia.venue.VenueJob;

/**
 * Adapter for secret store operations.
 *
 * <p>Discovery and management use the existing per-user encrypted store.
 * Plaintext extraction is restricted to direct owner calls.</p>
 */
public class SecretAdapter extends AAdapter {

	private static final AString K_VALUE = Strings.intern("value");
	private static final AString K_STORED = Strings.intern("stored");
	private static final AString K_NAMES = Strings.intern("names");
	private static final AString K_PREFIX = Strings.intern("prefix");
	private static final AString K_OFFSET = Strings.intern("offset");
	private static final AString K_LIMIT = Strings.intern("limit");
	private static final AString K_TOTAL = Strings.intern("total");
	private static final AString K_NEXT_OFFSET = Strings.intern("nextOffset");
	private static final AString K_DELETED = Strings.intern("deleted");
	private static final AString EXTRACT = Strings.intern("extract");
	public static final String AUTH_REQUIRED = "Secret operations require an authenticated caller";
	public static final String NAME_REQUIRED = "name is required";
	public static final String INVALID_NAME = "name must be a single secret name, not a path or control text";
	public static final String VALUE_REQUIRED = "value is required";
	public static final String INVALID_OVERWRITE = "overwrite must be a boolean";
	public static final String INVALID_INPUT = "Secret operation input must be an object";
	public static final String INVALID_PREFIX = "prefix must be a string";
	public static final String INVALID_PAGE = "offset must be a non-negative integer and limit an integer from 1 to 1000";
	public static final String UNKNOWN_OPERATION = "Unknown secret operation: ";
	public static final String EXTRACT_DIRECT_ONLY = "Secret extraction requires a direct owner run with no recorded job, agent or session context";
	public static final String EXTRACT_DEFINITION_REQUIRED = "Secret extraction requires the published operation definition";
	public static final String SECRET_UNAVAILABLE = "Secret is missing or cannot be decrypted";

	@Override
	public String getName() {
		return "secret";
	}

	@Override
	public String getDescription() {
		return "Manages encrypted secrets in the caller's per-user secret store. "
			+ "Lists names, stores and deletes credentials, and supports restricted owner-only plaintext extraction.";
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/secrets lives and dies with this adapter.
		installSkill("auth/secrets", "/skills/secrets.json");
		installAsset("secret/set",     "/adapters/secret/set.json");
		installAsset("secret/list",    "/adapters/secret/list.json");
		installAsset("secret/delete",  "/adapters/secret/delete.json");
		installAsset("secret/extract", "/adapters/secret/extract.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) {
			return CompletableFuture.failedFuture(
				new AuthException(AUTH_REQUIRED));
		}

		String op = getSubOperation(meta);
		try {
			if (!(input instanceof AMap) && !(input == null && "list".equals(op))) {
				throw new IllegalArgumentException(INVALID_INPUT);
			}
			switch (op) {
				case "set":
					return CompletableFuture.supplyAsync(() -> handleSet(ctx, input), VIRTUAL_EXECUTOR);
				case "extract":
					return CompletableFuture.completedFuture(handleExtract(ctx, meta, input));
				case "list":
					return CompletableFuture.completedFuture(handleList(ctx, input));
				case "delete":
					return CompletableFuture.completedFuture(handleDelete(ctx, input));
				default:
					return CompletableFuture.failedFuture(
						new IllegalArgumentException(UNKNOWN_OPERATION + op));
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell handleSet(RequestContext ctx, ACell input) {
		AString name = name(input);

		AString value = RT.ensureString(RT.getIn(input, K_VALUE));
		if (value == null) throw new IllegalArgumentException(VALUE_REQUIRED);
		ACell overwriteValue = RT.getIn(input, Fields.OVERWRITE);
		if (overwriteValue != null && !(overwriteValue instanceof CVMBool)) {
			throw new IllegalArgumentException(INVALID_OVERWRITE);
		}
		boolean overwrite = CVMBool.TRUE.equals(overwriteValue);

		storeSecret(engine, ctx, name, value, overwrite);
		return Maps.of(Fields.NAME, name, K_STORED, CVMBool.TRUE);
	}

	private ACell handleList(RequestContext ctx, ACell input) {
		engine.requireResourceAccess(ctx, Strings.create("s/"), Capability.CRUD_READ);
		ACell filter = RT.getIn(input, K_PREFIX);
		if (filter != null && !(filter instanceof AString)) throw new IllegalArgumentException(INVALID_PREFIX);
		String prefix = filter == null ? "" : filter.toString();
		long offset = pageNumber(input, K_OFFSET, 0, Long.MAX_VALUE);
		long limit = pageNumber(input, K_LIMIT, 100, 1000);
		if (limit == 0) throw new IllegalArgumentException(INVALID_PAGE);
		User user = engine.getVenueState().users().get(ctx.getUserDID());
		AVector<AString> names = user == null ? Vectors.empty() : user.secrets().list();
		AVector<AString> page = Vectors.empty();
		long total = 0;
		for (AString name : names) {
			if (!name.toString().startsWith(prefix)) continue;
			if (total++ >= offset && page.count() < limit) page = page.append(name);
		}
		AMap<AString, ACell> result = Maps.of(K_NAMES, page, K_TOTAL, total, K_OFFSET, offset);
		if (offset < total && page.count() < total - offset) result = result.assoc(K_NEXT_OFFSET, CVMLong.create(offset + page.count()));
		return result;
	}

	private static long pageNumber(ACell input, AString key, long fallback, long maximum) {
		ACell value = RT.getIn(input, key);
		if (value == null) return fallback;
		if (!(value instanceof CVMLong n) || n.longValue() < 0 || n.longValue() > maximum) throw new IllegalArgumentException(INVALID_PAGE);
		return n.longValue();
	}

	private ACell handleDelete(RequestContext ctx, ACell input) {
		AString name = name(input);
		engine.requireResourceAccess(ctx, resource(name), Capability.CRUD_DELETE);
		User user = engine.getVenueState().users().get(ctx.getUserDID());
		boolean deleted = user != null && user.secrets().deleteIfPresent(name);
		if (deleted) engine.audit().event(Audit.SECRET_DELETE, Audit.K_DID, ctx.getCallerDID(),
			Audit.K_NAME, name, Audit.K_TARGET, ctx.getUserDID());
		return Maps.of(Fields.NAME, name, K_DELETED, CVMBool.create(deleted));
	}

	private ACell handleExtract(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		AString name = name(input);
		try {
			if (!(ctx.getJob() instanceof VenueJob job) || !job.isDirectRun()
					|| ctx.getAgentId() != null || ctx.getSessionId() != null || ctx.getTaskId() != null
					|| !ctx.getCallerDID().equals(ctx.getUserDID())) throw new AuthException(EXTRACT_DIRECT_ONLY);
			engine.requireResourceAccess(ctx, resource(name), Abilities.SECRET_EXTRACT);
			// A caller-controlled output schema could put plaintext in validation logs.
			// Use the adapter-owned definition, including its safe output schema.
			if (!getOperations().get(EXTRACT).get(Fields.OPERATION).equals(meta.get(Fields.OPERATION))) {
				throw new AuthException(EXTRACT_DEFINITION_REQUIRED);
			}
			User user = engine.getVenueState().users().get(ctx.getUserDID());
			AString value;
			try {
				value = user == null ? null : user.secrets().decrypt(name, SecretStore.deriveKey(engine.getKeyPair()));
			} catch (RuntimeException e) { throw new IllegalStateException(SECRET_UNAVAILABLE); }
			if (value == null) throw new IllegalStateException(SECRET_UNAVAILABLE);
			engine.audit().event(Audit.SECRET_EXTRACT, Audit.K_DID, ctx.getCallerDID(), Audit.K_NAME, name, Audit.K_OUTCOME, Audit.OK);
			return Maps.of(Fields.NAME, name, K_VALUE, value);
		} catch (RuntimeException e) {
			engine.audit().event(Audit.SECRET_EXTRACT, Audit.K_DID, ctx.getCallerDID(), Audit.K_NAME, name, Audit.K_OUTCOME, Audit.FAILED);
			throw e;
		}
	}

	private static AString resource(AString name) { return Strings.create("s/" + name); }

	private static AString name(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		validateName(name);
		return name;
	}

	private static void validateName(AString name) {
		if (name == null || name.toString().isBlank()) throw new IllegalArgumentException(NAME_REQUIRED);
		String s = name.toString();
		if (s.contains("/") || s.contains("\\") || s.equals(".") || s.equals("..")
				|| s.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(INVALID_NAME);
	}

	/** Shared secret-write boundary for operations that generate credentials
	 * without ever returning their plaintext. The capability check and
	 * collision rule therefore cannot drift from {@code secret:set}. */
	static void storeSecret(Engine engine, RequestContext ctx, AString name,
			AString value, boolean overwrite) {
		if (ctx.getCallerDID() == null) {
			throw new IllegalArgumentException(AUTH_REQUIRED);
		}
		validateName(name);
		// Pin the capability to the action: writing a secret requires
		// secret/write on the secret resource. A null grant scope (authenticated /
		// internal) is unrestricted; a read-only scope (the public profile)
		// is denied here — closing the unauthenticated secret-write gap (#148).
		engine.requireAuthority(ctx, Strings.create("s/" + name), Abilities.SECRET_WRITE);

		// Secrets live in the user's store; an agent writes into its owner's,
		// gated above by secret/write in its capability scope.
		User user = engine.getVenueState().users().ensure(ctx.getUserDID());
		byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
		if (overwrite) {
			user.secrets().store(name, value, encKey);
		} else if (!user.secrets().storeIfAbsent(name, value, encKey)) {
			throw new IllegalArgumentException("Secret s/" + name
				+ " already exists; pass overwrite:true to replace it");
		}
		// The name only, never the value.
		engine.audit().event(covia.venue.Audit.SECRET_WRITE, covia.venue.Audit.K_DID, ctx.getCallerDID(),
			covia.venue.Audit.K_NAME, name, covia.venue.Audit.K_TARGET, ctx.getUserDID());
	}
}
