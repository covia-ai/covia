package covia.adapter.webhook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.AdapterWorkspace;
import covia.venue.Engine;

/**
 * Durable receipt/deduplication primitive for authenticated provider events.
 * Scope one root to a provider installation/binding. Use the provider event ID,
 * not a delivery attempt/envelope ID. Works for HTTP and socket transports.
 *
 * <p>Accept and claim flush before returning. Pending receipts can be scheduled
 * after restart; STARTED receipts require provider-specific reconciliation and
 * are never automatically replayed. Completing an external send and recording
 * completion cannot be atomic: this does not promise exactly-once effects.
 * Local session delivery can instead use {@link #deliver} on the same Users
 * transaction as input admission; its COMPLETE means delivered, not replied.
 * A delivery that cannot commit is retried only briefly: {@link #attempt}
 * records each attempt before it runs, and {@link #fail} ends the receipt as
 * FAILED, which is terminal — never delivered, claimed, retried or pruned.
 * Durability is that of the venue's configured store (memory/temp are ephemeral).
 * Claims coordinate a single Engine, not independent replicated venue processes.</p>
 */
public final class WebhookInbox {
	public static final AString PENDING = Strings.intern("PENDING");
	public static final AString STARTED = Strings.intern("STARTED");
	public static final AString COMPLETE = Strings.intern("COMPLETE");
	public static final AString FAILED = Strings.intern("FAILED");
	public static final AString RECEIVED_AT = Strings.intern("receivedAt");
	public static final AString COMPLETED_AT = Strings.intern("completedAt");
	public static final AString ATTEMPTS = Strings.intern("attempts");
	public static final AString FIRST_ATTEMPT_AT = Strings.intern("firstAttemptAt");
	public static final AString ATTEMPTED_AT = Strings.intern("attemptedAt");
	public static final AString LAST_ERROR = Strings.intern("lastError");
	public static final AString FAILED_AT = Strings.intern("failedAt");
	public static final String EVENT_REQUIRED = "Webhook event ID is required";
	public static final String NOT_PENDING = "Session delivery receipt must be pending";
	public static final String NOT_STARTED = "Webhook receipt must be started before completion";
	public static final String UNKNOWN_EVENT = "Unknown webhook receipt";
	public static final String INVALID_RECEIPT = "Invalid webhook receipt";
	private final Engine engine;
	private final AdapterWorkspace workspace;
	private final String root;

	public WebhookInbox(Engine engine, AdapterWorkspace workspace, String root) {
		this.engine = Objects.requireNonNull(engine);
		this.workspace = Objects.requireNonNull(workspace);
		workspace.path(root + "/check");
		this.root = root;
	}

	/** Persist before acknowledging. Duplicate deliveries return the original receipt. */
	public AMap<AString, ACell> accept(String eventId, ACell input) {
		Objects.requireNonNull(input);
		synchronized (engine) {
			AMap<AString, ACell> record = get(eventId);
			if (record == null) {
				record = Maps.of(Fields.ID, Strings.create(eventId), Fields.INPUT, input,
					Fields.STATUS, PENDING, RECEIVED_AT, CVMLong.create(System.currentTimeMillis()));
				workspace.write(path(eventId), record);
			}
			// Flush duplicates too: the original write may have survived in memory
			// after a failed durability barrier, before the provider retried.
			engine.flush();
			return record;
		}
	}

	/** Claim once before starting any Job or send. False means already claimed/completed. */
	public boolean claim(String eventId) {
		synchronized (engine) {
			AMap<AString, ACell> record = require(eventId);
			if (!PENDING.equals(record.get(Fields.STATUS))) return false;
			workspace.write(path(eventId), record.assoc(Fields.STATUS, STARTED));
			engine.flush();
			return true;
		}
	}

	/** Mark complete only after processing and any required outbound response. */
	public void complete(String eventId) {
		synchronized (engine) {
			AMap<AString, ACell> record = require(eventId);
			if (COMPLETE.equals(record.get(Fields.STATUS))) { engine.flush(); return; }
			if (!STARTED.equals(record.get(Fields.STATUS))) throw new IllegalStateException(NOT_STARTED);
			workspace.write(path(eventId), record.assoc(Fields.STATUS, COMPLETE)
				.assoc(COMPLETED_AT, CVMLong.create(System.currentTimeMillis())));
			engine.flush();
		}
	}

	/** Complete a local session handoff inside its Users transaction. The input
	 * and this receipt commit together; replies do not participate. No flush here. */
	public boolean deliver(covia.venue.Users draft, String eventId, AString sessionId, AString jobId) {
		AdapterWorkspace state = workspace.withUsers(draft);
		AMap<AString, ACell> record = RT.ensureMap(state.read(path(eventId)));
		if (record == null) throw new IllegalArgumentException(UNKNOWN_EVENT);
		if (COMPLETE.equals(record.get(Fields.STATUS))) return false;
		if (!PENDING.equals(record.get(Fields.STATUS))) throw new IllegalStateException(NOT_PENDING);
		state.write(path(eventId), record.assoc(Fields.STATUS, COMPLETE)
			.assoc(Fields.SESSION_ID, sessionId).assoc(Fields.JOB_ID, jobId)
			.assoc(COMPLETED_AT, CVMLong.create(System.currentTimeMillis())));
		return true;
	}

	/** Record a delivery attempt on a PENDING receipt before it runs, so neither a
	 * restart nor a crash mid-attempt resets its retry window. Returns the updated
	 * receipt, or null when it is no longer PENDING. */
	public AMap<AString, ACell> attempt(String eventId, long now) {
		synchronized (engine) {
			AMap<AString, ACell> record = require(eventId);
			if (!PENDING.equals(record.get(Fields.STATUS))) return null;
			long attempts = record.get(ATTEMPTS) instanceof CVMLong n ? n.longValue() : 0;
			record = record.assoc(ATTEMPTS, CVMLong.create(attempts + 1)).assoc(ATTEMPTED_AT, CVMLong.create(now));
			if (record.get(FIRST_ATTEMPT_AT) == null) record = record.assoc(FIRST_ATTEMPT_AT, CVMLong.create(now));
			workspace.write(path(eventId), record);
			engine.flush();
			return record;
		}
	}

	/** Record why the latest attempt on a PENDING receipt did not deliver it.
	 * Returns the updated receipt, or null when it is no longer PENDING. */
	public AMap<AString, ACell> attemptFailed(String eventId, String error) {
		synchronized (engine) {
			AMap<AString, ACell> record = require(eventId);
			if (!PENDING.equals(record.get(Fields.STATUS))) return null;
			record = record.assoc(LAST_ERROR, Strings.create(Objects.requireNonNull(error)));
			workspace.write(path(eventId), record);
			engine.flush();
			return record;
		}
	}

	/** End a PENDING receipt as FAILED, with a reason and optionally the error of the
	 * attempt that ended it. FAILED is terminal and is kept for the operator. Returns
	 * the updated receipt, or null when it is no longer PENDING. */
	public AMap<AString, ACell> fail(String eventId, String reason, String error, long now) {
		synchronized (engine) {
			AMap<AString, ACell> record = require(eventId);
			if (!PENDING.equals(record.get(Fields.STATUS))) return null;
			record = record.assoc(Fields.STATUS, FAILED).assoc(Fields.REASON, Strings.create(Objects.requireNonNull(reason)))
				.assoc(FAILED_AT, CVMLong.create(now));
			if (error != null) record = record.assoc(LAST_ERROR, Strings.create(error));
			workspace.write(path(eventId), record);
			engine.flush();
			return record;
		}
	}

	public AMap<AString, ACell> get(String eventId) {
		ACell value = workspace.read(path(eventId));
		// CVM castMap(nil) is an empty map, not Java null. Absence must remain
		// distinct from a receipt, otherwise a first delivery looks deduplicated.
		if (value == null) return null;
		if (!(value instanceof AMap<?, ?>)) throw new IllegalStateException(INVALID_RECEIPT);
		AMap<AString, ACell> record = RT.castMap(value);
		ACell status = record.get(Fields.STATUS);
		if (!Strings.create(eventId).equals(record.get(Fields.ID)) || record.get(Fields.INPUT) == null
				|| !(PENDING.equals(status) || STARTED.equals(status) || COMPLETE.equals(status) || FAILED.equals(status))) {
			throw new IllegalStateException(INVALID_RECEIPT);
		}
		return record;
	}

	/** Stable snapshot for startup recovery or reconciliation; no execution occurs here. */
	public List<AMap<AString, ACell>> records(AString status) {
		List<AMap<AString, ACell>> result = new ArrayList<>();
		AMap<AString, ACell> entries = RT.castMap(workspace.read(root));
		if (entries != null) for (var entry : entries.entrySet()) {
			AMap<AString, ACell> record = RT.castMap(entry.getValue());
			if (record != null && status.equals(record.get(Fields.STATUS))) result.add(record);
		}
		return List.copyOf(result);
	}

	/** Provider chooses a retention horizon longer than its retry/replay window. */
	public int pruneCompleted(long beforeMillis) {
		synchronized (engine) {
			int removed = 0;
			for (AMap<AString, ACell> record : records(COMPLETE)) {
				if (record.get(COMPLETED_AT) instanceof CVMLong time && time.longValue() < beforeMillis
						&& record.get(Fields.ID) instanceof AString id) {
					if (workspace.delete(path(id.toString()))) removed++;
				}
			}
			if (removed > 0) engine.flush();
			return removed;
		}
	}

	private AMap<AString, ACell> require(String eventId) {
		AMap<AString, ACell> record = get(eventId);
		if (record == null) throw new IllegalArgumentException(UNKNOWN_EVENT);
		return record;
	}

	private String path(String id) {
		if (id == null || id.isEmpty()) throw new IllegalArgumentException(EVENT_REQUIRED);
		return root + "/" + Strings.create(id).getHash().toHexString();
	}
}
