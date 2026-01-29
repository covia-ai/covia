package covia.venue.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.venue.Engine;
import io.javalin.http.sse.SseClient;

public class SseServer {

	public static final Logger log=LoggerFactory.getLogger(SseServer.class);

	/** Per-job client subscriptions */
	private final ConcurrentHashMap<String, Set<SseClient>> jobClients = new ConcurrentHashMap<>();

	protected Engine engine;

	public SseServer(Engine engine) {
		this.engine=engine;
	}

	/**
	 * Javalin SSE handler for per-job event subscriptions.
	 * Extracts job ID from the path parameter and registers the client.
	 */
	public Consumer<SseClient> registerSSE = client -> {
		String jobId = client.ctx().pathParam("id");
		registerClient(jobId, client);

		// Send current job state as initial event
		Job job = engine.getJob(jobId);
		if (job != null) {
			sendJobEvent(client, job);
		}
	};

	/**
	 * Registers an SSE client to receive updates for a specific job.
	 * @param jobId Job ID to subscribe to
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
	 * @param jobId Job ID to unsubscribe from
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
	 * Broadcasts a job update event to all SSE clients watching the given job.
	 * @param jobId Job ID
	 * @param job Job with updated state
	 */
	public void broadcastJobUpdate(String jobId, Job job) {
		Set<SseClient> clients = jobClients.get(jobId);
		if (clients == null || clients.isEmpty()) return;

		for (SseClient client : clients) {
			try {
				sendJobEvent(client, job);
			} catch (Exception e) {
				log.warn("Failed to send SSE event to client for job {}: {}", jobId, e.getMessage());
			}
		}
	}

	/**
	 * Creates an update listener for a job that broadcasts via SSE.
	 * Set this on a job via job.setUpdateListener().
	 * @param jobId Job ID for routing
	 * @return Consumer that broadcasts job updates to SSE clients
	 */
	public Consumer<Job> createJobListener(String jobId) {
		return job -> broadcastJobUpdate(jobId, job);
	}

	private void sendJobEvent(SseClient client, Job job) {
		AMap<AString, ACell> data = job.getData();
		String json = JSON.toString(data);
		client.sendEvent("job-update", json);
	}
}
