package covia.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
import covia.python.PythonException;
import covia.python.PythonRuntime;
import covia.python.PythonScript;
import covia.venue.Config;
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

	private final PythonRuntime runtime;
	private final Map<String, Definition> definitions;
	private final Map<String, Operation> operations = new ConcurrentHashMap<>();
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private boolean closed;

	private PythonAdapter(PythonRuntime runtime, Map<String, Definition> definitions) {
		this.runtime = runtime;
		this.definitions = definitions;
	}

	/** Creates the adapter only when explicitly enabled and runtime-capable. */
	public static Optional<PythonAdapter> create(Config config) {
		AMap<AString, ACell> settings = config.getAdapterConfig("python");
		if (!Boolean.TRUE.equals(RT.jvm(settings.get(ENABLED)))) return Optional.empty();

		AString libraryCell = RT.ensureString(settings.get(LIBRARY));
		String library = libraryCell == null ? null : libraryCell.toString();
		try {
			PythonRuntime runtime = PythonRuntime.open(library);
			return Optional.of(new PythonAdapter(runtime, parseDefinitions(settings)));
		} catch (PythonException e) {
			log.warn("Python adapter disabled: {}", e.getMessage());
			return Optional.empty();
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Definition> parseDefinitions(AMap<AString, ACell> settings) {
		Map<String, Definition> result = new LinkedHashMap<>();
		ACell raw = settings.get(OPERATIONS);
		if (raw == null) return result;
		AMap<AString, ACell> configured = (AMap<AString, ACell>) raw;
		for (long i = 0; i < configured.count(); i++) {
			Map.Entry<AString, ACell> entry = configured.entryAt(i);
			String id = entry.getKey().toString();
			AMap<AString, ACell> definition = (AMap<AString, ACell>) entry.getValue();
			String script = RT.ensureString(definition.get(SCRIPT)).toString();
			AString functionCell = RT.ensureString(definition.get(FUNCTION));
			AString nameCell = RT.ensureString(definition.get(Fields.NAME));
			AString descriptionCell = RT.ensureString(definition.get(Fields.DESCRIPTION));
			result.put(id, new Definition(id, Path.of(script).toAbsolutePath().normalize(),
				functionCell == null ? "main" : functionCell.toString(),
				nameCell == null ? "Python " + id : nameCell.toString(),
				descriptionCell == null ? "Runs the configured Python operation " + id
					: descriptionCell.toString(),
				definition.get(Fields.INPUT), definition.get(Fields.OUTPUT)));
		}
		return result;
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
