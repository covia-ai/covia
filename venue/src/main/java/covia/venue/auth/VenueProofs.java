package covia.venue.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import covia.adapter.AAdapter;
import covia.adapter.AuthAdapter;
import covia.exception.ConfigurationException;
import covia.grid.Venue;
import covia.venue.Engine;

/**
 * The operator's venue identity proof mechanisms ({@code auth.venueProof}),
 * resolved against the live adapter set and tried in order: the first that
 * proves the DID wins. A listed mechanism whose adapter is disabled is
 * withdrawn, and one that is not listed is never consulted. A proof is
 * reused for as long as its mechanism allows; a failure is never reused.
 */
public final class VenueProofs {
	public static final AString VENUE_PROOF = Strings.intern("venueProof");
	public static final String INVALID_CONFIG = "Invalid auth.venueProof entry: ";
	public static final String INVALID_DECLARATION = "Invalid venue proof declaration: ";
	/** Reason for a listed mechanism whose adapter is absent or does not declare it. */
	public static final String UNAVAILABLE = "venue proof mechanism is unavailable";
	/** Reason when nothing is listed, so nothing can be proven. */
	public static final String NONE_LISTED = "no venue proof mechanism is listed (auth.venueProof is empty)";

	private record Binding(String adapter, AString implementation) {
		@Override public String toString() { return adapter + "/" + implementation; }
	}

	private record Proven(Binding binding, VenueProofMethods.Method method, String target, String did) {}

	private static final List<Binding> DEFAULT = List.of(
		new Binding("auth", AuthAdapter.ADDRESS_PROOF), new Binding("auth", AuthAdapter.CHALLENGE_PROOF));

	private final Engine engine;
	private final boolean explicit;
	private final List<Binding> bindings;
	private final LongSupplier clock;
	private final ConcurrentHashMap<Proven, Long> proven = new ConcurrentHashMap<>();

	public VenueProofs(Engine engine) {
		this(engine, () -> engine.currentTimestamp().longValue());
	}

	/** @param clock Milliseconds since the epoch, against which reuse periods run */
	VenueProofs(Engine engine, LongSupplier clock) {
		this.engine = engine;
		this.clock = clock;
		List<Binding> configured = parse(engine.config().getAuthConfig());
		explicit = configured != null;
		bindings = explicit ? configured : DEFAULT;
	}

	/** Shape validation during Config construction, before any adapter is installed. */
	public static void validateConfig(AMap<AString, ACell> auth) { parse(auth); }

	private static List<Binding> parse(AMap<AString, ACell> auth) {
		if (auth == null || !auth.containsKey(VENUE_PROOF)) return null;
		if (!(auth.get(VENUE_PROOF) instanceof AVector<?> list)) throw invalid(VENUE_PROOF);
		List<Binding> bindings = new ArrayList<>();
		for (long i = 0; i < list.count(); i++) {
			ACell entry = list.get(i);
			if (!(entry instanceof AMap<?, ?> spec) || spec.count() != 2
					|| !(spec.get(AuthMethods.ADAPTER) instanceof AString adapter) || adapter.toString().isBlank()
					|| !(spec.get(AuthMethods.IMPLEMENTATION) instanceof AString name) || name.toString().isBlank()) {
				throw invalid(entry);
			}
			bindings.add(new Binding(adapter.toString(), name));
		}
		return List.copyOf(bindings);
	}

	private static ConfigurationException invalid(Object field) {
		return new ConfigurationException(INVALID_CONFIG + field);
	}

	/** Validate explicit entries once all host adapters are installed, before serving requests. */
	public void validate() {
		if (!explicit) return;
		synchronized (engine) {
			for (Binding binding : bindings) {
				if (resolve(binding) == null) throw new ConfigurationException(UNAVAILABLE + ": " + binding);
			}
		}
	}

	/** Validate a candidate's declarations before adapter publication. */
	public static void validateAdapter(AAdapter adapter) {
		if (adapter instanceof VenueProofMethods) declarations(adapter);
	}

	private static Map<AString, VenueProofMethods.Method> declarations(AAdapter adapter) {
		Map<AString, VenueProofMethods.Method> methods = ((VenueProofMethods) adapter).venueProofMethods();
		if (methods == null) throw new ConfigurationException(INVALID_DECLARATION + adapter.getName());
		for (var entry : methods.entrySet()) {
			if (entry.getKey() == null || entry.getKey().toString().isBlank() || entry.getValue() == null) {
				throw new ConfigurationException(INVALID_DECLARATION + adapter.getName());
			}
		}
		return methods;
	}

	private VenueProofMethods.Method resolve(Binding binding) {
		AAdapter adapter = engine.getAdapter(binding.adapter);
		return adapter instanceof VenueProofMethods ? declarations(adapter).get(binding.implementation) : null;
	}

	/**
	 * Whether the venue reached at {@code target} holds {@code did}, by the
	 * listed mechanisms in order.
	 *
	 * @param target The hop's target: a venue URL or DID
	 * @param did The DID to prove
	 * @param connection Opens an anonymous connection to the target through
	 *        the hop's outbound guard
	 * @return null when a mechanism proves it, else every mechanism's reason
	 */
	public String prove(String target, String did, Supplier<Venue> connection) {
		if (bindings.isEmpty()) return NONE_LISTED;
		List<VenueProofMethods.Method> methods = new ArrayList<>(bindings.size());
		synchronized (engine) {
			// Resolve under the lifecycle monitor; never hold it over a mechanism's I/O
			for (Binding binding : bindings) methods.add(resolve(binding));
		}
		StringJoiner reasons = new StringJoiner("; ");
		for (int i = 0; i < bindings.size(); i++) {
			Binding binding = bindings.get(i);
			VenueProofMethods.Method method = methods.get(i);
			String reason = UNAVAILABLE;
			if (method != null) {
				Proven key = new Proven(binding, method, target, did);
				Long until = proven.get(key);
				if (until != null && clock.getAsLong() < until) return null;
				reason = method.prove(target, did, connection);
				if (reason == null) {
					remember(key, method.reuseSeconds());
					return null;
				}
			}
			reasons.add(binding + ": " + reason);
		}
		return reasons.toString();
	}

	private void remember(Proven key, long seconds) {
		if (seconds <= 0) return;
		long now = clock.getAsLong();
		proven.values().removeIf(until -> until <= now);
		proven.put(key, now + seconds * 1000);
	}
}
