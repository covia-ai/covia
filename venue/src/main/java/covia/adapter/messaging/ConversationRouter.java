package covia.adapter.messaging;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.adapter.messaging.ChannelAuthentication.Channel;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Provider-independent Job routing and per-conversation ordering. Transport
 * authentication/admission must happen before enqueueing. This in-memory queue
 * is not a durable inbox and must not be used as a webhook acknowledgement.
 *
 * <p>Every message comes from a principal. The provider passes each message's
 * verified {@link ChannelAuthentication.Channel} beside it, and the binding
 * authenticates it ({@link ChannelAuthentication#authenticate}): the request
 * runs as the identified principal, carrying the authentication record. The
 * message itself is the sender's content only.</p>
 */
public final class ConversationRouter {
	private static final String AGENT_CHAT = "v/ops/agent/chat";
	private static final AString[] TEXT_KEYS = {
		Fields.TEXT, Fields.RESPONSE, Strings.intern("content"), Fields.MESSAGE, Fields.RESULT };
	private final Engine engine;
	private final MessagingBotSpec spec;
	private final AString principal;
	private final Predicate<AMap<AString, ACell>> lists;
	private final ConversationSessions sessions;
	private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

	/** A binding whose identified senders come from its user. */
	public ConversationRouter(Engine engine, MessagingBotSpec spec, ConversationSessions sessions) {
		this(engine, spec, null, sessions);
	}

	/**
	 * @param principal who the binding's identified senders come from: the
	 *        principal that set it up, which may be an agent of its user; null for
	 *        the binding's user
	 */
	public ConversationRouter(Engine engine, MessagingBotSpec spec, AString principal, ConversationSessions sessions) {
		this(engine, spec, principal, sessions, spec::lists);
	}

	/**
	 * @param lists whether the binding lists a verified sender, for a provider whose
	 *        listing holds state of its own; otherwise {@link MessagingBotSpec#lists}
	 */
	public ConversationRouter(Engine engine, MessagingBotSpec spec, AString principal, ConversationSessions sessions,
			Predicate<AMap<AString, ACell>> lists) {
		this.engine = Objects.requireNonNull(engine);
		this.spec = Objects.requireNonNull(spec);
		this.principal = principal;
		this.sessions = Objects.requireNonNull(sessions);
		this.lists = Objects.requireNonNull(lists);
	}

	/** Serializes submitted work per conversation. Delivery callers enqueue intake only; replies run independently. */
	public CompletableFuture<Void> enqueue(String conversation, Runnable work) {
		Objects.requireNonNull(conversation);
		Objects.requireNonNull(work);
		CompletableFuture<Void> next = new CompletableFuture<>();
		// Publish the tail before starting work. Completion/cleanup inside compute
		// can otherwise race insertion or recursively modify ConcurrentHashMap.
		CompletableFuture<Void> previous = tails.put(conversation, next);
		if (previous == null) previous = CompletableFuture.completedFuture(null);
		next.whenComplete((result, failure) -> tails.remove(conversation, next));
		previous.handleAsync((result, failure) -> {
			try { sessions.requireCurrentUser(); work.run(); next.complete(null); }
			catch (Throwable t) { next.completeExceptionally(t); }
			return null;
		}, AAdapter.VIRTUAL_EXECUTOR);
		return next.copy();
	}

	public boolean isIdle(String conversation) {
		CompletableFuture<Void> tail = tails.get(conversation);
		return tail == null || tail.isDone();
	}

	/** Whether the agent already holds this conversation: a stored session, or a turn queued or running. */
	public boolean holds(String conversation) {
		return spec.routesToAgent() && (!isIdle(conversation) || sessions.get(conversation) != null);
	}

	/** Call within this conversation's queued turn, for a message that came
	 * through {@code channel}. */
	public ACell chat(String conversation, ACell message, Channel channel) {
		String sid = sessions.get(conversation);
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agentAddress(), Fields.MESSAGE, message);
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid));
		RequestContext ctx = context(channel);
		ACell result;
		try {
			result = run(AGENT_CHAT, input, ctx);
		} catch (RuntimeException e) {
			if (sid == null || !isUnknownSession(e)) throw e;
			sessions.remove(conversation);
			result = run(AGENT_CHAT, input.dissoc(Fields.SESSION_ID), ctx);
		}
		AString newSession = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		if (newSession != null && !newSession.toString().equals(sid)) sessions.put(conversation, newSession.toString());
		return RT.getIn(result, Fields.RESPONSE);
	}

	/** Whether this binding's agent takes a message on {@code channel} by atomic
	 * session delivery: a local agent, or one that cannot take it at all, whose
	 * delivery is then refused once. False for an external agent facade, which
	 * keeps its claimed-chat protocol. */
	public boolean supportsSessionDelivery(Channel channel) {
		return engine.getAdapter("agent") instanceof AgentAdapter adapter
			&& (deliveryRefusal(channel) != null || adapter.supportsSessionDelivery(context(channel), agentAddress()));
	}

	/** Null when this binding's agent can take a message on {@code channel} now,
	 * from whoever it comes from; otherwise why not. */
	public String deliveryRefusal(Channel channel) {
		if (!(engine.getAdapter("agent") instanceof AgentAdapter adapter)) return null;
		RequestContext asker;
		try {
			asker = context(channel);
		} catch (AuthException refused) {
			return refused.getMessage();
		}
		return adapter.sessionDeliveryRefusal(asker, agentAddress());
	}

	/** Admit input without waiting for a response. The participant and session map
	 * commit with the input; null means a previously accepted delivery. */
	public Job deliver(String conversation, ACell message, Channel channel, RequestContext.SessionDelivery delivery) {
		// Registration/deletion also takes Engine before the binding lock. Keep
		// stop/delete from racing the transaction's publication of private state.
		synchronized (engine) {
			return sessions.mutate(() -> deliverLocked(conversation, message, channel, delivery));
		}
	}

	private Job deliverLocked(String conversation, ACell message, Channel channel, RequestContext.SessionDelivery delivery) {
		String sid = sessions.get(conversation);
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agentAddress(), Fields.MESSAGE, message);
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid));
		RequestContext ctx = context(channel).withSessionDelivery((draft, session, job) -> {
			boolean applied = delivery.accept(draft, session, job);
			if (applied) sessions.put(draft, conversation, session.toString());
			return applied;
		});
		AgentAdapter adapter = (AgentAdapter) engine.getAdapter("agent");
		Job job = adapter.deliverSession(input, ctx);
		if (!deliveryAccepted(job) && sid != null && isUnknownSession(new JobFailedException(job))) {
			job = adapter.deliverSession(input.dissoc(Fields.SESSION_ID), ctx);
		}
		// Admission may succeed even if execution subsequently fails immediately.
		if (!deliveryAccepted(job) && job.getStatus() != Status.COMPLETE) throw new JobFailedException(job);
		return deliveryAccepted(job) ? job : null;
	}

	private static boolean deliveryAccepted(Job job) {
		return convex.core.data.prim.CVMBool.TRUE.equals(job.getData().get(AgentAdapter.DELIVERY_ACCEPTED));
	}

	/** Observe a delivered chat independently of intake. A duplicate has no reply.
	 * Providers retain responsibility for checking their lifecycle before sending. */
	public static void onResponse(Job job, Consumer<ACell> reply, Consumer<Throwable> failure) {
		if (job == null) return;
		job.future().whenCompleteAsync((result, error) -> {
			try {
				if (error != null) throw new JobFailedException(job);
				reply.accept(RT.getIn(result, Fields.RESPONSE));
			} catch (Throwable t) { failure.accept(t); }
		}, AAdapter.VIRTUAL_EXECUTOR);
	}

	/** Call within the same queue as chat, so a reset cannot race an in-flight turn. */
	public void reset(String conversation) { sessions.remove(conversation); }

	/** The binding's principal as it acts now ({@link Engine#principalContext}):
	 * who its listed senders, and its own work, come from. */
	public RequestContext context() {
		sessions.requireCurrentUser();
		return engine.principalContext(principal());
	}

	private AString principal() {
		return principal != null ? principal : spec.userDID(engine);
	}

	/**
	 * Who a message that came through {@code channel} comes from, authenticated by
	 * this binding ({@link ChannelAuthentication#authenticate}): its principal for a
	 * listed sender, the public principal for any other on an open binding. The
	 * context carries the authentication record.
	 *
	 * A provider event with no sender at all (a poll, say) is anonymous on an
	 * open binding and carries no record.
	 *
	 * @throws AuthException for a sender the binding does not admit, or an
	 *         anonymous sender while public access is off
	 */
	public RequestContext context(Channel channel) {
		sessions.requireCurrentUser();
		if (channel == null) {
			if (!spec.open()) throw new AuthException(ChannelAuthentication.NOT_ADMITTED);
			return engine.principalContext(engine.publicPrincipal());
		}
		return engine.channelContext(ChannelAuthentication.authenticate(engine, spec, principal(),
			lists.test(channel.sender()), channel));
	}

	/** The binding's agent as a full address. A relative reference names an agent
	 * of the binding's user, wherever the message comes from. */
	private AString agentAddress() {
		String agent = spec.agent();
		if (agent.startsWith("did:")) return Strings.create(agent);
		if (agent.startsWith("/")) agent = agent.substring(1);
		if (agent.startsWith("g/")) agent = agent.substring(2);
		return Strings.create(spec.userDID(engine) + "/g/" + agent);
	}

	/**
	 * The context the binding's work for its agent runs under — storing a
	 * message's media, processing it, formatting the reply: the agent itself,
	 * bounded by its caps, when the binding's principal controls it (is its owner,
	 * or the agent); otherwise the binding's principal, since nobody else may make
	 * an agent act.
	 */
	public RequestContext agentContext() {
		String address = agentAddress().toString();
		int split = address.indexOf("/g/");
		if (split < 0) return context(); // not an agent address; asking it is refused anyway
		AString owner = Strings.create(address.substring(0, split));
		AString agent = Principals.agentDID(owner, Strings.create(address.substring(split + 3)));
		AString self = principal();
		if (!self.equals(owner) && !self.equals(agent)) return context();
		sessions.requireCurrentUser();
		return engine.principalContext(agent);
	}

	/** Run an operation as the binding's own work. */
	public ACell runOperation(String operation, ACell input) {
		return run(operation, input, context());
	}

	/** Run an operation as the binding's work for its agent ({@link #agentContext()}). */
	public ACell runForAgent(String operation, ACell input) {
		return run(operation, input, agentContext());
	}

	/** Run the binding's operation for a provider's message, from whoever it comes
	 * from ({@link #context(Channel)}). */
	public ACell runOperation(String operation, ACell input, Channel channel) {
		return run(operation, input, context(channel));
	}

	private ACell run(String operation, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(operation, input, ctx);
		ACell result = job.awaitResult();
		if (job.getStatus() != Status.COMPLETE) {
			throw new JobFailedException(job);
		}
		return result;
	}

	/** Operation reply policy: null means no reply. */
	public String operationReply(ACell result) {
		if (spec.silent()) return null;
		String fixed = spec.fixedReply();
		return fixed != null ? fixed : responseText(result);
	}

	/** The reply to post, or null for none: an agent that ended its turn without one, or a result with no text. */
	public static String responseText(ACell value) {
		String text = renderText(value);
		return text == null || text.isBlank() ? null : text;
	}

	public static String renderText(ACell value) {
		if (value == null) return null;
		if (value instanceof AString text) return text.toString();
		if (value instanceof AMap<?, ?> map) for (AString key : TEXT_KEYS) {
			ACell v = RT.getIn(map, key);
			if (v instanceof AString text) return text.toString();
		}
		return JSON.printPretty(value).toString();
	}

	/** Unambiguous opaque key, e.g. Slack installation + channel + thread timestamp. */
	public static String key(String... components) {
		StringBuilder result = new StringBuilder();
		for (String component : components) {
			Objects.requireNonNull(component);
			result.append(component.length()).append(':').append(component);
		}
		return result.toString();
	}

	static boolean isUnknownSession(Throwable t) {
		for (Throwable cause = t; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
			if (cause instanceof JobFailedException failure
					&& AgentAdapter.UNKNOWN_SESSION_CODE.equals(failure.getErrorCode())) return true;
		}
		return false;
	}
}
