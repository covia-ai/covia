package covia.venue;

import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.AUpdateCursor;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;

/**
 * Adapter-owned value beneath the running venue's :adapters region.
 * No user workspace, envelope or schema is imposed. Adapters may wrap this
 * component's cursor in their own domain components. Changes join the venue's
 * atomic root; external effects belong after commit. Snapshot reconciliation
 * remains a whole-venue recovery policy, not a merge of adapter operations.
 */
public final class AdapterState extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, AdapterState> {
	private static final Pattern ADAPTER_NAME = Pattern.compile("[a-z][a-z0-9-]*");
	private final LatticeContext context;
	private final String name;

	AdapterState(LatticeContext context, AForkableCursor<ACell> cursor, String name) {
		super(cursor);
		this.context = context;
		this.name = name;
	}

	@Override public AdapterState withCursor(AForkableCursor<ACell> draft) {
		return new AdapterState(context, draft, name);
	}

	static void validateName(String name) {
		if (name == null || !ADAPTER_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Adapter name must match [a-z][a-z0-9-]*: " + name);
		}
	}

	/** The account check encloses the adapter path, so it also runs at transaction commit. */
	static AdapterState bind(VenueState venue, String name, AString owner) {
		AForkableCursor<ACell> base = venue.cursor();
		if (owner != null) {
			ACell generation = RT.getIn(base.get(), Covia.USER_DATA, owner, Users.GENERATION);
			base = new AUpdateCursor<ACell, ACell>(base) {
				@Override protected ACell view(ACell value) {
					Users.requireCurrent(RT.getIn(value, Covia.USER_DATA, owner), generation);
					return value;
				}
				@Override protected ACell prepareWrite(ACell value) { return value; }
			};
			base.get(); // reject a tombstoned owner before handing out the view
		}
		return new AdapterState(venue.getContext(),
			base.path(Covia.ADAPTERS).withDefault(Maps.empty()).path(Strings.create(name)), name);
	}

	public LatticeContext getContext() { return context; }
	public String adapterName() { return name; }
	public ACell get() { return cursor().get(); }
	public void set(ACell value) { cursor().set(value); }
	public ACell update(UnaryOperator<ACell> action) { return cursor().updateAndGet(action); }
	public void requireCurrentUser() { cursor().get(); }

	/** Diagnostic location, not a caller-accessible workspace path. */
	public String rootPath() { return ":adapters/" + name; }
	public String path(String relative) {
		String r = validateRelative(relative, true);
		return r.isEmpty() ? rootPath() : rootPath() + "/" + r;
	}

	/** Optional naming convention for adapters that keep a map of user associations. */
	public String userPath(AString did, String relative) {
		if (did == null || !did.toString().startsWith("did:") || did.toString().contains("/")) {
			throw new IllegalArgumentException("Adapter state user must be a bare DID: " + did);
		}
		String suffix = validateRelative(relative, true);
		return "users/" + did + (suffix.isEmpty() ? "" : "/" + suffix);
	}

	/** Optional type-aware path access for map/vector-shaped adapter values. */
	public ACell read(String relative) {
		return WorkspaceRoot.deepGet(get(), keys(relative, true), 0);
	}
	public void write(String relative, ACell value) {
		Objects.requireNonNull(value, "Adapter state values must be non-null; use delete()");
		ACell[] path = keys(relative, false);
		update(current -> WorkspaceRoot.deepSet(current, path, 0, value));
	}
	public boolean delete(String relative) {
		ACell[] path = keys(relative, false);
		ACell previous = cursor().getAndUpdate(current -> WorkspaceRoot.deepDelete(current, path, 0));
		return WorkspaceRoot.leafPresentIn(previous, path);
	}

	private static ACell[] keys(String path, boolean allowEmpty) {
		return WorkspaceRoot.parseStringPath(validateRelative(path, allowEmpty));
	}
	private static String validateRelative(String relative, boolean allowEmpty) {
		if (relative == null) throw new IllegalArgumentException("Adapter state path is required");
		if (relative.isEmpty()) {
			if (allowEmpty) return relative;
			throw new IllegalArgumentException("Adapter state mutation path must not be empty");
		}
		if (relative.startsWith("/") || relative.endsWith("/") || relative.indexOf('\\') >= 0 || relative.length() > 2048) {
			throw new IllegalArgumentException("Invalid adapter state relative path: " + relative);
		}
		for (String segment : relative.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.chars().anyMatch(Character::isISOControl)) {
				throw new IllegalArgumentException("Invalid adapter state path segment in: " + relative);
			}
		}
		return relative;
	}
}
