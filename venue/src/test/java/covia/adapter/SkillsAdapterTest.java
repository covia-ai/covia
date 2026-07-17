package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for the {@code skills} venue op ({@code v/ops/skills}) — read-only
 * skill discovery, end-to-end through the catalog (resolution, schema,
 * dispatch) via {@code invokeInternal}.
 */
public class SkillsAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final AString K_CONTENT_TEXT = Strings.intern("contentText");
	private static final AString K_SOURCES = Strings.intern("sources");
	private static final AString K_REF = Strings.intern("ref");
	private static final AString K_BODY = Strings.intern("body");

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	private ACell invoke(AMap<AString, ACell> input, RequestContext c) {
		try {
			return engine.jobs().invokeInternal("v/ops/skills", input, c).get(5, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw (e.getCause() instanceof RuntimeException re) ? re : new RuntimeException(e.getCause());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void write(String path, ACell value, RequestContext c) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value), c)
				.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ========== list ==========

	@Test
	public void testListEmptyReturnsNull() {
		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);
		assertNull(result, "no skills → null (the assemble-op contract)");
	}

	@Test
	public void testListWorkspaceSkills() {
		write("w/skills/alpha", Maps.of(Fields.DESCRIPTION, Strings.create("Alpha skill")), ctx);
		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);
		assertNotNull(result);
		assertTrue(result.toString().contains("- alpha — Alpha skill"), result.toString());
	}

	@Test
	public void testListDefaultSources() {
		// Omitted sources default to ["w/skills", "v/skills"].
		write("w/skills/alpha", Maps.of(Fields.DESCRIPTION, Strings.create("Alpha skill")), ctx);
		ACell result = invoke(Maps.of(Strings.create("command"), Strings.create("list")), ctx);
		assertNotNull(result);
		assertTrue(result.toString().contains("- alpha — Alpha skill"), result.toString());
	}

	@Test
	public void testListVenueSkills() {
		// Venue skills are written under the venue's own identity (the
		// VenueGlobalsResolver write gate) and publicly discoverable.
		RequestContext venueCtx = RequestContext.of(engine.getDIDString());
		write("v/skills/venue-demo", Maps.of(
			Fields.DESCRIPTION, Strings.create("A venue-installed skill")), venueCtx);

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("v/skills"))), ctx);
		assertNotNull(result);
		assertTrue(result.toString().contains("- venue-demo — A venue-installed skill"), result.toString());
	}

	// ========== read ==========

	@Test
	public void testReadByName() {
		write("w/skills/reader", Maps.of(
			Fields.DESCRIPTION, Strings.create("Reads things"),
			Strings.create("skill"), Maps.of(
				Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read")))), ctx);

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("reader"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);

		assertEquals("reader", RT.getIn(result, Fields.NAME).toString());
		assertEquals("Reads things", RT.getIn(result, Fields.DESCRIPTION).toString());
		assertNull(RT.getIn(result, K_BODY), "contentless skill (a pure toolset) has no body");
		assertEquals("w/skills/reader", RT.getIn(result, Fields.PATH).toString());
		AVector<?> tools = (AVector<?>) RT.getIn(result, Fields.TOOLS);
		assertEquals(1, tools.count());
	}

	@Test
	public void testReadInlineContentBody() {
		write("w/skills/notes", Maps.of(
			Fields.DESCRIPTION, Strings.create("House notes"),
			Fields.CONTENT, Maps.of(Strings.create("inline"),
				Strings.create("Always write results to w/analysis."))), ctx);

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("notes"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);

		assertEquals("Always write results to w/analysis.", RT.getIn(result, K_BODY).toString());
	}

	@Test
	public void testReadByRefWithContentBody() {
		AMap<AString, ACell> storeInput = Maps.of(
			Fields.METADATA, Maps.of(
				Fields.NAME, Strings.create("asset-skill"),
				Fields.DESCRIPTION, Strings.create("From an asset")),
			K_CONTENT_TEXT, Strings.create("The full body text"));
		ACell stored;
		try {
			stored = engine.jobs().invokeInternal("v/ops/asset/store", storeInput, ctx)
				.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		Hash h = AssetAdapter.parseAssetId(RT.ensureString(RT.getIn(stored, Fields.ID)));
		String ref = "a/" + h.toHexString();

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			K_REF, Strings.create(ref)), ctx);

		assertEquals("asset-skill", RT.getIn(result, Fields.NAME).toString());
		assertEquals("The full body text", RT.getIn(result, K_BODY).toString());
		assertEquals(ref, RT.getIn(result, Fields.PATH).toString());
	}

	@Test
	public void testReadRequiresExactlyOneOfNameAndRef() {
		try {
			invoke(Maps.of(Strings.create("command"), Strings.create("read")), ctx);
			fail("neither name nor ref should be rejected");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
		}
		try {
			invoke(Maps.of(
				Strings.create("command"), Strings.create("read"),
				Fields.NAME, Strings.create("x"),
				K_REF, Strings.create("w/skills/x")), ctx);
			fail("both name and ref should be rejected");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
		}
	}

	@Test
	public void testUnknownCommandRejected() {
		try {
			invoke(Maps.of(Strings.create("command"), Strings.create("write")), ctx);
			fail("unknown command should be rejected");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("list | read"), e.getMessage());
		}
	}
}
