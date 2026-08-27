package covia.venue.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentEvents;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import io.javalin.http.sse.SseClient;

public class SseServer {

	public static final Logger log=LoggerFactory.getLogger(SseServer.class);

	/** Per-job client subscriptions, keyed by the job's CANONICAL bare hex id
	 *  ({@code Blob.toHexString()}) — the same form {@link #broadcastJobUpdate}
	 *  is called with. Clients may subscribe with any parseable form
	 *  ({@code 0x}-prefixed as the REST API renders ids, mixed case, …);
	 *  keying by the raw path parameter broke every broadcast for
	 *  {@code 0x}-form subscribers (#225). */
	private final ConcurrentHashMap<String, Set<SseClient>> jobClients = new ConcurrentHashMap<>();

	protected Engine engine;

	public SseServer(Engine engine) {
		this.engine=engine;
	}

	/**
	 * Javalin SSE handler for per-job event subscriptions. The HTTP-level
	 * concerns (id validity, job existence/ownership, Accept negotiation)
	 * are handled by the route handler BEFORE the stream is committed —
	 * this consumer owns the stream lifecycle:
	 *
	 * <ol>
	 *   <li>register the client under the canonical job key (BEFORE the
	 *       initial frame, so a transition in between is never missed)</li>
	 *   <li>send the current record as the initial {@code job-update} frame</li>
	 *   <li>a job already terminal streams its final record and closes —
	 *       there will never be another update</li>
	 * </ol>
	 */
	public Consumer<SseClient> registerSSE = client -> {
		// Hold the connection open after this handler returns — without
		// keepAlive Javalin completes the response immediately and the
		// client never receives subsequent job-update broadcasts (#200).
		client.keepAlive();

		Blob jobId = Blob.parse(client.ctx().pathParam("id"));
		String key = jobId.toHexString();

		// Resolve the record under the CALLER's context — the stream carries
		// exactly what GET /jobs/{id} would show this caller.
		RequestContext rctx = AuthMiddleware.callerContext(client.ctx());
		AMap<AString, ACell> record = engine.jobs().getJobData(jobId, rctx);
		if (record == null) {
			// Vanished between the route check and here (e.g. deleted)
			client.sendEvent("error", "{\"error\":\"Job not found: " + key + "\"}");
			client.close();
			return;
		}
		if (Job.isFinished(record)) {
			// Terminal already — deliver the final record and end the stream
			client.sendEvent("job-update", JSON.toString(record));
			client.close();
			return;
		}

		registerClient(key, client);
		client.sendEvent("job-update", JSON.toString(record));
	};

	/**
	 * Registers an SSE client to receive updates for a specific job.
	 * @param jobId Canonical job ID (bare hex)
	 * @param client SSE client
	 */
	public void registerClient(String jobId, SseClient client) {
		Set<SseClient> clients = jobClients.computeIfAbsent(jobId,
				k -> ConcurrentHashMap.newKeySet());
		clients.add(client);
		client.onClose(() -> {
			unregisterClient(jobId, client);
		});
		log.info("SSE client connected for job: {}", jobId);
	}

	/**
	 * Unregisters an SSE client from a job's event stream.
	 * @param jobId Canonical job ID (bare hex)
	 * @param client SSE client
	 */
	public void unregisterClient(String jobId, SseClient client) {
		Set<SseClient> clients = jobClients.get(jobId);
		if (clients != null) {
			clients.remove(client);
			if (clients.isEmpty()) {
				jobClients.remove(jobId);
			}
		}
		log.info("SSE client disconnected for job: {}", jobId);
	}

	/**
	 * Broadcasts a job update event to all SSE clients watching the given
	 * job. On a terminal update the stream is closed after the frame — the
	 * documented contract is "streams until the job reaches a terminal
	 * state", and there will never be another frame to wait for.
	 *
	 * @param jobId Canonical job ID (bare hex, {@code Blob.toHexString()} form)
	 * @param job Job with updated state
	 */
	public void broadcastJobUpdate(String jobId, Job job) {
		Set<SseClient> clients = jobClients.get(jobId);
		if (clients == null || clients.isEmpty()) return;

		boolean finished = job.isFinished();
		for (SseClient client : clients) {
			try {
				sendJobEvent(client, job);
				if (finished) client.close(); // onClose unregisters
			} catch (Exception e) {
				log.warn("Failed to send SSE event to client for job {}: {}", jobId, e.getMessage());
			}
		}
	}

	private void sendJobEvent(SseClient client, Job job) {
		AMap<AString, ACell> data = job.getData();
		String json = JSON.toString(data);
		client.sendEvent("job-update", json);
	}

	// ========== Agent streams (#394) ==========

	/** One agent-stream client and whether its frames carry {@code detail}. */
	private record AgentClient(SseClient client, boolean detail, AString sessionId) {}

	/** Per-agent client subscriptions keyed by the agent's grid address
	 *  ({@code <ownerDID>/g/<agentId>}) — the key every
	 *  {@link AgentEvents.Event} carries, so fan-out needs no lookup. */
	private final ConcurrentHashMap<AString, Set<AgentClient>> agentClients = new ConcurrentHashMap<>();

	/**
	 * Javalin SSE handler for per-agent live event subscriptions
	 * ({@code GET /agents/{id}/sse}). Authentication, existence/ownership and
	 * Accept are settled by the route handler before the stream is
	 * committed; this consumer owns the stream:
	 *
	 * <ol>
	 *   <li>resolve the agent under the caller's context, exactly as
	 *       {@code GET /agents/{id}} does</li>
	 *   <li>register the client (BEFORE the initial frame, so an event in
	 *       between is never missed)</li>
	 *   <li>send the current observable status as the initial {@code status}
	 *       frame, stamped with the agent's current {@code seq}</li>
	 *   <li>an agent already TERMINATED gets that frame and the stream closes</li>
	 * </ol>
	 */
	public Consumer<SseClient> registerAgentSSE = client -> {
		client.keepAlive();

		String ref = client.ctx().pathParam("id");
		RequestContext rctx = AuthMiddleware.callerContext(client.ctx());
		AgentAdapter agents = (AgentAdapter) engine.getAdapter("agent");
		AMap<AString, ACell> info = (agents != null) ? agents.agentInfo(rctx, Strings.create(ref)) : null;
		if (info == null) {
			client.sendEvent("error", "{\"error\":\"Agent not found: " + ref + "\"}");
			client.close();
			return;
		}
		AString address = RT.ensureString(info.get(Fields.ADDRESS));
		AString agentId = RT.ensureString(info.get(Fields.AGENT_ID));
		AString status = RT.ensureString(info.get(Fields.STATUS));
		boolean detail = !"false".equalsIgnoreCase(client.ctx().queryParam("detail"));
		AString sessionFilter = sessionFilter(client.ctx().queryParam("sessionId"));
		boolean terminated = AgentState.TERMINATED.equals(status);

		if (!terminated) registerAgentClient(address, new AgentClient(client, detail, sessionFilter));

		AMap<AString, ACell> initial = Maps.of(
			Fields.SEQ, CVMLong.create(engine.agentEvents().lastSeq(address)),
			Fields.TS, CVMLong.create(Utils.getCurrentTimestamp()),
			Fields.TYPE, AgentEvents.STATUS,
			Fields.AGENT_ID, agentId,
			Fields.ADDRESS, address,
			Fields.STATUS, status);
		if (sessionFilter != null) initial = initial.assoc(Fields.SESSION_ID, sessionFilter);
		client.sendEvent(AgentEvents.STATUS.toString(), JSON.toString(initial));
		if (terminated) client.close();
	};

	private void registerAgentClient(AString address, AgentClient ac) {
		Set<AgentClient> clients = agentClients.computeIfAbsent(address,
				k -> ConcurrentHashMap.newKeySet());
		clients.add(ac);
		ac.client().onClose(() -> {
			Set<AgentClient> set = agentClients.get(address);
			if (set != null) {
				set.remove(ac);
				if (set.isEmpty()) agentClients.remove(address, set);
			}
			log.info("SSE client disconnected for agent: {}", address);
		});
		log.info("SSE client connected for agent: {}", address);
	}


	/**
	 * The session a stream is narrowed to, as the bare hex the events carry
	 * (any parseable form is accepted, {@code 0x}-prefixed included); null
	 * for the whole agent, or for an unparseable value — the route handler
	 * rejects that with 400 before the stream is committed.
	 */
	public static AString sessionFilter(String param) {
		if (param == null || param.isBlank()) return null;
		Blob sid = Blob.parse(param.trim());
		return (sid != null) ? Strings.create(sid.toHexString()) : null;
	}
	/**
	 * Fans one live agent event out to that agent's stream clients: the
	 * event type is the SSE event name, {@code seq} the SSE id, and the wire
	 * form the data — without {@code detail} for a client that declined it.
	 * The stream closes after the TERMINATED status frame; there will never
	 * be another.
	 */
	public void broadcastAgentEvent(AgentEvents.Event event) {
		Set<AgentClient> clients = agentClients.get(event.address());
		if (clients == null || clients.isEmpty()) return;

		boolean terminal = event.isTerminal();
		String full = null;
		String safe = null;
		for (AgentClient ac : clients) {
			if (!event.concerns(ac.sessionId())) continue;
			try {
				String json;
				if (ac.detail()) {
					if (full == null) full = JSON.toString(event.toCell());
					json = full;
				} else {
					if (safe == null) safe = JSON.toString(event.withoutDetail().toCell());
					json = safe;
				}
				ac.client().sendEvent(event.type().toString(), json, Long.toString(event.seq()));
				if (terminal) ac.client().close(); // onClose unregisters
			} catch (Exception e) {
				log.warn("Failed to send agent SSE event to client for {}: {}", event.address(), e.getMessage());
			}
		}
	}
}
