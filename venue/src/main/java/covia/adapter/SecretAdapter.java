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
					return CompletableFuture.supplyAsync(() -> handleSet(input, callerDID), VIRTUAL_EXECUTOR);
				case "extract":
					// TODO: capability-gated secret extraction
					return CompletableFuture.failedFuture(
						new AuthException("No capability to extract secrets"));
				default:
					return CompletableFuture.failedFuture(
						new IllegalArgumentException("Unknown secret operation: " + op));
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell handleSet(ACell input, AString callerDID) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null) throw new IllegalArgumentException("name is required");

		AString value = RT.ensureString(RT.getIn(input, K_VALUE));
		if (value == null) throw new IllegalArgumentException("value is required");

		User user = engine.getVenueState().users().ensure(callerDID);
		byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
		user.secrets().store(name, value, encKey);

		return Maps.of(Fields.NAME, name, K_STORED, CVMBool.TRUE);
	}
}
