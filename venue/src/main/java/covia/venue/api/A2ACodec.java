package covia.venue.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Status;

/**
 * Stateless conversion between Covia's ACell world and the A2A spec POJOs.
 *
 * <p>Covia stays ACell-native internally (Job data, lattice records, etc).
 * A2A wire traffic is spec POJOs + gson. This class is the single membrane.
 * Every method is a pure function; test in isolation without Javalin or
 * any engine state.</p>
 */
public class A2ACodec {

	/** Internal record field name for an A2A message role. */
	public static final AString ROLE = Strings.intern("role");
	/** Internal record field name for an A2A message parts list. */
	public static final AString PARTS = Strings.intern("parts");
	/** Internal record field name for an A2A contextId (groups related tasks). */
	public static final AString CONTEXT_ID = Strings.intern("contextId");
	/** Internal record field name for an A2A messageId. */
	public static final AString MESSAGE_ID = Strings.intern("messageId");
	/** Internal record field name for an artifactId. */
	public static final AString ARTIFACT_ID = Strings.intern("artifactId");

	private static final AString ROLE_USER = Strings.intern("user");
	private static final AString ROLE_AGENT = Strings.intern("agent");

	private A2ACodec() {}

	// ==================== TaskState mapping ====================

	/**
	 * Reverse mapping: A2A TaskState → Covia Status. Used by the outbound A2A
	 * adapter when mirroring a remote Task's lifecycle onto a local Job.
	 */
	public static AString fromTaskState(TaskState state) {
		if (state == null) return Status.PENDING;
		return switch (state) {
			case TASK_STATE_SUBMITTED      -> Status.PENDING;
			case TASK_STATE_WORKING        -> Status.STARTED;
			case TASK_STATE_COMPLETED      -> Status.COMPLETE;
			case TASK_STATE_FAILED         -> Status.FAILED;
			case TASK_STATE_CANCELED       -> Status.CANCELLED;
			case TASK_STATE_REJECTED       -> Status.REJECTED;
			case TASK_STATE_INPUT_REQUIRED -> Status.INPUT_REQUIRED;
			case TASK_STATE_AUTH_REQUIRED  -> Status.AUTH_REQUIRED;
			case UNRECOGNIZED              -> Status.FAILED;
		};
	}

	/**
	 * Map a Covia Status string to an A2A TaskState.
	 *
	 * <p>Covia PAUSED has no A2A equivalent; we collapse it to WORKING so the
	 * client sees the task as still in progress. TIMEOUT folds into FAILED.</p>
	 */
	public static TaskState toTaskState(AString coviaStatus) {
		if (coviaStatus == null) return TaskState.TASK_STATE_SUBMITTED;
		if (Status.PENDING.equals(coviaStatus)) return TaskState.TASK_STATE_SUBMITTED;
		if (Status.STARTED.equals(coviaStatus)) return TaskState.TASK_STATE_WORKING;
		if (Status.COMPLETE.equals(coviaStatus)) return TaskState.TASK_STATE_COMPLETED;
		if (Status.FAILED.equals(coviaStatus)) return TaskState.TASK_STATE_FAILED;
		if (Status.TIMEOUT.equals(coviaStatus)) return TaskState.TASK_STATE_FAILED;
		if (Status.CANCELLED.equals(coviaStatus)) return TaskState.TASK_STATE_CANCELED;
		if (Status.REJECTED.equals(coviaStatus)) return TaskState.TASK_STATE_REJECTED;
		if (Status.INPUT_REQUIRED.equals(coviaStatus)) return TaskState.TASK_STATE_INPUT_REQUIRED;
		if (Status.AUTH_REQUIRED.equals(coviaStatus)) return TaskState.TASK_STATE_AUTH_REQUIRED;
		if (Status.PAUSED.equals(coviaStatus)) return TaskState.TASK_STATE_WORKING;
		return TaskState.UNRECOGNIZED;
	}

	// ==================== Task construction ====================

	/**
	 * Build an A2A Task from a Covia Job data record.
	 *
	 * <p>Task.id = hex-encoded Job ID. contextId falls back to the Job ID if
	 * unset (a single-task conversation). Artifacts = output wrapped as one
	 * Artifact with a single Part. History = persisted message log.</p>
	 */
	public static Task toTask(AMap<AString, ACell> jobData) {
		if (jobData == null) throw new IllegalArgumentException("jobData must not be null");

		Blob jobId = extractJobId(jobData);
		String id = jobId.toHexString();
		String contextId = extractContextId(jobData, id);
		TaskStatus status = toTaskStatus(jobData);
		List<Artifact> artifacts = toArtifacts(jobData);
		List<Message> history = toHistory(jobData, contextId, id);

		return new Task(id, contextId, status, artifacts, history, null);
	}

	/** Build a TaskStatus from a Job data record. */
	public static TaskStatus toTaskStatus(AMap<AString, ACell> jobData) {
		TaskState state = toTaskState(RT.ensureString(jobData.get(Fields.STATUS)));
		OffsetDateTime ts = extractTimestamp(jobData);
		return new TaskStatus(state, null, ts);
	}

	/**
	 * Wrap a Job's output as a single A2A Artifact. Returns empty list when the
	 * job has no output (i.e. not in a terminal state that produced one).
	 */
	private static List<Artifact> toArtifacts(AMap<AString, ACell> jobData) {
		ACell output = jobData.get(Fields.OUTPUT);
		if (output == null) return List.of();
		Part<?> part = toPart(output);
		Artifact artifact = Artifact.builder()
				.artifactId(extractJobId(jobData).toHexString())
				.parts(List.of(part))
				.build();
		return List.of(artifact);
	}

	/**
	 * Build the message history for a Task. Each entry in the persistent
	 * {@link Fields#HISTORY} slot is a map with role + parts; we turn each
	 * into an A2A Message.
	 */
	@SuppressWarnings("unchecked")
	private static List<Message> toHistory(AMap<AString, ACell> jobData, String contextId, String taskId) {
		ACell h = jobData.get(Fields.HISTORY);
		if (!(h instanceof AMap || h instanceof convex.core.data.AVector)) return List.of();
		if (!(h instanceof convex.core.data.AVector)) return List.of();
		convex.core.data.AVector<ACell> hist = (convex.core.data.AVector<ACell>) h;
		List<Message> out = new ArrayList<>((int) hist.count());
		for (long i = 0; i < hist.count(); i++) {
			ACell e = hist.get(i);
			if (!(e instanceof AMap)) continue;
			Message m = fromMessageRecord((AMap<AString, ACell>) e, contextId, taskId);
			if (m != null) out.add(m);
		}
		return out;
	}

	// ==================== Message handling ====================

	/**
	 * Convert an inbound A2A Message (from SendMessage) to a Covia history
	 * record. Preserves role, parts, messageId, and any referenceTaskIds.
	 * Role is not trusted — inbound messages are always stored as "user".
	 */
	public static AMap<AString, ACell> toMessageRecord(Message m, boolean trustRole) {
		AString role = trustRole ? roleToCell(m.role()) : ROLE_USER;
		AMap<AString, ACell> record = Maps.of(
				ROLE, role,
				PARTS, partsToCell(m.parts())
		);
		if (m.messageId() != null) {
			record = record.assoc(MESSAGE_ID, Strings.create(m.messageId()));
		}
		if (m.contextId() != null) {
			record = record.assoc(CONTEXT_ID, Strings.create(m.contextId()));
		}
		if (m.taskId() != null) {
			record = record.assoc(Fields.TASK_ID, Strings.create(m.taskId()));
		}
		return record;
	}

	/**
	 * Convert a stored message record to an A2A Message. The stored record
	 * shape is {role, parts, messageId?, contextId?, taskId?} — fall back to
	 * the Task's contextId/taskId if the record didn't carry them.
	 */
	@SuppressWarnings("unchecked")
	public static Message fromMessageRecord(AMap<AString, ACell> record,
			String defaultContextId, String defaultTaskId) {
		if (record == null) return null;
		Message.Role role = cellToRole(RT.ensureString(record.get(ROLE)));
		List<Part<?>> parts = cellToParts(record.get(PARTS));
		if (parts.isEmpty()) return null;

		String messageId = asString(record.get(MESSAGE_ID));
		if (messageId == null) messageId = java.util.UUID.randomUUID().toString();

		String contextId = asString(record.get(CONTEXT_ID));
		if (contextId == null) contextId = defaultContextId;
		String taskId = asString(record.get(Fields.TASK_ID));
		if (taskId == null) taskId = defaultTaskId;

		return Message.builder()
				.role(role)
				.parts(parts)
				.messageId(messageId)
				.contextId(contextId)
				.taskId(taskId)
				.build();
	}

	// ==================== Part handling ====================

	/**
	 * Wrap an arbitrary ACell as an A2A Part. Strings become TextPart;
	 * maps and everything else become DataPart (with the cell JSON-ified
	 * to a plain Java object for gson). FilePart construction is not
	 * synthesised from bare cells — callers produce FileParts explicitly
	 * when they have URL/bytes metadata.
	 */
	public static Part<?> toPart(ACell cell) {
		if (cell instanceof AString s) {
			return new TextPart(s.toString(), null);
		}
		Object jvm = JSON.json(cell);
		return new DataPart(jvm, null);
	}

	/**
	 * Convert an A2A Part to an ACell suitable for storage or return as
	 * Job input/output. TextPart → AString; DataPart → parsed JSON ACell;
	 * FilePart → map with url/bytes/filename/mediaType fields.
	 */
	public static ACell fromPart(Part<?> p) {
		if (p instanceof TextPart t) {
			return Strings.create(t.text());
		}
		if (p instanceof DataPart d) {
			Object data = d.data();
			if (data == null) return null;
			return JSON.parse(JSON.toString(data));
		}
		if (p instanceof FilePart f) {
			return fileContentToCell(f.file());
		}
		return null;
	}

	private static ACell fileContentToCell(Object fc) {
		if (fc instanceof FileWithUri uri) {
			AMap<AString, ACell> m = Maps.of(
					Fields.URL, Strings.create(uri.uri())
			);
			if (uri.name() != null) m = m.assoc(Fields.FILE_NAME, Strings.create(uri.name()));
			if (uri.mimeType() != null) m = m.assoc(Fields.CONTENT_TYPE, Strings.create(uri.mimeType()));
			return m;
		}
		if (fc instanceof FileWithBytes bytes) {
			AMap<AString, ACell> m = Maps.of(
					Fields.CONTENT, Strings.create(bytes.bytes())
			);
			if (bytes.name() != null) m = m.assoc(Fields.FILE_NAME, Strings.create(bytes.name()));
			if (bytes.mimeType() != null) m = m.assoc(Fields.CONTENT_TYPE, Strings.create(bytes.mimeType()));
			return m;
		}
		return null;
	}

	private static ACell partsToCell(List<Part<?>> parts) {
		if (parts == null || parts.isEmpty()) return Vectors.empty();
		ArrayList<ACell> items = new ArrayList<>(parts.size());
		for (Part<?> p : parts) {
			// Store as a typed descriptor so round-trip preserves kind.
			if (p instanceof TextPart t) {
				items.add(Maps.of(Fields.TYPE, Strings.intern("text"),
						Fields.TEXT, Strings.create(t.text())));
			} else if (p instanceof DataPart d) {
				Object data = d.data();
				ACell stored = data == null ? null : JSON.parse(JSON.toString(data));
				items.add(Maps.of(Fields.TYPE, Strings.intern("data"),
						Strings.intern("data"), stored));
			} else if (p instanceof FilePart f) {
				items.add(Maps.of(Fields.TYPE, Strings.intern("file"),
						Strings.intern("file"), fileContentToCell(f.file())));
			}
		}
		return Vectors.create(items);
	}

	@SuppressWarnings("unchecked")
	private static List<Part<?>> cellToParts(ACell partsCell) {
		if (!(partsCell instanceof convex.core.data.AVector)) return List.of();
		convex.core.data.AVector<ACell> v = (convex.core.data.AVector<ACell>) partsCell;
		List<Part<?>> out = new ArrayList<>((int) v.count());
		for (long i = 0; i < v.count(); i++) {
			ACell e = v.get(i);
			if (!(e instanceof AMap)) continue;
			AMap<AString, ACell> entry = (AMap<AString, ACell>) e;
			AString type = RT.ensureString(entry.get(Fields.TYPE));
			if (type == null) continue;
			String t = type.toString();
			if ("text".equals(t)) {
				AString text = RT.ensureString(entry.get(Fields.TEXT));
				if (text != null) out.add(new TextPart(text.toString(), null));
			} else if ("data".equals(t)) {
				ACell data = entry.get(Strings.intern("data"));
				Object jvm = data == null ? null : JSON.json(data);
				out.add(new DataPart(jvm, null));
			}
			// file round-trip intentionally one-way for now — Parts carrying
			// files are constructed by the server from asset metadata, not
			// reconstructed from stored records.
		}
		return out;
	}

	// ==================== Role mapping ====================

	private static Message.Role cellToRole(AString role) {
		if (role == null) return Message.Role.ROLE_USER;
		String s = role.toString();
		if ("agent".equalsIgnoreCase(s)) return Message.Role.ROLE_AGENT;
		if ("user".equalsIgnoreCase(s)) return Message.Role.ROLE_USER;
		return Message.Role.ROLE_UNSPECIFIED;
	}

	private static AString roleToCell(Message.Role role) {
		if (role == null) return ROLE_USER;
		return switch (role) {
			case ROLE_AGENT -> ROLE_AGENT;
			case ROLE_USER -> ROLE_USER;
			case ROLE_UNSPECIFIED -> ROLE_USER;
		};
	}

	// ==================== Extractors ====================

	private static Blob extractJobId(AMap<AString, ACell> jobData) {
		ACell id = jobData.get(Fields.ID);
		if (id instanceof Blob b) return b;
		if (id instanceof convex.core.data.ABlob b) return b.toFlatBlob();
		if (id instanceof AString s) return Blob.parse(s.toString());
		throw new IllegalArgumentException("Job record has no id");
	}

	private static String extractContextId(AMap<AString, ACell> jobData, String fallback) {
		AString ctx = RT.ensureString(jobData.get(CONTEXT_ID));
		return ctx != null ? ctx.toString() : fallback;
	}

	private static OffsetDateTime extractTimestamp(AMap<AString, ACell> jobData) {
		ACell u = jobData.get(Fields.UPDATED);
		if (u instanceof CVMLong l) {
			return OffsetDateTime.ofInstant(Instant.ofEpochMilli(l.longValue()), ZoneOffset.UTC);
		}
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	private static String asString(ACell cell) {
		AString s = RT.ensureString(cell);
		return s == null ? null : s.toString();
	}

	// ==================== Inbound parsing helpers ====================

	/**
	 * Extract the taskId from the `message` field of a SendMessage params map.
	 * Returns null if the message has no taskId (i.e. this is a new task).
	 */
	@SuppressWarnings("unchecked")
	public static Blob extractTaskId(Map<String, Object> messageParams) {
		if (messageParams == null) return null;
		Object mObj = messageParams.get("message");
		if (!(mObj instanceof Map)) return null;
		Object taskId = ((Map<String, Object>) mObj).get("taskId");
		if (taskId == null) return null;
		try {
			return Blob.parse(taskId.toString());
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid taskId: " + taskId);
		}
	}
}
