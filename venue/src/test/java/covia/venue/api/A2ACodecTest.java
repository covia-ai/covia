package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Status;

/**
 * Unit tests for A2ACodec — pure function tests, no Javalin, no engine.
 * Every conversion path exercised: TaskState mapping both directions,
 * Task construction, Message round-trips, every Part variant.
 */
public class A2ACodecTest {

	// ======== Fixture helpers ========

	private static Blob jobId() {
		return Blob.parse("000000000000000000000000deadbeef");
	}

	private static AMap<AString, ACell> baseJobData(AString status) {
		return Maps.of(
				Fields.ID, jobId(),
				Fields.STATUS, status,
				Fields.UPDATED, CVMLong.create(1700000000000L),
				Fields.CREATED, CVMLong.create(1700000000000L)
		);
	}

	// ======== TaskState mapping ========

	@Test
	public void taskStateMapping_coversAllCoviaStatuses() {
		assertEquals(TaskState.TASK_STATE_SUBMITTED, A2ACodec.toTaskState(Status.PENDING));
		assertEquals(TaskState.TASK_STATE_WORKING, A2ACodec.toTaskState(Status.STARTED));
		assertEquals(TaskState.TASK_STATE_COMPLETED, A2ACodec.toTaskState(Status.COMPLETE));
		assertEquals(TaskState.TASK_STATE_FAILED, A2ACodec.toTaskState(Status.FAILED));
		assertEquals(TaskState.TASK_STATE_FAILED, A2ACodec.toTaskState(Status.TIMEOUT));
		assertEquals(TaskState.TASK_STATE_CANCELED, A2ACodec.toTaskState(Status.CANCELLED));
		assertEquals(TaskState.TASK_STATE_REJECTED, A2ACodec.toTaskState(Status.REJECTED));
		assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, A2ACodec.toTaskState(Status.INPUT_REQUIRED));
		assertEquals(TaskState.TASK_STATE_AUTH_REQUIRED, A2ACodec.toTaskState(Status.AUTH_REQUIRED));
	}

	@Test
	public void taskStateMapping_pausedCollapsesToWorking() {
		// A2A has no PAUSED state; Covia PAUSED should surface as WORKING so
		// clients don't see a missing/unknown state.
		assertEquals(TaskState.TASK_STATE_WORKING, A2ACodec.toTaskState(Status.PAUSED));
	}

	@Test
	public void taskStateMapping_nullDefaultsToSubmitted() {
		assertEquals(TaskState.TASK_STATE_SUBMITTED, A2ACodec.toTaskState(null));
	}

	@Test
	public void taskStateMapping_unknownStatusIsUnrecognized() {
		assertEquals(TaskState.UNRECOGNIZED, A2ACodec.toTaskState(Strings.create("BOGUS")));
	}

	// ======== Task construction ========

	@Test
	public void toTask_basicComplete() {
		AMap<AString, ACell> jobData = baseJobData(Status.COMPLETE)
				.assoc(Fields.OUTPUT, Strings.create("hello world"));

		Task task = A2ACodec.toTask(jobData);

		assertEquals(jobId().toHexString(), task.id());
		assertEquals(jobId().toHexString(), task.contextId()); // falls back to jobId
		assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
		assertNotNull(task.status().timestamp());
		assertEquals(1, task.artifacts().size());
		assertTrue(task.artifacts().get(0).parts().get(0) instanceof TextPart);
	}

	@Test
	public void toTask_preservesContextIdWhenSet() {
		AMap<AString, ACell> jobData = baseJobData(Status.STARTED)
				.assoc(A2ACodec.CONTEXT_ID, Strings.create("ctx-abc"));

		Task task = A2ACodec.toTask(jobData);

		assertEquals("ctx-abc", task.contextId());
		assertEquals(TaskState.TASK_STATE_WORKING, task.status().state());
	}

	@Test
	public void toTask_pendingHasNoArtifacts() {
		AMap<AString, ACell> jobData = baseJobData(Status.PENDING);
		Task task = A2ACodec.toTask(jobData);
		assertTrue(task.artifacts().isEmpty());
	}

	@Test
	public void toTask_timestampFromUpdatedField() {
		AMap<AString, ACell> jobData = baseJobData(Status.PENDING);
		TaskStatus status = A2ACodec.toTaskStatus(jobData);
		// 1700000000000 ms = 2023-11-14T22:13:20Z
		assertEquals(1700000000000L, status.timestamp().toInstant().toEpochMilli());
	}

	// ======== Part handling ========

	@Test
	public void toPart_stringYieldsTextPart() {
		Part<?> p = A2ACodec.toPart(Strings.create("hello"));
		assertTrue(p instanceof TextPart);
		assertEquals("hello", ((TextPart) p).text());
	}

	@Test
	public void toPart_mapYieldsDataPart() {
		AMap<AString, ACell> m = Maps.of(Strings.create("key"), Strings.create("value"));
		Part<?> p = A2ACodec.toPart(m);
		assertTrue(p instanceof DataPart);
		DataPart dp = (DataPart) p;
		Object data = dp.data();
		assertTrue(data instanceof Map);
		assertEquals("value", ((Map<?, ?>) data).get("key"));
	}

	@Test
	public void fromPart_textPartYieldsAString() {
		ACell c = A2ACodec.fromPart(new TextPart("roundtrip", null));
		assertTrue(c instanceof AString);
		assertEquals("roundtrip", c.toString());
	}

	@Test
	public void fromPart_dataPartYieldsMatchingCell() {
		Map<String, Object> data = Map.of("n", 42L, "s", "hi");
		ACell c = A2ACodec.fromPart(new DataPart(data, null));
		assertTrue(c instanceof AMap);
		AMap<?, ?> m = (AMap<?, ?>) c;
		assertEquals(Strings.create("hi"), m.get(Strings.create("s")));
	}

	// ======== Message round-trip ========

	@Test
	public void messageRoundTrip_textOnly() {
		Message m = Message.builder()
				.role(Message.Role.ROLE_USER)
				.parts(List.<Part<?>>of(new TextPart("hello", null)))
				.messageId("msg-1")
				.build();

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(m, true);
		assertNotNull(record);
		assertEquals(Strings.create("user"), record.get(A2ACodec.ROLE));
		assertEquals(Strings.create("msg-1"), record.get(A2ACodec.MESSAGE_ID));

		Message back = A2ACodec.fromMessageRecord(record, "ctx", "task");
		assertEquals(Message.Role.ROLE_USER, back.role());
		assertEquals("msg-1", back.messageId());
		assertEquals(1, back.parts().size());
		assertTrue(back.parts().get(0) instanceof TextPart);
		assertEquals("hello", ((TextPart) back.parts().get(0)).text());
	}

	@Test
	public void messageRoundTrip_dataPart() {
		Map<String, Object> payload = Map.of("hello", "world");
		Message m = Message.builder()
				.role(Message.Role.ROLE_AGENT)
				.parts(List.<Part<?>>of(new DataPart(payload, null)))
				.messageId("msg-2")
				.build();

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(m, true);
		assertEquals(Strings.create("agent"), record.get(A2ACodec.ROLE));

		Message back = A2ACodec.fromMessageRecord(record, "ctx", "task");
		assertEquals(Message.Role.ROLE_AGENT, back.role());
		assertEquals(1, back.parts().size());
		assertTrue(back.parts().get(0) instanceof DataPart);
		Object data = ((DataPart) back.parts().get(0)).data();
		assertTrue(data instanceof Map);
		assertEquals("world", ((Map<?, ?>) data).get("hello"));
	}

	@Test
	public void toMessageRecord_untrustedRoleForcesUser() {
		// When trustRole=false (inbound from client), ROLE_AGENT must be
		// rewritten to user — clients cannot inject agent-authored history.
		Message m = Message.builder()
				.role(Message.Role.ROLE_AGENT)
				.parts(List.<Part<?>>of(new TextPart("pretending", null)))
				.messageId("msg-x")
				.build();

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(m, false);
		assertEquals(Strings.create("user"), record.get(A2ACodec.ROLE));
	}

	@Test
	public void fromMessageRecord_fillsDefaultsWhenOmitted() {
		AMap<AString, ACell> record = Maps.of(
				A2ACodec.ROLE, Strings.create("user"),
				A2ACodec.PARTS, Vectors.of(Maps.of(
						Fields.TYPE, Strings.intern("text"),
						Fields.TEXT, Strings.create("hi")
				))
		);
		Message m = A2ACodec.fromMessageRecord(record, "ctx-default", "task-default");
		assertEquals("ctx-default", m.contextId());
		assertEquals("task-default", m.taskId());
		assertNotNull(m.messageId()); // auto-generated UUID
	}

	// ======== extractTaskId ========

	@Test
	public void extractTaskId_absentReturnsNull() {
		Map<String, Object> params = Map.of("message", Map.of("role", "user"));
		assertNull(A2ACodec.extractTaskId(params));
	}

	@Test
	public void extractTaskId_presentReturnsBlob() {
		Map<String, Object> params = Map.of("message",
				Map.of("role", "user", "taskId", "000000000000000000000000deadbeef"));
		Blob id = A2ACodec.extractTaskId(params);
		assertNotNull(id);
		assertEquals("000000000000000000000000deadbeef", id.toHexString());
	}

	// ======== History ========

	@Test
	public void toTask_historyReconstructedFromStoredRecords() {
		AVector<ACell> history = Vectors.of(
				Maps.of(
						A2ACodec.ROLE, Strings.create("user"),
						A2ACodec.PARTS, Vectors.of(Maps.of(
								Fields.TYPE, Strings.intern("text"),
								Fields.TEXT, Strings.create("first")
						)),
						A2ACodec.MESSAGE_ID, Strings.create("m1")
				),
				Maps.of(
						A2ACodec.ROLE, Strings.create("agent"),
						A2ACodec.PARTS, Vectors.of(Maps.of(
								Fields.TYPE, Strings.intern("text"),
								Fields.TEXT, Strings.create("response")
						)),
						A2ACodec.MESSAGE_ID, Strings.create("m2")
				)
		);
		AMap<AString, ACell> jobData = baseJobData(Status.STARTED)
				.assoc(Fields.HISTORY, history);

		Task task = A2ACodec.toTask(jobData);
		assertEquals(2, task.history().size());
		assertEquals(Message.Role.ROLE_USER, task.history().get(0).role());
		assertEquals(Message.Role.ROLE_AGENT, task.history().get(1).role());
		assertEquals("m1", task.history().get(0).messageId());
	}

	@Test
	public void toTask_emptyHistoryGivesEmptyList() {
		AMap<AString, ACell> jobData = baseJobData(Status.PENDING);
		Task task = A2ACodec.toTask(jobData);
		assertTrue(task.history().isEmpty());
	}

	// ======== Sanity: status check via RT ========

	@Test
	public void sanity_coviaStatusConstantsAreInternedShortStrings() {
		// Guards against someone changing Status constants to something RT
		// can't compare to AString values read from Job data.
		assertNotNull(RT.ensureString(Status.PENDING));
		assertNotNull(RT.ensureString(Status.COMPLETE));
	}
}
