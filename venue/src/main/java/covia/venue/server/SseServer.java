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
import convex.core.util.JSON;
import covia.grid.Job;
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
}
