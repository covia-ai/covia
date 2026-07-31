package covia.adapter.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.adapter.AAdapter;
import covia.exception.AuthException;
import covia.python.PythonException;
import covia.python.PythonRuntime;
import covia.python.PythonScript;
import covia.venue.RequestContext;

/**
 * Venue boundary for operator-configured Python operations. Script paths and
 * callable names come only from venue configuration. When explicitly enabled,
 * callers may also create stateful instances from operator-approved templates
 * and invoke only their allowlisted functions. Caller-supplied source and host
 * paths are never accepted.
 */
public final class PythonAdapter extends AAdapter implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(PythonAdapter.class);
	private static final AString ENABLED = Strings.intern("enabled");
	private static final AString LIBRARY = Strings.intern("library");
	private static final AString OPERATIONS = Strings.intern("operations");
	private static final AString SCRIPT = Strings.intern("script");
	private static final AString FUNCTION = Strings.intern("function");
	private static final AString INSTANCES = Strings.intern("instances");
	private static final AString TEMPLATES = Strings.intern("templates");
	private static final AString FUNCTIONS = Strings.intern("functions");
	private static final AString MAX_PER_USER = Strings.intern("maxPerUser");
	private static final AString MAX_TOTAL = Strings.intern("maxTotal");
	private static final AString TEMPLATE = Strings.intern("template");
	private static final AString ID = Strings.intern("id");
	private static final AString ARGS = Strings.intern("args");
	private static final AString CREATED_AT = Strings.intern("createdAt");
	private static final AString CREATED_BY = Strings.intern("createdBy");
	private static final String INSTANCE_CREATE = "instances/create";
	private static final String INSTANCE_LIST = "instances/list";
	private static final String INSTANCE_CALL = "instances/call";
	private static final String INSTANCE_CLOSE = "instances/close";
	private static final Set<String> INSTANCE_OPERATIONS = Set.of(
		INSTANCE_CREATE, INSTANCE_LIST, INSTANCE_CALL, INSTANCE_CLOSE);

	private record Definition(String id, Path script, String function,
		String name, String description, ACell input, ACell output) {}
	private record Operation(Definition definition, PythonScript script) {}
	private record TemplateDefinition(String id, Path script, Set<String> functions) {}
	private record PreparedTemplate(TemplateDefinition definition, String source) {}
	private record InstanceConfig(boolean enabled, int maxPerUser, int maxTotal,
		Map<String, TemplateDefinition> templates) {
		private static InstanceConfig disabled() {
			return new InstanceConfig(false, 0, 0, Map.of());
		}
	}
	private record InstanceKey(AString owner, String id) {}

	private static final class Instance implements AutoCloseable {
		private final String id;
		private final AString createdBy;
		private final String template;
		private final long createdAt;
		private final Set<String> functions;
		private final PythonScript script;
		private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
		private boolean closed;

		private Instance(String id, AString createdBy,
				PreparedTemplate template, PythonScript script) {
			this.id = id;
			this.createdBy = createdBy;
			this.template = template.definition().id();
			this.createdAt = System.currentTimeMillis();
			this.functions = template.definition().functions();
			this.script = script;
		}

		private ACell call(String function, List<? extends ACell> arguments) {
			if (!functions.contains(function)) {
				throw new IllegalArgumentException("Function is not allowed by Python template "
					+ template + ": " + function);
			}
			lifecycle.readLock().lock();
			try {
				if (closed) throw new IllegalStateException("Python instance is closed: " + id);
				return script.call(function, arguments);
			} finally {
				lifecycle.readLock().unlock();
			}
		}

		private AMap<AString, ACell> descriptor() {
			List<ACell> callableNames = functions.stream().sorted()
				.map(Strings::create).map(ACell.class::cast).toList();
			return Maps.of(
				ID, Strings.create(id),
				TEMPLATE, Strings.create(template),
				FUNCTIONS, Vectors.create(callableNames),
				CREATED_AT, CVMLong.create(createdAt),
				CREATED_BY, createdBy);
		}

		@Override
		public void close() {
			lifecycle.writeLock().lock();
			try {
				if (closed) return;
				closed = true;
				script.close();
			} finally {
				lifecycle.writeLock().unlock();
			}
		}
	}

	private PythonRuntime runtime;
	private Map<String, Definition> definitions = Map.of();
	private InstanceConfig instanceConfig = InstanceConfig.disabled();
	private Map<String, PreparedTemplate> templates = Map.of();
	private final Map<String, Operation> operations = new ConcurrentHashMap<>();
	private final Map<InstanceKey, Instance> instances = new ConcurrentHashMap<>();
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private boolean closed;

	public PythonAdapter() {}

	@Override
	public boolean configureModule(AMap<AString, ACell> settings, boolean strict) {
		validateUnknownFields(settings, Set.of("enabled", "library", "operations", "instances"),
			"python module config", strict);
		boolean enabled = optionalBoolean(settings, ENABLED, "python.enabled", true);
		String library = optionalString(settings, LIBRARY, "python.library");
		Map<String, Definition> parsed = parseDefinitions(settings, strict);
		InstanceConfig parsedInstances = parseInstanceConfig(settings, strict);
		if (parsedInstances.enabled()) {
			for (String id : parsed.keySet()) {
				if (INSTANCE_OPERATIONS.contains(id)) {
					throw malformed("python.operations." + id,
						"is reserved when Python instances are enabled");
				}
			}
		}
		if (!enabled) return false;
		try {
			runtime = PythonRuntime.open(library);
			definitions = parsed;
			instanceConfig = parsedInstances;
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

	@SuppressWarnings("unchecked")
	private static InstanceConfig parseInstanceConfig(
			AMap<AString, ACell> settings, boolean strict) {
		ACell raw = settings.get(INSTANCES);
		if (raw == null) return InstanceConfig.disabled();
		if (!(raw instanceof AMap<?, ?>)) throw malformed("python.instances", "must be an object");
		AMap<AString, ACell> configured = (AMap<AString, ACell>) raw;
		validateUnknownFields(configured,
			Set.of("enabled", "templates", "maxPerUser", "maxTotal"),
			"python.instances", strict);
		boolean enabled = optionalBoolean(configured, ENABLED,
			"python.instances.enabled", true);
		int maxPerUser = optionalPositiveInt(configured, MAX_PER_USER,
			"python.instances.maxPerUser", 8);
		int maxTotal = optionalPositiveInt(configured, MAX_TOTAL,
			"python.instances.maxTotal", 128);
		if (maxTotal < maxPerUser) {
			throw malformed("python.instances.maxTotal", "must be at least maxPerUser");
		}

		Map<String, TemplateDefinition> templates = new LinkedHashMap<>();
		ACell rawTemplates = configured.get(TEMPLATES);
		if (rawTemplates != null) {
			if (!(rawTemplates instanceof AMap<?, ?>)) {
				throw malformed("python.instances.templates", "must be an object");
			}
			AMap<AString, ACell> templateMap = (AMap<AString, ACell>) rawTemplates;
			for (long i = 0; i < templateMap.count(); i++) {
				Map.Entry<AString, ACell> entry = templateMap.entryAt(i);
				String id = entry.getKey().toString();
				String path = "python.instances.templates." + id;
				if (!id.matches("^[a-z][a-z0-9-]*$")) {
					throw malformed(path, "name must be a lowercase catalog segment");
				}
				if (!(entry.getValue() instanceof AMap<?, ?>)) {
					throw malformed(path, "must be an object");
				}
				AMap<AString, ACell> template = (AMap<AString, ACell>) entry.getValue();
				validateUnknownFields(template, Set.of("script", "functions"), path, strict);
				String script = optionalString(template, SCRIPT, path + ".script");
				if (script == null) throw malformed(path + ".script", "is required");
				Set<String> functions = requiredFunctions(template.get(FUNCTIONS),
					path + ".functions");
				templates.put(id, new TemplateDefinition(id,
					Path.of(script).toAbsolutePath().normalize(), functions));
			}
		}
		if (enabled && templates.isEmpty()) {
			throw malformed("python.instances.templates",
				"must define at least one template when instances are enabled");
		}
		return new InstanceConfig(enabled, maxPerUser, maxTotal, Map.copyOf(templates));
	}

	private static Set<String> requiredFunctions(ACell raw, String path) {
		if (!(raw instanceof AVector<?> vector) || vector.isEmpty()) {
			throw malformed(path, "must be a non-empty array of function names");
		}
		Set<String> result = new LinkedHashSet<>();
		for (long i = 0; i < vector.count(); i++) {
			ACell value = (ACell) vector.get(i);
			if (!(value instanceof AString name)
					|| !name.toString().matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
				throw malformed(path + "[" + i + "]", "must be a Python identifier");
			}
			if (!result.add(name.toString())) {
				throw malformed(path + "[" + i + "]", "duplicates function " + name);
			}
		}
		return Set.copyOf(result);
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

	private static int optionalPositiveInt(AMap<AString, ACell> map,
			AString key, String path, int fallback) {
		ACell value = map.get(key);
		if (value == null) return fallback;
		Object jvm = RT.jvm(value);
		if (!(jvm instanceof Long number) || number < 1 || number > Integer.MAX_VALUE) {
			throw malformed(path, "must be a positive integer");
		}
		return number.intValue();
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
			if (instanceConfig.enabled()) {
				Map<String, PreparedTemplate> prepared = new LinkedHashMap<>();
				for (TemplateDefinition definition : instanceConfig.templates().values()) {
					if (!Files.isRegularFile(definition.script())) {
						throw new IllegalStateException(
							"Python instance template not found: " + definition.script());
					}
					prepared.put(definition.id(), new PreparedTemplate(definition,
						Files.readString(definition.script())));
				}
				templates = Map.copyOf(prepared);
				installInstanceAssets();
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw new IllegalStateException("Failed to install Python operations", e);
		}
		log.info("Python runtime enabled: {} ({} configured operations, {} instance templates)",
			runtime.description(), operations.size(), templates.size());
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

	private void installInstanceAssets() {
		List<ACell> templateNames = templates.keySet().stream()
			.sorted().map(Strings::create).map(ACell.class::cast).toList();
		ACell templateSchema = Maps.of(
			"type", "string",
			"description", "Operator-approved Python template",
			"enum", Vectors.create(templateNames));
		installAsset("python/instances/create", instanceMetadata(INSTANCE_CREATE,
			"Create Python instance",
			"Creates an isolated, stateful Python namespace from an operator-approved template. "
				+ "The instance is ephemeral and owned by the effective venue user.",
			objectSchema(Maps.of("template", templateSchema), "template"),
			instanceDescriptorSchema()));
		installAsset("python/instances/list", instanceMetadata(INSTANCE_LIST,
			"List Python instances",
			"Lists the caller's active Python instances; instances owned by other users are hidden.",
			objectSchema(Maps.empty()),
			objectSchema(Maps.of("instances", Maps.of(
				"type", "array", "items", instanceDescriptorSchema())))));
		installAsset("python/instances/call", instanceMetadata(INSTANCE_CALL,
			"Call Python instance",
			"Calls an allowlisted synchronous function on a Python instance. Args are positional "
				+ "and use the normal Convex/Python value conversion.",
			objectSchema(Maps.of(
				"id", Maps.of("type", "string", "description", "Instance identifier"),
				"function", Maps.of("type", "string", "description", "Allowlisted function name"),
				"args", Maps.of("type", "array", "description", "Zero or more positional arguments")),
				"id", "function"),
			Maps.empty()));
		installAsset("python/instances/close", instanceMetadata(INSTANCE_CLOSE,
			"Close Python instance",
			"Closes one of the caller's Python instances and releases its native references.",
			objectSchema(Maps.of(
				"id", Maps.of("type", "string", "description", "Instance identifier")), "id"),
			objectSchema(Maps.of(
				"id", Maps.of("type", "string"),
				"closed", Maps.of("type", "boolean")), "id", "closed")));
	}

	private static AMap<AString, ACell> instanceMetadata(String id, String name,
			String description, ACell input, ACell output) {
		return Maps.of(
			Fields.NAME, Strings.create(name),
			Fields.DESCRIPTION, Strings.create(description),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("python:" + id),
				Fields.TOOL_NAME, Strings.create("python_" + id.replace('/', '_')),
				Fields.INPUT, input,
				Fields.OUTPUT, output));
	}

	private static AMap<AString, ACell> objectSchema(AMap<?, ?> properties,
			String... required) {
		AMap<AString, ACell> schema = Maps.of(
			Strings.intern("type"), Strings.create("object"),
			Strings.intern("properties"), properties,
			Strings.intern("additionalProperties"), CVMBool.FALSE);
		if (required.length > 0) {
			List<ACell> names = java.util.Arrays.stream(required)
				.map(Strings::create).map(ACell.class::cast).toList();
			schema = schema.assoc(Strings.intern("required"), Vectors.create(names));
		}
		return schema;
	}

	private static AMap<AString, ACell> instanceDescriptorSchema() {
		return objectSchema(Maps.of(
			"id", Maps.of("type", "string"),
			"template", Maps.of("type", "string"),
			"functions", Maps.of("type", "array", "items", Maps.of("type", "string")),
			"createdAt", Maps.of("type", "integer"),
			"createdBy", Maps.of("type", "string")),
			"id", "template", "functions", "createdAt", "createdBy");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String id = getSubOperation(meta);
		Operation operation = operations.get(id);
		if (operation == null && !(instanceConfig.enabled() && INSTANCE_OPERATIONS.contains(id))) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Unknown configured Python operation: " + id));
		}
		return CompletableFuture.supplyAsync(() -> {
			lifecycle.readLock().lock();
			try {
				if (closed) throw new IllegalStateException("Python adapter is closed");
				if (operation == null) return invokeInstance(id, ctx, input);
				return operation.script().call(operation.definition().function(), input);
			} finally {
				lifecycle.readLock().unlock();
			}
		}, VIRTUAL_EXECUTOR);
	}

	private ACell invokeInstance(String operation, RequestContext ctx, ACell input) {
		AString owner = requireOwner(ctx);
		AMap<AString, ACell> parameters = requireInputMap(input);
		return switch (operation) {
			case INSTANCE_CREATE -> createInstance(owner, ctx.getCallerDID(),
				requiredString(parameters, TEMPLATE, "template"));
			case INSTANCE_LIST -> listInstances(owner);
			case INSTANCE_CALL -> callInstance(owner,
				requiredString(parameters, ID, "id"),
				requiredString(parameters, FUNCTION, "function"),
				arguments(parameters.get(ARGS)));
			case INSTANCE_CLOSE -> closeInstance(owner,
				requiredString(parameters, ID, "id"));
			default -> throw new IllegalArgumentException(
				"Unknown Python instance operation: " + operation);
		};
	}

	private ACell createInstance(AString owner, AString createdBy, String templateId) {
		PreparedTemplate template = templates.get(templateId);
		if (template == null) {
			throw new IllegalArgumentException("Unknown Python instance template: " + templateId);
		}
		synchronized (instances) {
			long owned = instances.keySet().stream()
				.filter(key -> key.owner().equals(owner)).count();
			if (owned >= instanceConfig.maxPerUser()) {
				throw new IllegalStateException("Python instance limit reached for user ("
					+ instanceConfig.maxPerUser() + ")");
			}
			if (instances.size() >= instanceConfig.maxTotal()) {
				throw new IllegalStateException("Venue Python instance limit reached ("
					+ instanceConfig.maxTotal() + ")");
			}
			String id = UUID.randomUUID().toString();
			PythonScript script = runtime.load(template.source(),
				template.definition().script() + "#instance-" + id);
			Instance instance = new Instance(id, createdBy, template, script);
			instances.put(new InstanceKey(owner, id), instance);
			return instance.descriptor();
		}
	}

	private ACell listInstances(AString owner) {
		List<ACell> result;
		synchronized (instances) {
			result = instances.entrySet().stream()
				.filter(entry -> entry.getKey().owner().equals(owner))
				.map(Map.Entry::getValue)
				.sorted(java.util.Comparator.comparingLong(instance -> instance.createdAt))
				.map(Instance::descriptor)
				.map(ACell.class::cast)
				.toList();
		}
		return Maps.of(INSTANCES, Vectors.create(result));
	}

	private ACell callInstance(AString owner, String id, String function,
			List<? extends ACell> arguments) {
		Instance instance = instances.get(new InstanceKey(owner, id));
		if (instance == null) throw instanceNotFound(id);
		return instance.call(function, arguments);
	}

	private ACell closeInstance(AString owner, String id) {
		Instance instance;
		synchronized (instances) {
			instance = instances.remove(new InstanceKey(owner, id));
		}
		if (instance == null) throw instanceNotFound(id);
		instance.close();
		return Maps.of(ID, Strings.create(id), Strings.intern("closed"), CVMBool.TRUE);
	}

	private static IllegalArgumentException instanceNotFound(String id) {
		return new IllegalArgumentException("Python instance not found: " + id);
	}

	private AString requireOwner(RequestContext ctx) {
		AString owner = ctx == null ? null : ctx.getUserDID();
		if (owner == null || (engine != null && engine.isPublicPrincipal(owner))) {
			throw new AuthException("Authenticated non-public identity required for Python instances");
		}
		return owner;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> requireInputMap(ACell input) {
		if (!(input instanceof AMap<?, ?>)) {
			throw new IllegalArgumentException("Python instance input must be an object");
		}
		return (AMap<AString, ACell>) input;
	}

	private static String requiredString(AMap<AString, ACell> input,
			AString key, String label) {
		ACell value = input.get(key);
		if (!(value instanceof AString string) || string.isEmpty()) {
			throw new IllegalArgumentException(label + " must be a non-empty string");
		}
		return string.toString();
	}

	private static List<ACell> arguments(ACell raw) {
		if (raw == null) return List.of();
		if (!(raw instanceof AVector<?> vector)) {
			throw new IllegalArgumentException("args must be an array");
		}
		List<ACell> result = new ArrayList<>((int) vector.count());
		for (long i = 0; i < vector.count(); i++) result.add((ACell) vector.get(i));
		return result;
	}

	@Override
	public void close() {
		lifecycle.writeLock().lock();
		try {
			if (closed) return;
			closed = true;
			for (Instance instance : instances.values()) {
				try {
					instance.close();
				} catch (RuntimeException e) {
					log.warn("Failed to release Python instance {}", instance.id, e);
				}
			}
			instances.clear();
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
