package covia.adapter.claudecode;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;

/**
 * One project: a directory Claude Code may run in, who may run it there,
 * and the process options that apply.
 *
 * <p>Projects are declared by the operator under
 * {@code adapters.claudecode.projects.<name>}, or created at runtime with
 * {@code claudecode:create} by a caller holding venue authority — either way
 * they are venue-authorised: naming a directory the venue's own OS user may
 * execute code in is an operator decision, never a user's. A spec is pure
 * configuration; live processes are {@link ClaudeSession}s.</p>
 *
 * @param name        Project name (config key / registry key); {@code [A-Za-z0-9_-]+}
 * @param path        Absolute directory Claude Code runs in (its cwd)
 * @param userRef     DID whose namespace owns the project — that user and
 *                    their agents may run in it; others need a delegation
 *                    from them — or {@code "public"} for the venue's public
 *                    principal, or {@code "venue"} for the venue identity
 * @param description Human/agent-facing description, or null
 * @param options     Project-level process options (overlay the adapter defaults)
 * @param managed     Whether the project came from config or the runtime registry
 */
public record ProjectSpec(
		String name,
		Path path,
		String userRef,
		String description,
		RunOptions options,
		Managed managed) {

	public enum Managed { CONFIG, RUNTIME }

	static final AString K_PATH = Fields.PATH;
	static final AString K_USER = Strings.intern("user");
	static final AString K_DESCRIPTION = Fields.DESCRIPTION;
	static final AString K_MANAGED = Strings.intern("managed");
	static final AString K_OPTIONS = Strings.intern("options");

	/** The {@code user} value naming the venue's public principal. */
	static final String PUBLIC_USER = "public";
	/** The {@code user} value naming the venue identity itself (the default). */
	static final String VENUE_USER = "venue";

	private static final Set<AString> KNOWN_KEYS = Set.of(K_PATH, K_USER, K_DESCRIPTION, K_OPTIONS);

	/**
	 * The DID that owns the project. {@code "public"} and {@code "venue"}
	 * resolve against the engine at call time.
	 *
	 * @throws IllegalStateException when the spec names the public principal
	 *         but the venue has public access disabled
	 */
	public AString userDID(Engine engine) {
		if (VENUE_USER.equals(userRef)) return engine.getDIDString();
		if (!PUBLIC_USER.equals(userRef)) return Strings.create(userRef);
		if (!engine.config().isPublicAccess()) {
			throw new IllegalStateException("project '" + name + "' is owned by \"public\" but public access "
				+ "is disabled on this venue (auth.public.enabled) — name an explicit DID");
		}
		return Strings.create(engine.getDIDString() + ":public");
	}

	/**
	 * Parses and validates one project entry.
	 *
	 * @param name        Config/registry key
	 * @param cell        Entry value: an object, or a plain string taken as {@code path}
	 * @param strict      Reject unknown keys
	 * @param managed     Origin of the entry
	 * @param defaultUser {@code user} when the entry has none ({@code "venue"} for config)
	 * @throws IllegalArgumentException with an actionable message on any defect
	 */
	static ProjectSpec parse(String name, ACell cell, boolean strict, Managed managed, String defaultUser) {
		String where = (managed == Managed.CONFIG ? "adapters.claudecode.projects." : "project ") + name;
		if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException(where + ": project name must match [A-Za-z0-9_-]+");
		}
		AMap<AString, ACell> m;
		if (cell instanceof AString pathOnly) {
			m = Maps.of(K_PATH, pathOnly);
		} else {
			m = RT.castMap(cell);
			if (m == null) throw new IllegalArgumentException(where + " must be an object ({path, user?, description?, options?}) or a path string");
		}
		if (strict) {
			for (long i = 0; i < m.count(); i++) {
				ACell k = m.entryAt(i).getKey();
				if (!(k instanceof AString ks) || !KNOWN_KEYS.contains(ks)) {
					throw new IllegalArgumentException(where + ": unknown setting " + k
						+ " (known: path, user, description, options)");
				}
			}
		}
		String pathStr = optString(m, K_PATH, where);
		if (pathStr == null || pathStr.isBlank()) {
			throw new IllegalArgumentException(where + ".path is required: the directory Claude Code runs in");
		}
		Path path;
		try {
			path = Path.of(pathStr).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException(where + ".path is not a valid path: " + pathStr);
		}
		if (!Files.isDirectory(path)) {
			throw new IllegalArgumentException(where + ".path is not an existing directory: " + path);
		}
		String user = optString(m, K_USER, where);
		if (user == null || user.isBlank()) user = defaultUser;
		if (!PUBLIC_USER.equals(user) && !VENUE_USER.equals(user) && !user.startsWith("did:")) {
			throw new IllegalArgumentException(where + ".user must be a DID, \"public\" or \"venue\": " + user);
		}
		String description = optString(m, K_DESCRIPTION, where);
		RunOptions options = RunOptions.parse(m.get(K_OPTIONS), RunOptions.ALL_KEYS, where + ".options", strict);
		return new ProjectSpec(name, path, user, description, options, managed);
	}

	private static String optString(AMap<AString, ACell> m, AString key, String where) {
		ACell v = m.get(key);
		if (v == null) return null;
		if (!(v instanceof AString s)) throw new IllegalArgumentException(where + "." + key + " must be a string");
		return s.toString();
	}

	/** The registry record for a runtime project ({@code w/claudecode/projects/<name>} in the venue workspace). */
	AMap<AString, ACell> record() {
		AMap<AString, ACell> r = Maps.of(K_PATH, Strings.create(path.toString()), K_USER, Strings.create(userRef));
		if (description != null) r = r.assoc(K_DESCRIPTION, Strings.create(description));
		if (!options.isEmpty()) r = r.assoc(K_OPTIONS, options.values());
		return r;
	}

	/** The public description of the project ({@code claudecode:projects}). */
	AMap<AString, ACell> describe(Engine engine) {
		AMap<AString, ACell> r = Maps.of(
			Fields.NAME, Strings.create(name),
			K_PATH, Strings.create(path.toString()),
			K_USER, userDID(engine),
			K_MANAGED, Strings.create(managed.name().toLowerCase(java.util.Locale.ROOT)));
		if (description != null) r = r.assoc(K_DESCRIPTION, Strings.create(description));
		if (!options.isEmpty()) r = r.assoc(K_OPTIONS, options.publicView());
		return r;
	}

	@Override
	public String toString() {
		return "ProjectSpec[" + name + " @ " + path + " as " + userRef + "]";
	}
}
