package covia.adapter.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.adapter.AAdapter;
import covia.exception.ConfigurationException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.exception.UnsupportedException;
import covia.python.PythonException;
import covia.python.PythonRuntime;
import covia.python.PythonScript;
import covia.venue.RequestContext;

/**
 * Venue boundary for operator-configured Python operations. Script paths and
 * callable names come only from venue configuration; caller-supplied source
 * and host paths are never accepted. Each script is loaded once when the
 * adapter starts and keeps its globals between calls. An operator offers a
 * function to others through a callable (CALLABLES.md).
 *
 * <p>The code runs in the venue process with the venue's power, so running it
 * is the operator's: {@link #ADMIN_EXECUTE_PYTHON} on the venue resource that
 * names it, which the operator may grant (CONFIG.md, Python operations).</p>
 */
public final class PythonAdapter extends AAdapter implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(PythonAdapter.class);
	/** Running the operator's Python code, below {@link covia.api.Abilities#ADMIN_EXECUTE}. */
	public static final AString ADMIN_EXECUTE_PYTHON = Strings.intern("admin/execute/python");
	/** The venue resource of a configured function, under the venue's DID; its id follows. */
	public static final String OPERATION_RESOURCE = "python/operations/";
	/** Why the module refuses the {@code instances} setting it once had. */
	public static final String INSTANCES_REMOVED = "Malformed configuration at adapters.python.instances: Python "
		+ "instances are no longer offered. Configure each function under adapters.python.operations, and offer it to other "
		+ "principals through a callable (CONFIG.md, Python operations)";
	private static final AString ENABLED = Strings.intern("enabled");
	private static final AString LIBRARY = Strings.intern("library");
	private static final AString OPERATIONS = Strings.intern("operations");
	private static final AString INSTANCES = Strings.intern("instances");
	private static final AString SCRIPT = Strings.intern("script");
	private static final AString FUNCTION = Strings.intern("function");
	/** Refusal for a configured operation called before the adapter has started and loaded its script. */
	public static final String NOT_LOADED = "Python operations are loaded when the python adapter starts";
	/** A malformed setting: where in {@code adapters}, then what is wrong with it. */
	public static final String MALFORMED = "Malformed configuration at adapters.%s: %s";
	/** A setting this adapter does not know, refused under strictConfig: where, then the key. */
	public static final String UNKNOWN_SETTING = "Unknown configuration field adapters.%s.%s";
	/** A script a setting names that is not a readable file: the setting, then the path. */
	public static final String SCRIPT_NOT_FOUND = "adapters.python.%s names no readable file: %s";
	/** A changed configuration offered once the adapter is installed. */
	public static final String SETTINGS_FIXED = "adapters.python cannot change once the adapter is installed, "
		+ "since its operations are published from it: change it in the venue config and restart the venue";

	private record Definition(String id, Path script, String function,
		String name, String description, ACell input, ACell output) {}
	private record Operation(Definition definition, PythonScript script) {}

	/** The settings accepted, without the framework's {@code enabled}; null until configured. */
	private AMap<AString, ACell> applied;
	private String library;
	private PythonRuntime runtime;
	private Map<String, Definition> definitions = Map.of();
	private final Map<String, Operation> operations = new ConcurrentHashMap<>();
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private boolean closed;

	public PythonAdapter() {}

	/**
	 * Takes {@code adapters.python}: the CPython {@code library} and the
	 * {@code operations} it publishes. Every script a setting names must be a
	 * readable file; the {@code instances} it once allowed are refused under
	 * strictConfig and warned of otherwise ({@link #INSTANCES_REMOVED}). Its operations are
	 * published from these settings as it installs, so once installed it
	 * refuses any change to them until the venue restarts.
	 */
	@Override
	public void configure(AMap<AString, ACell> config, boolean strict) {
		AMap<AString, ACell> settings = config.dissoc(ENABLED);
		if (engine != null) {
			if (!settings.equals(applied)) throw new ConfigurationException(SETTINGS_FIXED);
			return;
		}
		if (settings.containsKey(INSTANCES)) {
			if (strict) throw new ConfigurationException(INSTANCES_REMOVED);
			log.warn(INSTANCES_REMOVED);
		}
		validateUnknownFields(settings, Set.of("library", "operations", "instances"), "python", strict);
		String parsedLibrary = optionalString(settings, LIBRARY, "python.library");
		Map<String, Definition> parsed = parseDefinitions(settings, strict);
		for (Definition definition : parsed.values()) {
			requireReadable(definition.script(), "operations." + definition.id() + ".script");
		}
		library = parsedLibrary;
		definitions = parsed;
		applied = settings;
	}

	private static void requireReadable(Path script, String setting) {
		if (!Files.isRegularFile(script) || !Files.isReadable(script)) {
			throw new ConfigurationException(SCRIPT_NOT_FOUND.formatted(setting, script));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Definition> parseDefinitions(
			AMap<AString, ACell> settings, boolean strict) {
		Map<String, Definition> result = new LinkedHashMap<>();
		ACell raw = settings.get(OPERATIONS);
		if (raw == null) return result;
		if (!(raw instanceof AMap<?, ?>)) throw malformed("python.operations", "must be an object");
		AMap<AString, ACell> configured = (AMap<AString, ACell>) raw;
		for (long i = 0; i < configured.count(); i++) {
			Map.Entry<AString, ACell> entry = configured.entryAt(i);
			String id = entry.getKey().toString();
			if (!id.matches("^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*$")) {
				throw malformed("python.operations." + id,
					"name must be a lowercase catalog path");
			}
			if (!(entry.getValue() instanceof AMap<?, ?>)) {
				throw malformed("python.operations." + id, "must be an object");
			}
			AMap<AString, ACell> definition = (AMap<AString, ACell>) entry.getValue();
			String path = "python.operations." + id;
			validateUnknownFields(definition,
				Set.of("script", "function", "name", "description", "input", "output"),
				path, strict);
			String script = optionalString(definition, SCRIPT, path + ".script");
			if (script == null) throw malformed(path + ".script", "is required");
			String function = optionalString(definition, FUNCTION, path + ".function");
			String name = optionalString(definition, Fields.NAME, path + ".name");
			String description = optionalString(definition, Fields.DESCRIPTION,
				path + ".description");
			ACell input = optionalMap(definition, Fields.INPUT, path + ".input");
			ACell output = optionalMap(definition, Fields.OUTPUT, path + ".output");
			result.put(id, new Definition(id, Path.of(script).toAbsolutePath().normalize(),
				function == null ? "main" : function,
				name == null ? "Python " + id : name,
				description == null ? "Runs the configured Python operation " + id : description,
				input, output));
		}
		return result;
	}

	private static void validateUnknownFields(AMap<AString, ACell> map,
			Set<String> known, String path, boolean strict) {
		for (AString key : map.keySet()) {
			if (known.contains(key.toString())) continue;
			String message = UNKNOWN_SETTING.formatted(path, key);
			if (strict) throw new ConfigurationException(message);
			log.warn(message);
		}
	}

	private static String optionalString(AMap<AString, ACell> map,
			AString key, String path) {
		ACell value = map.get(key);
		if (value == null) return null;
		if (!(value instanceof AString string)) throw malformed(path, "must be a string");
		return string.toString();
	}

	private static AMap<?, ?> optionalMap(AMap<AString, ACell> map,
			AString key, String path) {
		ACell value = map.get(key);
		if (value == null) return null;
		if (!(value instanceof AMap<?, ?> nested)) throw malformed(path, "must be an object");
		return nested;
	}

	private static ConfigurationException malformed(String path, String detail) {
		return new ConfigurationException(MALFORMED.formatted(path, detail));
	}

	@Override
	public String getName() {
		return "python";
	}

	@Override
	public String getDescription() {
		return "Runs operator-configured CPython functions in-process through Java FFM; "
			+ "Convex maps, vectors, scalars and blobs are converted natively.";
	}

	/**
	 * Declares the configured operations. Nothing is read or run: an installed
	 * adapter that is not started runs no operator code ({@link #start}).
	 */
	@Override
	protected void installAssets() {
		for (Definition definition : definitions.values()) {
			installAsset("python/" + definition.id(), metadata(definition));
		}
	}

	/** Whether the configured scripts are loaded ({@link #start}); written under {@link #lifecycle}. */
	private volatile boolean loaded;

	/**
	 * Opens CPython, then loads the configured scripts into it, which runs each
	 * one's top-level code. Operator code runs only once the adapter is
	 * started, never while it is just installed. The scripts load once; a later
	 * start keeps them. If one cannot load, none is kept and the start fails
	 * with the reason.
	 *
	 * @throws UnavailableException if this host has no usable CPython, such as
	 *         when the configured {@code library} cannot load
	 * @throws ConfigurationException if a script is missing, or cannot be read
	 *         or loaded
	 */
	@Override
	public void start() {
		lifecycle.writeLock().lock();
		try {
			if (closed) throw new UnavailableException("Python adapter is closed");
			if (loaded) return;
			if (runtime == null) {
				try {
					runtime = PythonRuntime.open(library);
				} catch (PythonException e) {
					throw new UnavailableException(e.getMessage(), e);
				}
			}
			Map<String, Operation> loading = new LinkedHashMap<>();
			try {
				for (Definition definition : definitions.values()) {
					if (!Files.isRegularFile(definition.script())) {
						throw new ConfigurationException(
							"Python script not found: " + definition.script());
					}
					String source = Files.readString(definition.script());
					PythonScript script = runtime.load(source, definition.script().toString());
					loading.put(definition.id(), new Operation(definition, script));
				}
				operations.putAll(loading);
				loaded = true;
			} catch (IOException | PythonException | ConfigurationException | IllegalArgumentException e) {
				release(loading.values());
				throw new ConfigurationException("Failed to load Python operations", e);
			} catch (RuntimeException e) {
				release(loading.values());
				throw e;
			}
		} finally {
			lifecycle.writeLock().unlock();
		}
		log.info("Python runtime enabled: {} ({} configured operations)",
			runtime.description(), operations.size());
	}

	/** Releases loaded operations' scripts, logging any that cannot be. */
	private static void release(Iterable<Operation> loaded) {
		for (Operation operation : loaded) {
			try {
				operation.script().close();
			} catch (RuntimeException e) {
				log.warn("Failed to release Python operation {}", operation.definition().id(), e);
			}
		}
	}

	private static AMap<AString, ACell> metadata(Definition definition) {
		ACell input = definition.input() == null
			? Maps.of(Strings.intern("type"), Strings.create("object")) : definition.input();
		ACell output = definition.output() == null ? Maps.empty() : definition.output();
		AMap<AString, ACell> operation = Maps.of(
			Fields.ADAPTER, Strings.create("python:" + definition.id()),
			Fields.TOOL_NAME, Strings.create("python_" + definition.id().replace('/', '_')),
			Fields.INPUT, input,
			Fields.OUTPUT, output);
		return Maps.of(
			Fields.NAME, Strings.create(definition.name()),
			Fields.DESCRIPTION, Strings.create(definition.description()),
			Fields.OPERATION, operation);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		String id = getSubOperation(meta);
		Operation operation = operations.get(id);
		if (!loaded && definitions.containsKey(id)) {
			return CompletableFuture.failedFuture(new UnavailableException(NOT_LOADED));
		}
		if (operation == null) {
			return CompletableFuture.failedFuture(
				new UnsupportedException("Unknown configured Python operation: " + id));
		}
		return CompletableFuture.supplyAsync(() -> {
			lifecycle.readLock().lock();
			try {
				if (closed) throw new UnavailableException("Python adapter is closed");
				requireExecute(ctx, OPERATION_RESOURCE + id);
				try {
					return operation.script().call(operation.definition().function(), input);
				} catch (PythonException e) {
					throw pythonFailure(e);
				}
			} finally {
				lifecycle.readLock().unlock();
			}
		}, VIRTUAL_EXECUTOR);
	}

	/**
	 * Requires that the request may run the operator's Python code at
	 * {@code resource}, a resource of the venue: the venue and its unrestricted
	 * agents may, and anyone the operator granted {@link #ADMIN_EXECUTE_PYTHON}
	 * there.
	 */
	private void requireExecute(RequestContext ctx, String resource) {
		engine.requireVenueAuthority(ctx, resource, ADMIN_EXECUTE_PYTHON);
	}

	/** An error raised by the operator's Python code, which the caller is told in Python's words. */
	private static ResponseException pythonFailure(PythonException e) {
		return new ResponseException(e.getMessage(), e);
	}

	@Override
	public void close() {
		lifecycle.writeLock().lock();
		try {
			if (closed) return;
			closed = true;
			release(operations.values());
			operations.clear();
		} finally {
			lifecycle.writeLock().unlock();
		}
	}
}
