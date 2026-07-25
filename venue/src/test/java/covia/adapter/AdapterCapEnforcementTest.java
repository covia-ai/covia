package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.auth.ucan.Capability;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CapabilityChecker;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Capability enforcement at the adapter layer — the drift-free, resource-pinned
 * checks that each resource adapter performs at its execution point.
 *
 * <p>Each op is invoked <b>directly</b> on its adapter ({@code invokeFuture}),
 * so these assertions isolate the adapter's own point-of-action gate: the right
 * resource and the right ability. Under the public read-only scope, mutations
 * are denied and owner-scoped reads are allowed — including DLFS, which is a
 * DID-scoped {@code <did>/dlfs/…} namespace covered by the caller's crud/read
 * grant like {@code /w/}. Only genuinely scheme-qualified {@code file://}
 * resources fall outside the owner-scoped grant, so those reads are denied (the
 * secure default for a public caller). A null scope (authenticated/internal)
 * is unrestricted — no cap denial on any op.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class AdapterCapEnforcementTest {

	private static Engine engine;
	private static final AString DID = Strings.create("did:key:zCapEnforceTest");
	private static RequestContext readOnly;     // public read-only scope
	private static RequestContext unrestricted; // null scope

	@BeforeAll
	public void setup() {
		engine = TestEngine.ENGINE;
		readOnly = RequestContext.of(DID).withCaps(CapabilityChecker.readOnlyScope(DID));
		unrestricted = RequestContext.of(DID); // no caps
	}

	/** Invoke an op directly on its adapter; return the cause-chain message
	 *  (empty if it completed without throwing). */
	private static String direct(String adapter, String op, AMap<AString, ACell> input, RequestContext ctx) {
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create(adapter + ":" + op)));
		try {
			engine.getAdapter(adapter).invokeFuture(ctx, meta, input).get(5, TimeUnit.SECONDS);
			return "";
		} catch (Exception e) {
			StringBuilder sb = new StringBuilder();
			for (Throwable c = e; c != null; c = c.getCause()) {
				if (c.getMessage() != null) sb.append(c.getMessage()).append(" | ");
			}
			return sb.toString();
		}
	}

	private static boolean capDenied(String msg) {
		return msg.contains("Capability denied");
	}

	private static AMap<AString, ACell> m(Object... kv) {
		AMap<AString, ACell> out = Maps.empty();
		for (int i = 0; i < kv.length; i += 2) {
			AString k = (kv[i] instanceof AString s) ? s : Strings.create(kv[i].toString());
			ACell v = (kv[i + 1] instanceof ACell c) ? c : Strings.create(kv[i + 1].toString());
			out = out.assoc(k, v);
		}
		return out;
	}

	// ===================== covia (owner-scoped lattice) =====================

	@Test public void coviaWriteDeniedUnderReadOnly() {
		assertTrue(capDenied(direct("covia", "write", m(Fields.PATH, "w/x", Fields.VALUE, "v"), readOnly)));
	}
	@Test public void coviaDeleteDeniedUnderReadOnly() {
		assertTrue(capDenied(direct("covia", "delete", m(Fields.PATH, "w/x"), readOnly)));
	}
	@Test public void coviaReadAllowedUnderReadOnly() {
		assertFalse(capDenied(direct("covia", "read", m(Fields.PATH, "w/x"), readOnly)));
	}
	@Test public void coviaWriteAllowedUnrestricted() {
		assertFalse(capDenied(direct("covia", "write", m(Fields.PATH, "w/cap-test", Fields.VALUE, "ok"), unrestricted)));
	}

	// ===================== asset (content-addressed) =====================

	@Test public void assetStoreDeniedUnderReadOnly() {
		assertTrue(capDenied(direct("asset", "store", Maps.empty(), readOnly)));
	}
	@Test public void assetGetAllowedUnderReadOnly() {
		assertFalse(capDenied(direct("asset", "get", m(Fields.ID, "0xabc123"), readOnly)));
	}
	@Test public void assetStoreNotCapDeniedUnrestricted() {
		// Null scope: no cap denial (may still error for missing metadata).
		assertFalse(capDenied(direct("asset", "store", Maps.empty(), unrestricted)));
	}

	// ===================== skills (read-only discovery) =====================

	@Test public void skillsListAllowedUnderReadOnly() {
		// Default sources (w/skills + v/skills) sit inside the owner-scoped
		// read grant — venue skills are publicly discoverable.
		assertFalse(capDenied(direct("skills", "manage", m("command", "list"), readOnly)));
	}
	@Test public void skillsReadByAssetRefAllowedUnderReadOnly() {
		// asset/read is in the scope; a missing asset errors but is not a denial.
		assertFalse(capDenied(direct("skills", "manage",
			m("command", "read", "ref", "a/" + "00".repeat(32)), readOnly)));
	}
	@Test public void skillsListCrossUserSourceDeniedUnderReadOnly() {
		AMap<AString, ACell> input = m("command", "list").assoc(
			Strings.create("sources"),
			convex.core.data.Vectors.of(Strings.create("did:key:zSomeoneElse/w/skills")));
		assertTrue(capDenied(direct("skills", "manage", input, readOnly)));
	}

	// ===================== file (scheme-qualified) =====================

	@Test public void fileWriteDeniedUnderReadOnly() {
		assertTrue(capDenied(direct("file", "write", m("root", "scratch", "path", "x.txt"), readOnly)));
	}
	@Test public void fileReadDeniedUnderReadOnly() {
		// file:// is not covered by the owner-scoped read grant — public reads denied.
		assertTrue(capDenied(direct("file", "read", m("root", "scratch", "path", "x.txt"), readOnly)));
	}
	@Test public void fileWriteNotCapDeniedUnrestricted() {
		assertFalse(capDenied(direct("file", "write", m("root", "scratch", "path", "x.txt"), unrestricted)));
	}

	// ===================== dlfs (owner-scoped path: <did>/dlfs/…) =====================

	@Test public void dlfsWriteDeniedUnderReadOnly() {
		assertTrue(capDenied(direct("dlfs", "write", m("drive", "d", "path", "x"), readOnly)));
	}
	// DLFS is a DID-scoped namespace (<callerDID>/dlfs/…) alongside /w/ and /j/, so a
	// read-only scope's crud/read on the caller's own namespace covers own-drive reads
	// — same as lattice reads. (Cross-user reads are gated separately by proofsCover.)
	@Test public void dlfsReadAllowedUnderReadOnly() {
		assertFalse(capDenied(direct("dlfs", "read", m("drive", "d", "path", "x"), readOnly)));
	}
	@Test public void dlfsWriteNotCapDeniedUnrestricted() {
		assertFalse(capDenied(direct("dlfs", "write", m("drive", "d", "path", "x"), unrestricted)));
	}

	// ===================== job-aware invoke gates =====================

	@Test public void a2aFuturePathRequiresInvokeBeforeOutboundWork() {
		assertTrue(capDenied(direct("a2a", "getAgentCard",
			m(Fields.URL, "http://10.0.0.1/a2a"), readOnly)),
			"A2A must deny at requireInvoke before URL validation or network work");
	}

	@Test public void agentJobPathRequiresInvokeAsWellAsTheActionAbility() {
		String agentId = "cap-invoke-" + System.nanoTime();
		// Deliberately grant the action-specific right but not invocation of the
		// operation definition. The Job-aware override must enforce both, exactly
		// like AgentAdapter.invokeFuture does.
		RequestContext actionOnly = RequestContext.of(DID).withCaps(Vectors.of(
			Capability.create(Strings.create("g/" + agentId), Strings.create("agent/create"))));
		Job job = engine.jobs().invokeOperation("v/ops/agent/create",
			m(Fields.AGENT_ID, agentId, Fields.CONFIG,
				Maps.of(Fields.OPERATION, Strings.create("v/ops/llmagent/chat"))),
			actionOnly);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(job.getStatus().equals(Status.FAILED));
		assertTrue(job.getErrorMessage().contains("invoke"), job.getErrorMessage());
	}
}
