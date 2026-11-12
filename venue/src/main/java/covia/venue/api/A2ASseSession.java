package covia.venue.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import covia.exception.FaultException;
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

	/** What failed, when the venue fails streaming an A2A task: the task's id follows. */
	public static final String STREAM = "A2A stream for task %s";

	private final SseClient sseClient;
	private final Engine engine;
	private final Blob taskId;
	private final Object rpcRequestId;
	private final RequestContext rctx;
	private final AString owner;

	private final Consumer<Job> listener = this::onJobUpdate;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private volatile Job subscribedJob;

	public A2ASseSession(SseClient sseClient, Engine engine, Blob taskId,
			Object rpcRequestId, RequestContext rctx) {
		this(sseClient, engine, taskId, rpcRequestId, rctx, null);
	}

	public A2ASseSession(SseClient sseClient, Engine engine, Blob taskId,
			Object rpcRequestId, RequestContext rctx, AString owner) {
		this.owner = owner;
		this.sseClient = sseClient;
		this.engine = engine;
		this.taskId = taskId;
		this.rpcRequestId = rpcRequestId;
		this.rctx = rctx;
	}

	/**
	 * Attach the listener and emit the initial Task frame. Returns immediately
	 * — subsequent frames are driven by Job updates.
	 *
	 * <p>If the Job is already in a terminal state, emits the initial Task
	 * plus one {@code final:true} statusUpdate, then closes.</p>
	 */
	public synchronized void start() {
		// Javalin completes an SSE response when the handler returns unless the
		// client is explicitly kept alive. Without this, subscribers receive the
		// initial Task snapshot but never any later Job transition (#305).
		sseClient.keepAlive();

		// Disconnect hook before anything else — Javalin closes idle SSE
		// connections after a timeout and the peer may abort at any time.
		sseClient.onClose(this::close);

		try {
			attach();
		} catch (Exception e) {
			fail(e);
		}
	}

	/** Sends the initial frames and, unless the Job has finished, subscribes to it. */
	private void attach() {
		// Access-control check against the caller via the RequestContext path;
		// gives us an authoritative snapshot for the initial frame.
		AMap<AString, ACell> data = engine.jobs().getJobData(taskId, rctx, owner);
		if (data == null) {
			close();
			return;
		}

		Job job = engine.jobs().getJob(taskId);
		if (job == null) {
			// The Job may have completed and been evicted between the authorised
			// lookup above and this active-cache lookup. Re-read the durable record
			// so the stream cannot finish on a stale non-terminal snapshot.
			data = engine.jobs().getJobData(taskId, rctx, owner);
			if (data == null) {
				close();
				return;
			}
			// Job already evicted (terminal). Emit snapshot + final frame and close.
			sendFrame(A2ACodec.toTask(data));
			if (Job.isFinished(data)) {
				sendFrame(A2ACodec.toStatusUpdate(data));
			}
			close();
			return;
		}

		// Subscribe before taking the emitted snapshot. start() and
		// onJobUpdate() share the same monitor: an update that commits in this
		// window either appears in the snapshot below or waits and is emitted as
		// the next status frame. Fast completion can therefore never be lost.
		this.subscribedJob = job;
		job.subscribe(listener);
		data = engine.jobs().getJobData(taskId, rctx, owner);
		if (data == null) { close(); return; }

		// Frame 1: Task snapshot
		sendFrame(A2ACodec.toTask(data));

		if (Job.isFinished(data)) {
			sendFrame(A2ACodec.toStatusUpdate(data));
			close();
		}
	}

	private synchronized void onJobUpdate(Job job) {
		if (closed.get()) return;
		try {
			AMap<AString, ACell> data = engine.jobs().getJobData(taskId, rctx, owner);
			if (data == null) { close(); return; }
			TaskStatusUpdateEvent update = A2ACodec.toStatusUpdate(data);
			sendFrame(update);
			if (update.isFinal()) {
				close();
			}
		} catch (Exception e) {
			fail(e);
		}
	}

	/**
	 * Ends the stream on a failure: one JSON-RPC {@code INTERNAL} error frame
	 * carrying its reason, or the venue's report of a fault
	 * ({@link FaultException#within}), never an unexpected failure's text,
	 * which is logged at ERROR. Sending never fails for a peer that has gone
	 * (the emitter closes the client), so what reaches here is the venue's.
	 */
	private void fail(Exception e) {
		Map<String, Object> error = new LinkedHashMap<>();
		if (e instanceof covia.exception.AuthException) {
			error.put("code", A2AErrorCodes.TASK_NOT_FOUND.code());
			error.put("message", covia.venue.JobManager.JOB_ACCESS_DENIED + taskId.toHexString());
			sendEnvelope("error", error);
			close();
			return;
		}
		error.put("code", A2AErrorCodes.INTERNAL.code());
		error.put("message", FaultException.within(
			STREAM.formatted(taskId.toHexString()), FaultException.VENUE, e).getMessage());
		sendEnvelope("error", error);
		close();
	}

	/**
	 * Wrap a StreamResponse payload in a JSON-RPC envelope and emit as SSE.
	 * gson's type-hierarchy adapter serialises the concrete payload with its
	 * discriminator ({@code "task"}, {@code "statusUpdate"}, etc.) already.
	 */
	private void sendFrame(StreamingEventKind payload) {
		// The 1.2 SDK deliberately registers its streaming union adapter for
		// the StreamingEventKind interface, not each concrete record. Supplying
		// the declared type preserves the protocol's {task: ...},
		// {statusUpdate: ...} discriminator wrapper inside result.
		sendEnvelope("result", JsonUtil.OBJECT_MAPPER.toJsonTree(payload, StreamingEventKind.class));
	}

	/** Emits one JSON-RPC response for this request, its {@code result} or {@code error} member set. */
	private void sendEnvelope(String member, Object value) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", rpcRequestId);
		envelope.put(member, value);
		sseClient.sendEvent(JsonUtil.OBJECT_MAPPER.toJson(envelope));
	}

	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		Job job = this.subscribedJob;
		if (job != null) job.unsubscribe(listener);
		sseClient.close();
	}
}
