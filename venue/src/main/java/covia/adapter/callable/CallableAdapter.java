package covia.adapter.callable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.adapter.AAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConflictException;
import covia.exception.CoviaException;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.UnsupportedException;
import covia.grid.Asset;
import covia.grid.Operation;
import covia.lattice.CapabilityChecker;
import covia.lattice.Caps;
import covia.lattice.CoviaPath;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.JobManager;
import covia.venue.RequestContext;
import covia.venue.RouteText;

/**
 * Callables (CALLABLES.md): functions a principal publishes for others to
 * call. A callable names an operation of any kind and runs it with its
 * issuer's authority narrowed by caps fixed on it, whoever calls it; the
 * caller is the invoker. A gate operation the provider chooses decides who
 * else may call it, as a Convex trust monitor guards a callable function.
 * This adapter keeps them, as its state ({@link Callables}), offers the
 * {@code callable:} operations and two standard gates, and runs a callable
 * whose path is invoked as an operation ({@link #operation}).
 */
public class CallableAdapter extends AAdapter {

	/** The adapter's name. */
	public static final String NAME = "callable";
	/** The adapter's name as a call's route step names it. */
	private static final AString ADAPTER = Strings.intern(NAME);

	public static final String NAME_INVALID = "name is required and must match [a-z0-9-]{1,64}";
	public static final String CALLABLE_NOT_FOUND = "No callable named %s";
	public static final String CALLABLE_EXISTS = "A callable named %s already exists: update or delete it";
	public static final String CALLABLE_CHANGED = "Callable %s changed while this request was checking it: try again";
	public static final String REFERENCE_INVALID = "%s must be an operation reference";
	public static final String OPERATION_REQUIRED = "operation is required: the operation the callable runs";
	public static final String DESCRIPTION_INVALID = "description must be a string";
	public static final String ISSUER_MAY_NOT_CREATE = "%s may not create callables";
	public static final String CALLABLE_NOT_COVERED = "Callable %s can only be changed by a request acting with its issuer's authority, covering its caps";
	public static final String CALLABLE_INVALID = "callable is required: a callable's name, x/callable/<name> or <ownerDID>/x/callable/<name>";
	public static final String NOT_CALLABLE = "No callable %s that this request may call";
	public static final String GATE_REFUSED = "The gate of %s refused this call: %s";
	public static final String NOT_A_CALLER = "%s is not among this gate's callers";
	public static final String NO_CALLERS = "This gate lists no callers: give it callers as an operation default";
	public static final String CALLABLE_UNNAMED = "This operation names no callable: operation.callable must be <ownerDID>/x/callable/<name>";

	/** The resource a namespace's callables are under, {@code x/callable}; one callable is {@link #resource}. */
	public static final AString CALLABLES_RESOURCE = Strings.intern("x/callable");

	/** A callable's resource as {@code x/callable/<name>} names it, after the prefix. */
	private static final String CALLABLE_PATH = CALLABLES_RESOURCE + "/";

	/** The first segment of a callable's path, the namespace of adapter views. */
	private static final AString VIEWS = Strings.intern("x");

	/** What runs a callable invoked by its path ({@link #operation}). */
	private static final AString INVOKE = Strings.intern(NAME + ":invoke");

	/** What a callable invoked by its path declares of its target's operation: its schema, and what its job records hide. */
	private static final AString[] TARGET_DECLARES = {Fields.INPUT, Fields.OUTPUT, Fields.SECRET_FIELDS};

	private static final Pattern CALLABLE_NAME = Pattern.compile("[a-z0-9-]{1,64}");

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Callables: functions a principal publishes for others to call. Each runs an operation with its publisher's "
			+ "authority narrowed by fixed caps, whoever calls it, and a gate operation chosen by the publisher decides who may call it.";
	}

	@Override
	protected void installAssets() {
		installAsset("callable/create", "/adapters/callable/create.json");
		installAsset("callable/update", "/adapters/callable/update.json");
		installAsset("callable/delete", "/adapters/callable/delete.json");
		installAsset("callable/list",   "/adapters/callable/list.json");
		installAsset("callable/call",   "/adapters/callable/call.json");
		installAsset("callable/gates/anyone",  "/adapters/callable/gates-anyone.json");
		installAsset("callable/gates/callers", "/adapters/callable/gates-callers.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		if ("call".equals(op)) return CompletableFuture.supplyAsync(() -> call(ctx, input), VIRTUAL_EXECUTOR).thenCompose(f -> f);
		if ("invoke".equals(op)) return CompletableFuture.supplyAsync(() -> invoked(ctx, meta, input), VIRTUAL_EXECUTOR).thenCompose(f -> f);
		return CompletableFuture.supplyAsync(() -> switch (op) {
			case "create" -> view(create(ctx, input));
			case "update" -> view(update(ctx, input));
			case "delete" -> Maps.of(Fields.NAME, delete(ctx, requireName(input)), Fields.DELETED, CVMBool.TRUE);
			case "list" -> list(ctx);
			case "gates/anyone" -> CVMBool.TRUE;
			case "gates/callers" -> callers(input);
			default -> throw new UnsupportedException("Unsupported callable operation: " + op);
		}, VIRTUAL_EXECUTOR);
	}

	// ========== The store ==========

	/** The venue's callables. */
	public Callables callables() {
		return callables(adapterState());
	}

	/** The callables in {@code state}, a map read as empty while nothing is stored. */
	private static Callables callables(AdapterState state) {
		return new Callables(state.withDefault(Maps.empty()).cursor());
	}

	/**
	 * What a user sees of its callables at {@code x/callable}: each by name,
	 * as {@link #view} shows it, never its id. A snapshot, so read-only.
	 */
	@Override
	public AForkableCursor<ACell> latticeView(AString owner, RequestContext ctx) {
		if (owner == null) return null;
		AMap<AString, ACell> shown = Maps.empty();
		for (var entry : callables().list(owner).entrySet()) {
			Callable callable = callables().callable(owner, entry.getKey());
			if (callable.record() != null) shown = shown.assoc(entry.getKey(), view(callable).dissoc(Fields.NAME));
		}
		return Cursors.create((ACell) shown);
	}

	@Override
	public void deleteUserState(AString did, AdapterState state) {
		callables(state).deleteOwner(did);
	}

	@Override
	public void deleteAgentState(AString agentDID, AdapterState state) {
		callables(state).deleteIssuer(covia.grid.Principals.userOf(agentDID), agentDID);
	}

	// ========== Resources ==========

	/** The capability resource a callable is in its namespace: {@code x/callable/<name>}. */
	public static AString resource(AString name) {
		return Strings.create(CALLABLE_PATH + name);
	}

	/** A callable's resource, wherever it is named from: {@code <owner>/x/callable/<name>}. */
	public static AString resource(AString owner, AString name) {
		return Strings.create(owner + "/" + CALLABLE_PATH + name);
	}

	// ========== Working on callables ==========

	/**
	 * Creates a callable in the caller's namespace, issued by the principal
	 * whose authority the request acts with ({@link Engine#actingPrincipal}).
	 * Its caps lie within what the request may do itself
	 * ({@link Engine#capsDenial}).
	 */
	Callable create(RequestContext ctx, ACell input) {
		AString owner = owner(ctx);
		AString name = requireName(input);
		engine.requireAuthority(ctx, resource(name), Abilities.CALLABLE_MANAGE);
		AString principal = Engine.actingPrincipal(ctx);
		// A delegate acting for another user holds none of that user's own authority to hand on.
		if (principal == null || engine.isPublicPrincipal(principal) || !engine.actsWithAuthorityOf(ctx, principal)) {
			throw new AuthException(ISSUER_MAY_NOT_CREATE.formatted(principal));
		}
		AString operation = reference(input, Fields.OPERATION);
		if (operation == null) throw new InvalidInputException(OPERATION_REQUIRED);
		requireResolves(ctx, operation);
		AString gate = reference(input, Fields.GATE);
		if (gate != null) requireResolves(ctx, gate);
		ACell caps = RT.getIn(input, Fields.CAPS);
		AVector<ACell> stored = (caps == null) ? Vectors.empty() : engine.expandCaps(Caps.parse(caps));
		requireGivable(ctx, stored);
		AString description = description(input);

		AMap<AString, ACell> record = Maps.of(
			Fields.ID, Callables.newId(),
			Fields.ISSUER, principal,
			Fields.OPERATION, operation,
			Fields.CREATED, engine.currentTimestamp());
		if (stored != null) record = record.assoc(Fields.CAPS, stored);
		if (gate != null) record = record.assoc(Fields.GATE, gate);
		if (description != null) record = record.assoc(Fields.DESCRIPTION, description);
		if (!callables().create(owner, name, record)) throw new ConflictException(CALLABLE_EXISTS.formatted(name));
		return callables().callable(owner, name);
	}

	/**
	 * Changes a callable's operation, caps, gate or description; what is left
	 * out stays as it is, and {@code gate: null} removes its gate. It needs a
	 * request acting with its issuer's authority that covers its caps as they
	 * are and as they become, so no request widens it beyond what it may do
	 * itself.
	 */
	Callable update(RequestContext ctx, ACell input) {
		AString owner = owner(ctx);
		AString name = requireName(input);
		engine.requireAuthority(ctx, resource(name), Abilities.CALLABLE_MANAGE);
		Callable callable = callables().callable(owner, name);
		AString id = callable.id();
		if (id == null) throw new NotFoundException(CALLABLE_NOT_FOUND.formatted(name), name.toString());
		if (!engine.actsWithAuthorityOf(ctx, callable.issuer()) || engine.capsDenial(ctx, callable.caps().scope()) != null) {
			throw new AuthException(CALLABLE_NOT_COVERED.formatted(name));
		}
		AString operation = reference(input, Fields.OPERATION);
		if (operation != null) requireResolves(ctx, operation);
		boolean regated = (input instanceof AMap<?, ?> given) && given.containsKey(Fields.GATE);
		AString gate = reference(input, Fields.GATE);
		if (gate != null) requireResolves(ctx, gate);
		ACell caps = RT.getIn(input, Fields.CAPS);
		AVector<ACell> stored = (caps == null) ? null : engine.expandCaps(Caps.parse(caps));
		if (caps != null) requireGivable(ctx, stored);
		AString description = description(input);

		AMap<AString, ACell> changed = callables().update(owner, name, record -> {
			// Changed only if it is still the callable the checks above were made on
			if (!id.equals(record.get(Fields.ID))) return record;
			AMap<AString, ACell> next = record;
			if (operation != null) next = next.assoc(Fields.OPERATION, operation);
			if (caps != null) next = (stored == null) ? next.dissoc(Fields.CAPS) : next.assoc(Fields.CAPS, stored);
			if (regated) next = (gate == null) ? next.dissoc(Fields.GATE) : next.assoc(Fields.GATE, gate);
			if (description != null) next = next.assoc(Fields.DESCRIPTION, description);
			return next;
		});
		if (changed == null || !id.equals(changed.get(Fields.ID))) throw new ConflictException(CALLABLE_CHANGED.formatted(name));
		return callables().callable(owner, name);
	}

	/** Refuses caps beyond what {@code ctx} may do itself ({@link Engine#capsDenial}); null is unrestricted. */
	private void requireGivable(RequestContext ctx, AVector<ACell> caps) {
		String denial = engine.capsDenial(ctx, caps);
		if (denial != null) throw new AuthException(denial);
	}

	/**
	 * Deletes a callable: it can no longer be called, and work its calls left
	 * running acts with nothing.
	 *
	 * @return its name
	 */
	AString delete(RequestContext ctx, AString name) {
		AString owner = owner(ctx);
		engine.requireAuthority(ctx, resource(name), Abilities.CALLABLE_MANAGE);
		if (callables().delete(owner, name) == null) {
			throw new NotFoundException(CALLABLE_NOT_FOUND.formatted(name), name.toString());
		}
		return name;
	}

	/** The callables in the caller's namespace, needing {@code crud/read} on {@code x/callable}. */
	private AMap<AString, ACell> list(RequestContext ctx) {
		AString owner = owner(ctx);
		engine.requireAuthority(ctx, CALLABLES_RESOURCE, Capability.CRUD_READ);
		AVector<ACell> items = Vectors.empty();
		for (var entry : callables().list(owner).entrySet()) {
			Callable callable = callables().callable(owner, entry.getKey());
			if (callable.record() != null) items = items.conj(view(callable));
		}
		return Maps.of(Callables.CALLABLES, items, Fields.TOTAL, AInteger.create(items.count()));
	}

	// ========== Calling ==========

	/**
	 * Calls a callable (CALLABLES.md, How a call runs): resolves it, admits the
	 * caller, and runs its operation with its authority. A callable that does
	 * not exist, and one without a gate that the caller may not call, are
	 * refused alike, so a private callable is not revealed.
	 *
	 * @return the target's output, when it completes
	 */
	private CompletableFuture<ACell> call(RequestContext ctx, ACell input) {
		AString named = RT.ensureString(RT.getIn(input, Fields.CALLABLE));
		Callable callable = named(ctx, named);
		return run(ctx, callable, RT.getIn(input, Fields.INPUT),
			() -> new NotFoundException(NOT_CALLABLE.formatted(named), named.toString()));
	}

	/**
	 * Runs the call of the callable an operation names when its path was
	 * invoked ({@link #operation}), with {@code input} as its target's input. A
	 * caller that may not call it is refused as for an operation reference
	 * that resolves to nothing, as resolving its path refuses one.
	 *
	 * @return the target's output, when it completes
	 */
	private CompletableFuture<ACell> invoked(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		AString path = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.CALLABLE));
		if (path == null) throw new InvalidDefinitionException(CALLABLE_UNNAMED);
		AString invoked = (ctx.getOp() != null) ? ctx.getOp() : path;
		Supplier<CoviaException> hidden = () -> JobManager.unresolved(invoked);
		Callable callable = atPath(path, ctx);
		if (callable == null) throw hidden.get();
		return run(ctx, callable, input, hidden);
	}

	/**
	 * A call of {@code callable} with {@code input} as its target's input
	 * (CALLABLES.md, How a call runs): admits the caller, enters the issuer's
	 * frame and runs the target there. A caller that may not reach it is
	 * refused with {@code hidden}, as a callable that does not exist is, so a
	 * private callable is not revealed.
	 *
	 * @return the target's output, when it completes
	 */
	private CompletableFuture<ACell> run(RequestContext ctx, Callable callable, ACell input,
			Supplier<CoviaException> hidden) {
		AString resource = resource(callable.owner(), callable.name());
		// The callable hands the caller's request to its issuer: its record is its
		// reference, vouching for the issuer, after the record of whoever called
		RequestContext frame = engine.callContext(ctx, callable.reference());
		RequestContext call = frame.via(ADAPTER, callable.reference(),
			RouteText.builder().ref(callable.owner(), resource(callable.name())).build());
		if (!engine.authorityCovers(ctx, resource, Abilities.CALLABLE_CALL)) {
			if (!gated(ctx, callable)) throw hidden.get();
			String refused = engine.jobs().evaluateCallableGate(callable.gate(), resource, input, ctx, call);
			if (refused != null) throw new AuthException(GATE_REFUSED.formatted(resource, refused));
		}
		AString target = callable.operation();
		Operation operation = engine.jobs().resolveOperation(target, engine.context(callable.owner(), callable.issuer()));
		return engine.jobs().invokeInternal(target, operation, input, call, null);
	}

	/**
	 * Whether {@code ctx} may reach {@code callable}, as far as can be known
	 * before its input: it holds {@code callable/call} on it, perhaps under a
	 * grant whose gate has yet to rule ({@link Engine#authorityMayCover}), or
	 * may pass the callable's own gate ({@link #gated}). A call it reaches
	 * through a gate is still refused if that gate refuses the call.
	 */
	private boolean reaches(RequestContext ctx, Callable callable) {
		return engine.authorityMayCover(ctx, resource(callable.owner(), callable.name()), Abilities.CALLABLE_CALL)
			|| gated(ctx, callable);
	}

	/**
	 * Whether {@code callable}'s gate may admit {@code ctx}: it has one, and
	 * the gate admits the caller only as far as what narrows its request
	 * reaches, as any admission does ({@link Engine#admissionCovers}).
	 */
	private boolean gated(RequestContext ctx, Callable callable) {
		return callable.gate() != null
			&& engine.admissionCovers(ctx, resource(callable.owner(), callable.name()), Abilities.CALLABLE_CALL);
	}

	/**
	 * The callable {@code named} names, as an agent reference is named: a bare
	 * name for one in the caller's namespace, {@code x/callable/<name>}, or
	 * {@code <ownerDID>/x/callable/<name>}.
	 *
	 * @throws NotFoundException when it names none that exists
	 */
	private Callable named(RequestContext ctx, AString named) {
		if (named == null) throw new InvalidInputException(CALLABLE_INVALID);
		AString value = engine.expandPath(named);
		Named path = path(value, ctx.getUserDID());
		// Anything else is a bare name for one of the caller's own
		if (path == null) path = new Named(ctx.getUserDID(), value);
		if (!CALLABLE_NAME.matcher(path.name().toString()).matches()) throw new InvalidInputException(CALLABLE_INVALID);
		Callable callable = (path.owner() == null) ? null : callables().callable(path.owner(), path.name());
		if (callable == null || callable.id() == null) throw new NotFoundException(NOT_CALLABLE.formatted(named), value.toString());
		return callable;
	}

	// ========== A callable as an operation ==========

	/** A callable as its path names it: its owner, null when the path names it in no namespace, and its name. */
	private record Named(AString owner, AString name) {}

	/**
	 * The callable a callable's path names: {@code <ownerDID>/x/callable/<name>},
	 * or {@code x/callable/<name>} for one in {@code namespace}, read as any
	 * path is, so a leading slash changes nothing. Null when {@code ref} is no
	 * callable's path; the name need not be one a callable can have.
	 */
	private static Named path(AString ref, AString namespace) {
		if (ref == null) return null;
		AString owner = Engine.ownerOf(ref);
		String canonical = CapabilityChecker.canonicalResource(ref.toString(), null);
		Path path = CoviaPath.parse((owner == null) ? canonical : canonical.substring(owner.toString().length()));
		if (path.count() != 3 || !VIEWS.equals(path.get(0)) || !ADAPTER.equals(path.get(1))) return null;
		return new Named((owner == null) ? namespace : owner, RT.ensureString(path.get(2)));
	}

	/**
	 * Whether {@code ref} is a callable's path, which operation resolution
	 * resolves through {@link #operation} alone.
	 */
	public static boolean isPath(AString ref) {
		return path(ref, null) != null;
	}

	/** The callable at callable's path {@code ref}, read in {@code ctx}'s namespace; null when there is none. */
	private Callable atPath(AString ref, RequestContext ctx) {
		Named path = path(ref, ctx.getUserDID());
		if (path == null || path.owner() == null || !CALLABLE_NAME.matcher(path.name().toString()).matches()) return null;
		Callable callable = callables().callable(path.owner(), path.name());
		return (callable.id() == null) ? null : callable;
	}

	/**
	 * The operation a callable's path names (CALLABLES.md, Invoking a callable
	 * by its path): it runs the callable's call, taking the target's input
	 * directly, and declares the target's schema. It is admitted here as far
	 * as can be known before the input ({@link #reaches}); otherwise there is
	 * none, so a callable the request may not call reads as one that does not
	 * exist. The target is resolved as the issuer would resolve it.
	 *
	 * @param ref a callable's path ({@link #isPath})
	 * @param fetch whether a target another venue holds may be fetched from it
	 * @return the operation, or null when {@code ref} names no callable
	 *         {@code ctx} may reach, or one whose target is no operation
	 */
	public Asset operation(AString ref, RequestContext ctx, boolean fetch) {
		return operation(ref, ctx, fetch, new HashSet<>());
	}

	/** As above, {@code seen} holding the callables whose targets led here. */
	private Asset operation(AString ref, RequestContext ctx, boolean fetch, Set<AString> seen) {
		Callable callable = atPath(ref, ctx);
		if (callable == null || !reaches(ctx, callable)) return null;
		// Callables whose targets lead back to one of them name no operation
		if (!seen.add(resource(callable.owner(), callable.name()))) return null;
		RequestContext issuer = engine.context(callable.owner(), callable.issuer());
		AString target = callable.operation();
		Asset resolved = isPath(target) ? operation(target, issuer, fetch, seen)
			: fetch ? engine.resolveAsset(target, issuer) : engine.resolveLocalAsset(target, issuer);
		Operation operation = Operation.from(resolved);
		return (operation == null) ? null : Asset.fromMeta(definition(callable, operation.meta()));
	}

	/**
	 * What invoking {@code callable} by its path is, as an operation: a call of
	 * it, named after it, declaring what {@code target}, its operation's
	 * definition, declares of its input and output. Its description is the
	 * callable's, or else the target's.
	 */
	private static AMap<AString, ACell> definition(Callable callable, AMap<AString, ACell> target) {
		AMap<AString, ACell> operation = Maps.of(
			Fields.ADAPTER, INVOKE,
			Fields.CALLABLE, resource(callable.owner(), callable.name()),
			Fields.TOOL_NAME, callable.name());
		for (AString declared : TARGET_DECLARES) {
			ACell value = RT.getIn(target, Fields.OPERATION, declared);
			if (value != null) operation = operation.assoc(declared, value);
		}
		AString description = (callable.description() != null) ? callable.description()
			: RT.ensureString(RT.getIn(target, Fields.DESCRIPTION));
		AMap<AString, ACell> definition = Maps.of(Fields.NAME, callable.name(), Fields.OPERATION, operation);
		return (description == null) ? definition : definition.assoc(Fields.DESCRIPTION, description);
	}

	// ========== Standard gates ==========

	/**
	 * The callers gate: admits a caller that acts for a principal its
	 * {@code callers} lists ({@link Engine#actsFor}): that principal, or an
	 * agent acting for it up its chain of controllers. The caller is read from
	 * the gate's input, which the evaluator builds from the call
	 * ({@link covia.venue.JobManager#evaluateCallableGate}); how far a caller
	 * it admits may use that is bounded before any gate runs
	 * ({@link Engine#admissionCovers}).
	 */
	private ACell callers(ACell input) {
		AVector<ACell> callers = RT.ensureVector(RT.getIn(input, Fields.CALLERS));
		if (callers == null || callers.isEmpty()) throw new InvalidInputException(NO_CALLERS);
		AString caller = RT.ensureString(RT.getIn(input, Fields.CALLER));
		for (ACell listed : callers) {
			if (engine.actsFor(caller, engine.expandPrincipal(RT.ensureString(listed)))) return CVMBool.TRUE;
		}
		throw new AuthException(NOT_A_CALLER.formatted(caller));
	}

	// ========== What the operations take and return ==========

	/** A callable's name from an operation's input. */
	public static AString requireName(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || !CALLABLE_NAME.matcher(name.toString()).matches()) throw new InvalidInputException(NAME_INVALID);
		return name;
	}

	/** What the operations and the user's view show of a callable, never its id or source. */
	static AMap<AString, ACell> view(Callable callable) {
		AVector<ACell> caps = callable.caps().scope();
		AMap<AString, ACell> shown = Maps.of(
			Fields.NAME, callable.name(),
			Fields.ISSUER, callable.issuer(),
			Fields.OPERATION, callable.operation(),
			Fields.CAPS, (caps == null) ? Caps.UNRESTRICTED : caps,
			Fields.CREATED, callable.record().get(Fields.CREATED));
		if (callable.gate() != null) shown = shown.assoc(Fields.GATE, callable.gate());
		if (callable.description() != null) shown = shown.assoc(Fields.DESCRIPTION, callable.description());
		return shown;
	}

	/** The namespace a request on callables acts in, which its callables belong to. */
	private static AString owner(RequestContext ctx) {
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException("Authentication required");
		return owner;
	}

	/** An operation reference from {@code input}'s {@code field}, or null when it gives none. */
	private AString reference(ACell input, AString field) {
		ACell value = RT.getIn(input, field);
		if (value == null) return null;
		if (!(value instanceof AString reference) || reference.count() == 0) {
			throw new InvalidInputException(REFERENCE_INVALID.formatted(field));
		}
		return engine.expandPath(reference);
	}

	/** The description in {@code input}, or null when it gives none. */
	private static AString description(ACell input) {
		ACell value = RT.getIn(input, Fields.DESCRIPTION);
		if (value == null) return null;
		if (!(value instanceof AString description)) throw new InvalidInputException(DESCRIPTION_INVALID);
		return description;
	}

	/** Refuses a reference that names no operation the request can read. */
	private void requireResolves(RequestContext ctx, AString reference) {
		engine.jobs().resolveOperation(reference, ctx);
	}
}
