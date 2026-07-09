package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import covia.api.Fields;
import covia.lattice.CapabilityChecker;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Capability enforcement at the adapter layer — the drift-free, resource-pinned
 * checks that each resource adapter performs at its execution point.
 *
 * <p>Each op is invoked <b>directly</b> on its adapter ({@code invokeFuture}),
 * bypassing {@code JobManager}'s name-keyed boundary net, so these assertions
 * isolate the adapter's own {@code ctx.requireCapability(...)} call: the right
 * resource and the right ability. Under the public read-only ceiling, mutations
 * are denied and owner-scoped reads are allowed — including DLFS, which is a
 * DID-scoped {@code <did>/dlfs/…} namespace covered by the caller's crud/read
 * grant like {@code /w/}. Only genuinely scheme-qualified {@code file://}
 * resources fall outside the owner-scoped grant, so those reads are denied (the
 * secure default for a public caller). A null ceiling (authenticated/internal)
 * is unrestricted — no cap denial on any op.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class AdapterCapEnforcementTest {

	private static Engine engine;
	private static final AString DID = Strings.create("did:key:zCapEnforceTest");
	private static RequestContext readOnly;     // public read-only ceiling
	private static RequestContext unrestricted; // null ceiling

	@BeforeAll
	public void setup() {
		engine = TestEngine.ENGINE;
		readOnly = RequestContext.of(DID).withCaps(CapabilityChecker.readOnlyCeiling(DID));
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
		// Null ceiling: no cap denial (may still error for missing metadata).
		assertFalse(capDenied(direct("asset", "store", Maps.empty(), unrestricted)));
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
	// read-only ceiling's crud/read on the caller's own namespace covers own-drive reads
	// — same as lattice reads. (Cross-user reads are gated separately by proofsCover.)
	@Test public void dlfsReadAllowedUnderReadOnly() {
		assertFalse(capDenied(direct("dlfs", "read", m("drive", "d", "path", "x"), readOnly)));
	}
	@Test public void dlfsWriteNotCapDeniedUnrestricted() {
		assertFalse(capDenied(direct("dlfs", "write", m("drive", "d", "path", "x"), unrestricted)));
	}
}
