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
import covia.python.PythonException;
import covia.python.PythonRuntime;
import covia.python.PythonScript;
import covia.venue.RequestContext;

/**
 * Venue boundary for operator-configured Python operations. Script paths and
 * callable names come only from venue configuration, never caller-supplied
 * metadata, so ordinary operation invocation cannot become arbitrary host code
 * execution.
 */
public final class PythonAdapter extends AAdapter implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(PythonAdapter.class);
	private static final AString ENABLED = Strings.intern("enabled");
	private static final AString LIBRARY = Strings.intern("library");
	private static final AString OPERATIONS = Strings.intern("operations");
	private static final AString SCRIPT = Strings.intern("script");
	private static final AString FUNCTION = Strings.intern("function");

	private record Definition(String id, Path script, String function,
		String name, String description, ACell input, ACell output) {}
	private record Operation(Definition definition, PythonScript script) {}

	private PythonRuntime runtime;
	private Map<String, Definition> definitions = Map.of();
	private final Map<String, Operation> operations = new ConcurrentHashMap<>();
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private boolean closed;

	public PythonAdapter() {}

	@Override
	public boolean configureModule(AMap<AString, ACell> settings, boolean strict) {
		validateUnknownFields(settings, Set.of("enabled", "library", "operations"),
			"python module config", strict);
		boolean enabled = optionalBoolean(settings, ENABLED, "python.enabled", true);
		String library = optionalString(settings, LIBRARY, "python.library");
		Map<String, Definition> parsed = parseDefinitions(settings, strict);
		if (!enabled) return false;
		try {
			runtime = PythonRuntime.open(library);
			definitions = parsed;
			return true;
		} catch (PythonException e) {
			log.warn("Python adapter disabled: {}", e.getMessage());
			return false;
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
			String message = "Unknown configuration field " + path + "." + key;
			if (strict) throw new IllegalArgumentException(message);
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

	private static boolean optionalBoolean(AMap<AString, ACell> map,
			AString key, String path, boolean fallback) {
		ACell value = map.get(key);
		if (value == null) return fallback;
		Object jvm = RT.jvm(value);
		if (!(jvm instanceof Boolean bool)) throw malformed(path, "must be a boolean");
		return bool;
	}

	private static AMap<?, ?> optionalMap(AMap<AString, ACell> map,
			AString key, String path) {
		ACell value = map.get(key);
		if (value == null) return null;
		if (!(value instanceof AMap<?, ?> nested)) throw malformed(path, "must be an object");
		return nested;
	}

	private static IllegalArgumentException malformed(String path, String detail) {
		return new IllegalArgumentException("Malformed configuration at " + path + ": " + detail);
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

	@Override
	protected void installAssets() {
		try {
			if (runtime == null) throw new IllegalStateException("Python module was not configured");
			for (Definition definition : definitions.values()) {
				if (!Files.isRegularFile(definition.script())) {
					throw new IllegalStateException(
						"Python script not found: " + definition.script());
				}
				String source = Files.readString(definition.script());
				PythonScript script = runtime.load(source, definition.script().toString());
				operations.put(definition.id(), new Operation(definition, script));
				installAsset("python/" + definition.id(), metadata(definition));
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw new IllegalStateException("Failed to install Python operations", e);
		}
		log.info("Python runtime enabled: {} ({} configured operations)",
			runtime.description(), operations.size());
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
		requireInvoke(ctx);
		String id = getSubOperation(meta);
		Operation operation = operations.get(id);
		if (operation == null) return CompletableFuture.failedFuture(
			new IllegalArgumentException("Unknown configured Python operation: " + id));
		return CompletableFuture.supplyAsync(() -> {
			lifecycle.readLock().lock();
			try {
				if (closed) throw new IllegalStateException("Python adapter is closed");
				return operation.script().call(operation.definition().function(), input);
			} finally {
				lifecycle.readLock().unlock();
			}
		}, VIRTUAL_EXECUTOR);
	}

	@Override
	public void close() {
		lifecycle.writeLock().lock();
		try {
			if (closed) return;
			closed = true;
			for (Operation operation : operations.values()) {
				try {
					operation.script().close();
				} catch (RuntimeException e) {
					log.warn("Failed to release Python operation {}",
						operation.definition().id(), e);
				}
			}
			operations.clear();
		} finally {
			lifecycle.writeLock().unlock();
		}
	}
}
