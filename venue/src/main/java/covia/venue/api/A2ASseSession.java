package covia.venue.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import io.javalin.http.sse.SseClient;

/**
 * One active A2A streaming subscription.
 *
 * <p>Per spec §9.4.2 / §9.4.6, {@code SendStreamingMessage} and
 * {@code SubscribeToTask} return {@code text/event-stream} frames where each
 * event wraps a {@code StreamResponse} union in a JSON-RPC 2.0 envelope:</p>
 *
 * <pre>
 * data: {"jsonrpc":"2.0","id":&lt;req-id&gt;,"result":{"task":{...}}}
 * data: {"jsonrpc":"2.0","id":&lt;req-id&gt;,"result":{"statusUpdate":{...,"final":false}}}
 * data: {"jsonrpc":"2.0","id":&lt;req-id&gt;,"result":{"statusUpdate":{...,"final":true}}}
 * </pre>
 *
 * <p>Lifecycle: {@link #start()} sends the initial Task frame, registers a
 * filter listener on {@link covia.venue.JobManager}, and returns. Subsequent
 * Job transitions trigger {@link #onJobUpdate} which emits
 * {@link TaskStatusUpdateEvent} frames. Terminal state emits one last frame
 * with {@code final: true} and closes the stream. The peer may also close
 * early (timeout, abort); onClose deregisters the listener.</p>
 *
 * <p>Artifact streaming ({@code TaskArtifactUpdateEvent}, incremental
 * {@code append}/{@code lastChunk}) is not implemented — no Covia adapter
 * emits per-artifact events today. Add when an adapter needs it.</p>
 */
public class A2ASseSession {

	private static final Logger log = LoggerFactory.getLogger(A2ASseSession.class);

	private final SseClient sseClient;
	private final Engine engine;
	private final Blob taskId;
	private final Object rpcRequestId;
	private final RequestContext rctx;

	private final Consumer<Job> listener = this::onJobUpdate;
	private final AtomicBoolean closed = new AtomicBoolean(false);

	public A2ASseSession(SseClient sseClient, Engine engine, Blob taskId,
			Object rpcRequestId, RequestContext rctx) {
		this.sseClient = sseClient;
		this.engine = engine;
		this.taskId = taskId;
		this.rpcRequestId = rpcRequestId;
		this.rctx = rctx;
	}

	/**
	 * Emit the initial Task frame and attach the listener. Returns immediately
	 * — subsequent frames are driven by Job updates.
	 *
	 * <p>If the Job is already in a terminal state, emits the initial Task
	 * plus one {@code final:true} statusUpdate, then closes.</p>
	 */
	public void start() {
		// Disconnect hook before anything else — Javalin closes idle SSE
		// connections after a timeout and the peer may abort at any time.
		sseClient.onClose(this::close);

		AMap<AString, ACell> data = engine.jobs().getJobData(taskId, rctx);
		if (data == null) {
			// Race: task existed at route-dispatch but was evicted before we
			// got here. Close cleanly; the POST handler already returned 200.
			close();
			return;
		}

		// Frame 1: Task snapshot
		Task initial = A2ACodec.toTask(data);
		sendFrame(initial);

		if (Job.isFinished(data)) {
			// Already terminal — emit one statusUpdate with final:true then close.
			TaskStatusUpdateEvent update = A2ACodec.toStatusUpdate(data);
			sendFrame(update);
			close();
			return;
		}

		// Register listener for future transitions
		engine.jobs().addJobUpdateListener(listener);
	}

	private void onJobUpdate(Job job) {
		if (closed.get()) return;
		if (!taskId.equals(job.getID())) return;

		AMap<AString, ACell> data = job.getData();
		try {
			TaskStatusUpdateEvent update = A2ACodec.toStatusUpdate(data);
			sendFrame(update);
			if (update.isFinal()) {
				close();
			}
		} catch (Exception e) {
			log.warn("A2A SSE frame send failed, closing session: {}", e.getMessage());
			close();
		}
	}

	/**
	 * Wrap a StreamResponse payload in a JSON-RPC envelope and emit as SSE.
	 * gson's type-hierarchy adapter serialises the concrete payload with its
	 * discriminator ({@code "task"}, {@code "statusUpdate"}, etc.) already.
	 */
	private void sendFrame(Object payload) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", rpcRequestId);
		envelope.put("result", payload);
		String json = JsonUtil.OBJECT_MAPPER.toJson(envelope);
		sseClient.sendEvent(json);
	}

	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		engine.jobs().removeJobUpdateListener(listener);
		try {
			sseClient.close();
		} catch (Exception ignored) {
			// peer already gone
		}
	}
}
