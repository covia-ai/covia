package covia.lattice;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;
import convex.lattice.cursor.Cursors;
import covia.adapter.AAdapter;
import covia.adapter.CoviaAdapter;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.venue.RequestContext;

/** Routes x/adapterName through the active adapter's explicit, user-scoped view. */
public final class ExtensionNamespaceResolver implements NamespaceResolver {
	public static final String AUTHENTICATION_REQUIRED = "Extension paths require an authenticated user";
	public static final String INVALID_PATH = "Invalid extension path segment";
	public static final String READ_ONLY = "Extension path is unavailable or read-only";
	public static final String UNAVAILABLE = "Extension adapter is no longer active";

	@Override public Position resolve(RequestContext ctx, CoviaAdapter adapter, CoviaPath path) {
		return resolve(ctx, adapter, path, Capability.CRUD_READ);
	}

	@Override public Position resolve(RequestContext ctx, CoviaAdapter adapter, CoviaPath path, AString ability) {
		return resolveForUser(ctx, adapter, ctx == null ? null : ctx.getUserDID(), path, ability);
	}

	/** Original authority is checked against the addressed owner; hooks receive that user's context. */
	public static Position resolveForUser(RequestContext ctx, CoviaAdapter covia,
			AString owner, CoviaPath fullPath, AString ability) {
		if (ctx == null || ctx.getCallerDID() == null || owner == null) throw new AuthException(AUTHENTICATION_REQUIRED);
		for (int i = 0; i < fullPath.length(); i++) {
			String s = fullPath.get(i).toString();
			if (s.isEmpty() || s.contains("\\") || s.equals(".") || s.equals("..")
					|| s.chars().anyMatch(Character::isISOControl)) {
				throw new InvalidInputException(INVALID_PATH);
			}
		}
		AString resource = Strings.create(owner + fullPath.toString());
		covia.engine.requireLocalAccess(ctx, resource, ability);
		String name = fullPath.length() < 2 ? null : fullPath.get(1).toString();
		AAdapter adapter = name == null ? null : covia.engine.getAdapter(name);
		RequestContext scoped = owner.equals(ctx.getUserDID()) ? ctx : ctx.onBehalfOf(owner);
		// The adapter's write policy takes the path inside its view, without x/<adapter>
		CoviaPath path = fullPath.rest(Math.min(2, fullPath.length()));
		boolean mutation = Capability.CRUD_WRITE.equals(ability) || Capability.CRUD_DELETE.equals(ability);
		if (mutation) requireWritable(adapter, scoped, path);
		AForkableCursor<ACell> exposed = adapter == null ? null : adapter.latticeView(scoped);
		if (mutation && exposed == null) throw new AuthException(READ_ONLY);
		// An absent route is still resolved: never fall through to a physical user x slot.
		AForkableCursor<ACell> base = exposed == null ? Cursors.root(null) : exposed;
		// Authority was checked above. The view itself keeps only what holds for any
		// caller: its adapter is still the active one, and that adapter's write policy.
		AForkableCursor<ACell> guarded = new ATransformCursor<ACell, ACell>(base) {
			private void check() {
				if (name != null && covia.engine.getAdapter(name) != adapter) throw new AuthException(UNAVAILABLE);
				if (mutation) requireWritable(adapter, scoped, path);
			}
			@Override protected ACell view(ACell value) { check(); return value; }
			@Override protected ACell write(ACell current, ACell value) {
				if (!mutation) throw new AuthException(READ_ONLY);
				check();
				return value;
			}
		};
		return new Workspace(guarded).resolve(path);
	}

	private static void requireWritable(AAdapter adapter, RequestContext ctx, CoviaPath path) {
		String denial = adapter == null ? READ_ONLY : adapter.latticeWriteDenial(ctx, path);
		if (denial != null) throw new AuthException(denial);
	}

	/** Individual adapters decide which paths accept mutations during resolution. */
	@Override public boolean isWritable() { return true; }
}
