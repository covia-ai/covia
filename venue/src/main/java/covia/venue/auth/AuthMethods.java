package covia.venue.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import covia.adapter.AAdapter;
import covia.adapter.AuthAdapter;
import covia.exception.AuthException;
import covia.venue.Engine;

/** Resolves configured bindings against the live adapter set; owns no lifecycle. */
public final class AuthMethods {
	public static final AString METHODS = Strings.intern("methods");
	public static final AString ADAPTER = Strings.intern("adapter");
	public static final AString IMPLEMENTATION = Strings.intern("implementation");
	public static final String INVALID_CONFIG = "Invalid auth.methods binding: ";
	public static final String UNAVAILABLE = "Authentication implementation is unavailable: ";
	public static final String AMBIGUOUS = "Authentication implementation selector is required: ";
	public static final String INVALID_DECLARATION = "Invalid authentication implementation declaration: ";
	public static final String NOT_SELECTED = "Authentication method is not selected: ";
	public static final String CHANGED = "Authentication configuration changed during verification";
	public static final String INVALID_RESULT = "Authentication verifier returned no result or a different method";
	public static final String LEGACY_RESTRICTED = "Legacy venue credential has unknown originating method and cannot satisfy explicit auth.methods";

	private record Binding(String adapter, AString implementation) {}
	private final Engine engine;
	private final boolean explicit;
	private final Map<AString, Binding> bindings;

	public AuthMethods(Engine engine) {
		this.engine = engine;
		Map<AString, Binding> configured = parse(engine.config().getAuthConfig());
		explicit = configured != null;
		bindings = explicit ? configured : Map.of(
			VenueAuthenticator.KEY, new Binding("auth", VenueAuthenticator.KEY_JWT),
			VenueAuthenticator.UCAN_METHOD, new Binding("auth", VenueAuthenticator.UCAN_BEARER),
			VenueAuthenticator.OIDC, new Binding("auth", VenueAuthenticator.PROVIDER_TOKEN));
	}

	/** Shape validation during Config construction, before any adapter is installed. */
	public static void validateConfig(AMap<AString, ACell> auth) { parse(auth); }

	private static Map<AString, Binding> parse(AMap<AString, ACell> auth) {
		if (auth == null || !auth.containsKey(METHODS)) return null;
		if (!(auth.get(METHODS) instanceof AMap<?, ?> methods)) throw invalid(METHODS);
		Map<AString, Binding> bindings = new LinkedHashMap<>();
		for (var entry : methods.entrySet()) {
			if (!(entry.getKey() instanceof AString id) || id.toString().isBlank()
					|| !(entry.getValue() instanceof AMap<?, ?> spec)) throw invalid(entry.getKey());
			for (ACell key : spec.keySet()) {
				if (!ADAPTER.equals(key) && !IMPLEMENTATION.equals(key)) throw invalid(key);
			}
			if (!(spec.get(ADAPTER) instanceof AString adapter) || adapter.toString().isBlank()) throw invalid(id);
			AString implementation = null;
			if (spec.containsKey(IMPLEMENTATION)) {
				if (!(spec.get(IMPLEMENTATION) instanceof AString name) || name.toString().isBlank()) throw invalid(id);
				implementation = name;
			}
			bindings.put(id, new Binding(adapter.toString(), implementation));
		}
		return Map.copyOf(bindings);
	}

	private static IllegalArgumentException invalid(Object field) {
		return new IllegalArgumentException(INVALID_CONFIG + field);
	}

	/** Validate all explicit references after assembly and before serving requests. */
	public void validate() {
		synchronized (engine) {
			if (explicit) for (Binding binding : bindings.values()) resolve(binding, engine.getAdapter(binding.adapter));
		}
	}

	/** Validate a candidate's declarations and references before adapter publication. */
	public void validateAdapter(AAdapter adapter) {
		if (adapter instanceof AuthenticationMethods) declarations(adapter);
		// Assembly hooks may replace defaults under the same adapter name.
		// Resolve all references after assembly; live replacements validate now.
		if (explicit && engine.isStarted()) for (Binding binding : bindings.values()) {
			if (binding.adapter.equals(adapter.getName())) resolve(binding, adapter);
		}
	}

	private static Map<AString, AuthenticationMethods.Verifier> declarations(AAdapter adapter) {
		if (!(adapter instanceof AuthenticationMethods provider)) {
			throw new IllegalArgumentException(UNAVAILABLE + (adapter == null ? "adapter" : adapter.getName()));
		}
		Map<AString, AuthenticationMethods.Verifier> methods = provider.authenticationMethods();
		if (methods == null) throw new IllegalArgumentException(INVALID_DECLARATION + adapter.getName());
		for (var entry : methods.entrySet()) {
			if (entry.getKey() == null || entry.getKey().toString().isBlank() || entry.getValue() == null) {
				throw new IllegalArgumentException(INVALID_DECLARATION + adapter.getName());
			}
		}
		return methods;
	}

	private static AuthenticationMethods.Verifier resolve(Binding binding, AAdapter adapter) {
		if (adapter == null) throw new IllegalArgumentException(UNAVAILABLE + binding.adapter);
		var methods = declarations(adapter);
		if (binding.implementation == null) {
			if (methods.size() != 1) throw new IllegalArgumentException(AMBIGUOUS + binding.adapter);
			return methods.values().iterator().next();
		}
		var verifier = methods.get(binding.implementation);
		if (verifier == null) throw new IllegalArgumentException(UNAVAILABLE + binding.adapter + "/" + binding.implementation);
		return verifier;
	}

	/** Direct method invocation; the supplied data never enters job persistence. */
	public AuthResult verify(AString method, ACell input) {
		if (method == null) throw new AuthException(NOT_SELECTED + method);
		AuthenticationMethods.Verifier verifier;
		long revision;
		synchronized (engine) {
			Binding binding = bindings.get(method);
			if (binding == null) throw new AuthException(NOT_SELECTED + method);
			try { verifier = resolve(binding, engine.getAdapter(binding.adapter)); }
			catch (IllegalArgumentException e) { throw new AuthException(e.getMessage()); }
			revision = engine.adapterRegistryVersion();
		}
		return finish(method, input, verifier, revision);
	}

	/** Compatibility credentials with unknown origin are never a selectable method. */
	AuthResult verifyLegacy(ACell input) {
		AuthenticationMethods.Verifier verifier;
		long revision;
		synchronized (engine) {
			if (explicit) throw new AuthException(LEGACY_RESTRICTED);
			if (!(engine.getAdapter("auth") instanceof AuthAdapter adapter)) throw new AuthException(UNAVAILABLE + "auth");
			verifier = (method, value) -> adapter.verifyLegacyCredential(value);
			revision = engine.adapterRegistryVersion();
		}
		return finish(null, input, verifier, revision);
	}

	private AuthResult finish(AString method, ACell input,
			AuthenticationMethods.Verifier verifier, long revision) {
		AuthResult result = verifier.verify(method, input);
		synchronized (engine) {
			// Never hold the lifecycle monitor over verifier I/O. The revision
			// also detects disable/re-enable of the same instance (an ABA change).
			if (revision != engine.adapterRegistryVersion()) throw new AuthException(CHANGED);
			if (result == null || !java.util.Objects.equals(method, result.authentication().method())) {
				throw new AuthException(INVALID_RESULT);
			}
			return result;
		}
	}
}
