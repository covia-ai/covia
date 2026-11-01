package covia.adapter;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConfigurationException;
import covia.exception.InvalidInputException;
import covia.venue.AdapterState;
import covia.venue.RequestContext;

/**
 * Per-user preferences: one map of any structure for each user, the facts
 * every agent acting for them should know. Held in this adapter's state at
 * {@code users/<did>}, read at {@code x/preferences} (read-only) and edited
 * only through {@code v/ops/preferences}, whose commands each make one atomic
 * update. The contract is {@code venue/docs/PREFERENCES.md}.
 */
public class PreferencesAdapter extends AAdapter {

	/** Size limit of one user's preferences, in {@link Cells#storageSize} bytes, when none is configured. */
	public static final long DEFAULT_MAX_BYTES = 4096;

	/** Where the preferences appear to their user. */
	public static final String VIEW_PATH = "x/preferences";

	static final AString K_MAX_BYTES = Strings.intern("maxBytes");
	static final AString K_USERS     = Strings.intern("users");
	private static final AString K_COMMAND = Strings.intern("command");
	private static final AString K_OK      = Strings.intern("ok");
	private static final AString K_LATTICE = Strings.intern("lattice");

	private static final Set<AString> SETTINGS = Set.of(K_MAX_BYTES, K_USERS);
	private static final Set<AString> USER_SETTINGS = Set.of(K_MAX_BYTES);
	private static final Set<String> COMMANDS = Set.of("set", "remove", "append", "insert", "merge");

	public static final String AUTHENTICATION_REQUIRED = "Preferences belong to an authenticated user";
	public static final String UNKNOWN_COMMAND = "preferences needs a command: set, remove, append, insert or merge";
	public static final String PATH_REQUIRED = "%s needs a path; only merge applies to all preferences at once";
	public static final String VALUE_REQUIRED = "%s needs a value";
	public static final String BAD_PATH = "Path '%s' has an empty segment";
	public static final String BAD_KEY = "Keys must be non-empty text without '/', got '%s'";
	public static final String NO_NULL = "Values cannot hold null; remove an entry with remove";
	public static final String NOT_A_POSITION = "The list at '%s' takes a position counting from 0, not '%s'";
	public static final String OUT_OF_RANGE = "Position %d is out of range at '%s', which has %d items";
	public static final String NOT_A_LIST = "%s needs a list at '%s'";
	public static final String NOT_INSIDE = "Cannot go inside '%s': it holds a value, not a map or a list";
	public static final String MERGE_NEEDS_MAP = "merge needs a map value, and a map or nothing at '%s'; use set, append or insert for other values";
	public static final String TOO_LARGE = "Preferences would be %d bytes, over the limit of %d; remove or shorten something first";

	public static final String UNKNOWN_SETTING = "%s: unknown setting '%s'";
	public static final String BAD_LIMIT = "%s must be a positive integer, got: %s";
	public static final String BAD_USERS = "adapters.preferences.users must map each DID to its settings";

	@Override
	public String getName() {
		return "preferences";
	}

	@Override
	public String getDescription() {
		return "Per-user preferences: one map of any structure that every agent acting for the user should know, "
			+ "read at x/preferences and edited with one tool (set, remove, append, insert, merge).";
	}

	@Override
	protected void installAssets() {
		installAsset("preferences", "/adapters/preferences/preferences.json");
	}

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) return true;
		if (strict) rejectUnknown(config, SETTINGS, "adapters.preferences");
		checkLimit(config.get(K_MAX_BYTES), "adapters.preferences.maxBytes");
		ACell users = config.get(K_USERS);
		if (users == null) return true;
		if (!(users instanceof AMap<?, ?> byUser)) throw new ConfigurationException(BAD_USERS);
		for (long i = 0; i < byUser.count(); i++) {
			var entry = byUser.entryAt(i);
			if (!(entry.getKey() instanceof AString did) || !did.toString().startsWith("did:")
					|| !(entry.getValue() instanceof AMap<?, ?> settings)) {
				throw new ConfigurationException(BAD_USERS);
			}
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> own = (AMap<AString, ACell>) settings;
			String at = "adapters.preferences.users." + did;
			if (strict) rejectUnknown(own, USER_SETTINGS, at);
			checkLimit(own.get(K_MAX_BYTES), at + ".maxBytes");
		}
		return true;
	}

	private static void rejectUnknown(AMap<AString, ACell> config, Set<AString> known, String at) {
		for (long i = 0; i < config.count(); i++) {
			AString key = config.entryAt(i).getKey();
			if (!known.contains(key)) throw new ConfigurationException(UNKNOWN_SETTING.formatted(at, key));
		}
	}

	private static void checkLimit(ACell limit, String at) {
		if (limit == null) return;
		if (!(limit instanceof CVMLong l) || l.longValue() < 1) throw new ConfigurationException(BAD_LIMIT.formatted(at, limit));
	}

	/** The venue default is public; per-user limits name users, so they are not. */
	@Override
	public AMap<AString, ACell> publicConfig() {
		return publicConfig("maxBytes");
	}

	@Override
	public AMap<AString, ACell> info() {
		return Maps.of(K_LATTICE, Maps.of(Fields.PATH, VIEW_PATH));
	}

	/** The addressed user's preferences, read-only: edits go through the tool. */
	@Override
	public AForkableCursor<ACell> latticeView(RequestContext ctx) {
		return userCursor(ctx.getUserDID());
	}

	@Override
	public void deleteUserState(AString did, AdapterState state) {
		state.delete(state.userPath(did, ""));
	}

	private AForkableCursor<ACell> userCursor(AString did) {
		return adapterState(did).cursor().withDefault(Maps.empty())
			.path(K_USERS).withDefault(Maps.empty()).path(did);
	}

	/** The size limit for one user: their own setting, else the venue's, else {@link #DEFAULT_MAX_BYTES}. */
	long limitFor(AString did) {
		AMap<AString, ACell> config = engine.adapterConfig(getName());
		ACell own = RT.getIn(config, K_USERS, did, K_MAX_BYTES);
		ACell limit = (own != null) ? own : RT.getIn(config, K_MAX_BYTES);
		return (limit instanceof CVMLong l) ? l.longValue() : DEFAULT_MAX_BYTES;
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		try {
			return CompletableFuture.completedFuture(edit(ctx, input));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell edit(RequestContext ctx, ACell input) {
		AString user = ctx.getUserDID();
		if (ctx.getCallerDID() == null || user == null) throw new AuthException(AUTHENTICATION_REQUIRED);
		AString commandCell = RT.ensureString(RT.getIn(input, K_COMMAND));
		String command = (commandCell != null) ? commandCell.toString() : null;
		if (command == null || !COMMANDS.contains(command)) throw new InvalidInputException(UNKNOWN_COMMAND);

		AString pathCell = RT.ensureString(RT.getIn(input, Fields.PATH));
		String[] keys = parsePath((pathCell != null) ? pathCell.toString() : "");
		String path = String.join("/", keys);
		if (keys.length == 0 && !"merge".equals(command)) throw new InvalidInputException(PATH_REQUIRED.formatted(command));
		boolean hasValue = (input instanceof AMap<?, ?> m) && m.containsKey(Fields.VALUE);
		ACell value = RT.getIn(input, Fields.VALUE);
		if (!hasValue && !"remove".equals(command)) throw new InvalidInputException(VALUE_REQUIRED.formatted(command));
		if ("merge".equals(command)) {
			if (!(value instanceof AMap)) throw new InvalidInputException(MERGE_NEEDS_MAP.formatted(path));
			checkValue(value, true);
		} else if (value != null) {
			checkValue(value, false);
		}

		engine.requireLocalAccess(ctx,
			Strings.create(user + "/" + VIEW_PATH + (path.isEmpty() ? "" : "/" + path)), Capability.CRUD_WRITE);
		long limit = limitFor(user);
		AtomicBoolean changed = new AtomicBoolean();
		userCursor(user).updateAndGet(current -> {
			ACell root = (current != null) ? current : Maps.empty();
			ACell next = apply(command, root, keys, value, path);
			boolean differs = !Utils.equals(root, next);
			changed.set(differs);
			if (!differs) return current;
			long size = Cells.storageSize(next);
			if (size > limit && size > Cells.storageSize(current)) {
				throw new InvalidInputException(TOO_LARGE.formatted(size, limit));
			}
			return next;
		});
		return ("remove".equals(command) && !changed.get())
			? Maps.of(K_OK, CVMBool.TRUE, Fields.REMOVED, CVMBool.FALSE)
			: Maps.of(K_OK, CVMBool.TRUE);
	}

	/**
	 * The path's keys. A leading or trailing {@code /} and a leading
	 * {@code x/preferences}, the path the preferences are read at, are ignored,
	 * so a path copied from a read addresses the same place.
	 */
	static String[] parsePath(String path) {
		String p = path;
		if (p.startsWith("/")) p = p.substring(1);
		if (p.equals(VIEW_PATH)) p = "";
		else if (p.startsWith(VIEW_PATH + "/")) p = p.substring(VIEW_PATH.length() + 1);
		if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
		if (p.isEmpty()) return new String[0];
		String[] keys = p.split("/", -1);
		for (String key : keys) {
			if (key.isEmpty()) throw new InvalidInputException(BAD_PATH.formatted(path));
		}
		return keys;
	}

	/** Refuses nulls (except, in a merge, as map values that remove keys) and keys a path could not address. */
	private static void checkValue(ACell value, boolean merge) {
		if (value instanceof AMap<?, ?> map) {
			for (long i = 0; i < map.count(); i++) {
				var entry = map.entryAt(i);
				if (!(entry.getKey() instanceof AString key) || key.count() == 0 || key.toString().contains("/")) {
					throw new InvalidInputException(BAD_KEY.formatted(entry.getKey()));
				}
				ACell v = entry.getValue();
				if (v == null) {
					if (!merge) throw new InvalidInputException(NO_NULL);
				} else {
					checkValue(v, merge);
				}
			}
		} else if (value instanceof AVector<?> vec) {
			for (long i = 0; i < vec.count(); i++) {
				ACell v = vec.get(i);
				if (v == null) throw new InvalidInputException(NO_NULL);
				checkValue(v, false);
			}
		}
	}

	/** The preferences after {@code command}; pure, so the atomic update may run it again. */
	static ACell apply(String command, ACell root, String[] keys, ACell value, String path) {
		int last = keys.length - 1;
		return switch (command) {
			case "set" -> (value == null)
				? remove(root, keys, path)
				: at(root, keys, last, true, parent -> setIn(parent, keys, last, value));
			case "remove" -> (value == null)
				? remove(root, keys, path)
				: at(root, keys, keys.length, false, list -> withoutValue(list, value, path));
			case "append" -> at(root, keys, keys.length, true, list -> appendTo(list, value, path));
			case "insert" -> at(root, keys, last, true, parent -> insertIn(parent, keys, last, value));
			case "merge" -> at(root, keys, keys.length, true, target -> mergeInto(target, value, path));
			default -> throw new InvalidInputException(UNKNOWN_COMMAND);
		};
	}

	private static ACell remove(ACell root, String[] keys, String path) {
		int last = keys.length - 1;
		return at(root, keys, last, false, parent -> removeIn(parent, keys, last));
	}

	/**
	 * Replaces the node {@code depth} keys down with {@code change} applied to
	 * it. With {@code create}, a missing map on the way is created; without it,
	 * a missing node leaves everything unchanged.
	 */
	private static ACell at(ACell node, String[] keys, int depth, boolean create, UnaryOperator<ACell> change) {
		return at(node, keys, 0, depth, create, change);
	}

	@SuppressWarnings("unchecked")
	private static ACell at(ACell node, String[] keys, int i, int depth, boolean create, UnaryOperator<ACell> change) {
		if (i == depth) return change.apply(node);
		if (node instanceof AVector<?> vec) {
			long idx = position(vec, keys, i, false);
			ACell child = vec.get(idx);
			ACell next = at(child, keys, i + 1, depth, create, change);
			return Utils.equals(child, next) ? node : ((AVector<ACell>) vec).assoc(idx, next);
		}
		if (node == null) {
			if (!create) return null;
			node = Maps.empty();
		}
		if (!(node instanceof AMap)) throw new InvalidInputException(NOT_INSIDE.formatted(prefix(keys, i)));
		AMap<AString, ACell> map = (AMap<AString, ACell>) node;
		AString key = Strings.create(keys[i]);
		ACell child = map.get(key);
		ACell next = at(child, keys, i + 1, depth, create, change);
		if (Utils.equals(child, next)) return node;
		return (next == null) ? map.dissoc(key) : map.assoc(key, next);
	}

	@SuppressWarnings("unchecked")
	private static ACell setIn(ACell parent, String[] keys, int last, ACell value) {
		if (parent instanceof AVector<?> vec) {
			return ((AVector<ACell>) vec).assoc(position(vec, keys, last, false), value);
		}
		if (parent != null && !(parent instanceof AMap)) throw new InvalidInputException(NOT_INSIDE.formatted(prefix(keys, last)));
		AMap<AString, ACell> map = (parent != null) ? (AMap<AString, ACell>) parent : Maps.empty();
		return map.assoc(Strings.create(keys[last]), value);
	}

	@SuppressWarnings("unchecked")
	private static ACell removeIn(ACell parent, String[] keys, int last) {
		if (parent instanceof AVector<?> vec) {
			if (!isPosition(keys[last])) throw new InvalidInputException(NOT_A_POSITION.formatted(prefix(keys, last), keys[last]));
			long idx = Long.parseLong(keys[last]);
			if (idx >= vec.count()) return parent;
			AVector<ACell> out = Vectors.empty();
			for (long j = 0; j < vec.count(); j++) {
				if (j != idx) out = out.conj(vec.get(j));
			}
			return out;
		}
		if (parent instanceof AMap) return ((AMap<AString, ACell>) parent).dissoc(Strings.create(keys[last]));
		return parent;
	}

	@SuppressWarnings("unchecked")
	private static ACell withoutValue(ACell list, ACell value, String path) {
		if (list == null) return null;
		if (!(list instanceof AVector<?> vec)) throw new InvalidInputException(NOT_A_LIST.formatted("remove with a value", path));
		AVector<ACell> out = Vectors.empty();
		for (long j = 0; j < vec.count(); j++) {
			if (!Utils.equals(vec.get(j), value)) out = out.conj(vec.get(j));
		}
		return (out.count() == vec.count()) ? list : out;
	}

	@SuppressWarnings("unchecked")
	private static ACell appendTo(ACell list, ACell value, String path) {
		if (list == null) return Vectors.of(value);
		if (!(list instanceof AVector<?> vec)) throw new InvalidInputException(NOT_A_LIST.formatted("append", path));
		return ((AVector<ACell>) vec).conj(value);
	}

	@SuppressWarnings("unchecked")
	private static ACell insertIn(ACell parent, String[] keys, int last, ACell value) {
		if (!(parent instanceof AVector<?> vec)) throw new InvalidInputException(NOT_A_LIST.formatted("insert", prefix(keys, last)));
		long idx = position(vec, keys, last, true);
		AVector<ACell> out = Vectors.empty();
		for (long j = 0; j < vec.count(); j++) {
			if (j == idx) out = out.conj(value);
			out = out.conj(vec.get(j));
		}
		return (idx == vec.count()) ? out.conj(value) : out;
	}

	private static ACell mergeInto(ACell target, ACell value, String path) {
		if (target != null && !(target instanceof AMap)) throw new InvalidInputException(MERGE_NEEDS_MAP.formatted(path));
		return JSONAdapter.mergePatch(target, value);
	}

	/** The list position {@code keys[i]} names: within the list, or up to its end when {@code orEnd}. */
	private static long position(AVector<?> vec, String[] keys, int i, boolean orEnd) {
		String at = prefix(keys, i);
		if (!isPosition(keys[i])) throw new InvalidInputException(NOT_A_POSITION.formatted(at, keys[i]));
		long idx = Long.parseLong(keys[i]);
		long bound = orEnd ? vec.count() + 1 : vec.count();
		if (idx >= bound) throw new InvalidInputException(OUT_OF_RANGE.formatted(idx, at, vec.count()));
		return idx;
	}

	private static boolean isPosition(String key) {
		return key.length() <= 18 && key.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	private static String prefix(String[] keys, int i) {
		return String.join("/", java.util.Arrays.copyOf(keys, i));
	}
}
