package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.api.Abilities;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;

/**
 * Adapter for secret store operations.
 *
 * <p>{@code secret:set} — store an encrypted secret in the caller's per-user store.</p>
 */
public class SecretAdapter extends AAdapter {

	private static final AString K_VALUE = Strings.intern("value");
	private static final AString K_STORED = Strings.intern("stored");

	@Override
	public String getName() {
		return "secret";
	}

	@Override
	public String getDescription() {
		return "Manages encrypted secrets in the caller's per-user secret store. "
			+ "Supports storing secrets with automatic redaction in job records.";
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/secrets lives and dies with this adapter.
		installSkill("auth/secrets", "/skills/secrets.json");
		installAsset("secret/set",     "/adapters/secret/set.json");
		installAsset("secret/extract", "/adapters/secret/extract.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Secret operations require an authenticated caller"));
		}

		String op = getSubOperation(meta);
		try {
			switch (op) {
				case "set":
					return CompletableFuture.supplyAsync(() -> handleSet(ctx, input), VIRTUAL_EXECUTOR);
				case "extract":
					// TODO: capability-gated secret extraction
					return CompletableFuture.failedFuture(
						new AuthException("Secret extraction denied; pass an s/<name> reference instead of plaintext"));
				default:
					return CompletableFuture.failedFuture(
						new IllegalArgumentException("Unknown secret operation: " + op));
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell handleSet(RequestContext ctx, ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null) throw new IllegalArgumentException("name is required");

		AString value = RT.ensureString(RT.getIn(input, K_VALUE));
		if (value == null) throw new IllegalArgumentException("value is required");
		ACell overwriteValue = RT.getIn(input, Fields.OVERWRITE);
		if (overwriteValue != null && !(overwriteValue instanceof CVMBool)) {
			throw new IllegalArgumentException("overwrite must be a boolean");
		}
		boolean overwrite = CVMBool.TRUE.equals(overwriteValue);

		store(engine, ctx, name, value, overwrite);
		return Maps.of(Fields.NAME, name, K_STORED, CVMBool.TRUE);
	}

	/** Shared secret-write boundary for operations that generate credentials
	 * without ever returning their plaintext. The capability check and
	 * collision rule therefore cannot drift from {@code secret:set}. */
	static void store(Engine engine, RequestContext ctx, AString name,
			AString value, boolean overwrite) {
		if (ctx.getCallerDID() == null) {
			throw new IllegalArgumentException("Secret operations require an authenticated caller");
		}
		if (name == null || name.toString().isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
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
	}
}
