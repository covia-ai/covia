package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Tests for ContextLoader — context entry resolution for agent LLM context.
 *
 * <p>Focuses on the resolution paths: literal text, workspace references,
 * asset content, job results, grid operations, map entries. Does not test
 * E2E agent runs.</p>
 */
public class ContextLoaderTest {

	private Engine engine;
	private ContextLoader loader;
	private RequestContext ctx;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
		ctx = RequestContext.of(ALICE_DID);
		loader = new ContextLoader(engine);
	}

	// ========== Static helpers ==========

	@Test
	public void testIsNamespacePath() {
		assertTrue(ContextLoader.isNamespacePath("w/docs/rules"));
		assertTrue(ContextLoader.isNamespacePath("g/my-agent/state"));
		assertTrue(ContextLoader.isNamespacePath("o/my-op"));
		assertTrue(ContextLoader.isNamespacePath("j/some-job"));
		assertTrue(ContextLoader.isNamespacePath("s/secret-name"));
		assertTrue(ContextLoader.isNamespacePath("h/hitl-request"));

		assertFalse(ContextLoader.isNamespacePath("Always use British English"));
		assertFalse(ContextLoader.isNamespacePath("abc123def456"));
		assertFalse(ContextLoader.isNamespacePath("/a/abc123"));
		assertFalse(ContextLoader.isNamespacePath("test:echo"));
	}

	@Test
	public void testIsAssetReference() {
		// Hex hash (64 chars)
		assertTrue(ContextLoader.isAssetReference("a".repeat(64)));
		assertTrue(ContextLoader.isAssetReference("0123456789abcdef".repeat(4)));

		// /a/ and /o/ paths
		assertTrue(ContextLoader.isAssetReference("/a/abc123"));
		assertTrue(ContextLoader.isAssetReference("/o/my-op"));

		// DID URLs
		assertTrue(ContextLoader.isAssetReference("did:key:z6MkAlice/a/abc123"));

		// Adapter:op pattern
		assertTrue(ContextLoader.isAssetReference("test:echo"));
		assertTrue(ContextLoader.isAssetReference("langchain:openai"));

		// Literal text — should NOT be asset references
		assertFalse(ContextLoader.isAssetReference("Always use British English"));
		assertFalse(ContextLoader.isAssetReference("This is a plain instruction."));
		assertFalse(ContextLoader.isAssetReference("short"));
	}

	@Test
	public void testDeriveLabel() {
		assertEquals("w/docs/rules", ContextLoader.deriveLabel("w/docs/rules"));
		assertEquals("test:echo", ContextLoader.deriveLabel("test:echo"));
		// Hex hash gets truncated
		String hash = "a".repeat(64);
		assertEquals("aaaaaaaaaaaa...", ContextLoader.deriveLabel(hash));
	}

	@Test
	public void testSystemMessage() {
		ACell msg = ContextLoader.systemMessage("AP Rules", "Rule 1: do stuff");
		AString content = RT.ensureString(RT.getIn(msg, Strings.intern("content")));
		assertEquals("[Context: AP Rules]\nRule 1: do stuff", content.toString());

		// Null label — no prefix
		ACell msg2 = ContextLoader.systemMessage(null, "Plain text");
		AString content2 = RT.ensureString(RT.getIn(msg2, Strings.intern("content")));
		assertEquals("Plain text", content2.toString());
	}

	// ========== Literal text entries ==========

	@Test
	public void testLiteralTextString() {
		ACell msg = loader.resolveEntry(Strings.create("Always respond in British English"), ctx);
		assertNotNull(msg);
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("Always respond in British English"));
	}

	@Test
	public void testLiteralTextMap() {
		ACell entry = Maps.of(
			Strings.intern("text"), Strings.create("Use metric units."),
			Strings.intern("label"), Strings.create("Unit Policy")
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNotNull(msg);
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("[Context: Unit Policy]"));
		assertTrue(content.contains("Use metric units."));
	}

	// ========== Workspace path entries ==========

	@Test
	public void testWorkspacePathExists() {
		// Write some data to workspace
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/docs/test-rules"),
				Strings.create("value"), Strings.create("Rule 1: Always validate")),
			ctx).awaitResult(5000);

		ACell msg = loader.resolveEntry(Strings.create("w/docs/test-rules"), ctx);
		assertNotNull(msg, "Should resolve workspace path");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("Rule 1: Always validate"));
		assertTrue(content.contains("[Context: w/docs/test-rules]"));
	}

	@Test
	public void testWorkspacePathMissing() {
		ACell msg = loader.resolveEntry(Strings.create("w/docs/nonexistent"), ctx);
		assertNull(msg, "Missing workspace path should return null");
	}

	@Test
	public void testWorkspacePathStructuredValue() {
		// Write structured data (map)
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/config/policy"),
				Strings.create("value"), Maps.of(
					Strings.create("max_amount"), 50000,
					Strings.create("require_approval"), true)),
			ctx).awaitResult(5000);

		ACell msg = loader.resolveEntry(Strings.create("w/config/policy"), ctx);
		assertNotNull(msg, "Should resolve structured workspace data");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("max_amount"));
	}

	// ========== Asset reference entries ==========

	@Test
	public void testAssetWithTextContent() {
		// Store an asset with text content
		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(
				Strings.create("metadata"), Maps.of(
					Strings.create("name"), Strings.create("Test Policy"),
					Strings.create("type"), Strings.create("document")),
				Strings.create("contentText"), Strings.create("Section 1: All invoices require approval.")),
			ctx);
		ACell storeResult = storeJob.awaitResult(5000);
		AString assetId = RT.ensureString(RT.getIn(storeResult, Strings.intern("id")));
		assertNotNull(assetId);

		ACell msg = loader.resolveEntry(assetId, ctx);
		assertNotNull(msg, "Should resolve asset with content");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("Section 1: All invoices require approval."));
	}

	@Test
	public void testAssetDescriptionFallback() {
		// Store an asset without content — should fall back to description
		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(
				Strings.create("metadata"), Maps.of(
					Strings.create("name"), Strings.create("My Operation"),
					Strings.create("description"), Strings.create("This operation processes invoices"))),
			ctx);
		ACell storeResult = storeJob.awaitResult(5000);
		AString assetId = RT.ensureString(RT.getIn(storeResult, Strings.intern("id")));

		ACell msg = loader.resolveEntry(assetId, ctx);
		assertNotNull(msg, "Should resolve asset via description fallback");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("This operation processes invoices"));
	}

	@Test
	public void testAssetByRegisteredName() {
		// test:echo is a registered operation name
		ACell msg = loader.resolveEntry(Strings.create("test:echo"), ctx);
		assertNotNull(msg, "Should resolve registered operation name");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		// Should contain the operation's description
		assertTrue(content.length() > 0);
	}

	@Test
	public void testAssetNotFound() {
		String fakeHash = "f".repeat(64);
		ACell msg = loader.resolveEntry(Strings.create(fakeHash), ctx);
		assertNull(msg, "Non-existent asset should return null");
	}

	// ========== Job result entries ==========

	@Test
	public void testJobResultComplete() {
		// Create a completed job via test:echo
		Job job = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("message"), Strings.create("hello world")),
			ctx);
		ACell result = job.awaitResult(5000);
		assertNotNull(result);

		String jobIdHex = job.getID().toHexString();

		ACell entry = Maps.of(
			Strings.intern("job"), Strings.create(jobIdHex),
			Strings.intern("label"), Strings.create("Echo Result")
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNotNull(msg, "Should resolve completed job");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("[Context: Echo Result]"));
		assertTrue(content.contains("hello world"));
	}

	@Test
	public void testJobResultWithPath() {
		// Create a completed job with structured output
		Job job = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("nested"), Maps.of(
				Strings.create("value"), Strings.create("deep data"))),
			ctx);
		job.awaitResult(5000);
		String jobIdHex = job.getID().toHexString();

		ACell entry = Maps.of(
			Strings.intern("job"), Strings.create(jobIdHex),
			Strings.intern("path"), Strings.create("nested.value")
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNotNull(msg, "Should resolve job output with path");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("deep data"));
	}

	@Test
	public void testJobResultNotFound() {
		ACell entry = Maps.of(
			Strings.intern("job"), Strings.create("00".repeat(16))
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNull(msg, "Non-existent job should return null");
	}

	@Test
	public void testJobResultRequiredThrows() {
		ACell entry = Maps.of(
			Strings.intern("job"), Strings.create("00".repeat(16)),
			Strings.intern("required"), CVMBool.TRUE
		);
		assertThrows(RuntimeException.class, () -> loader.resolveEntry(entry, ctx));
	}

	// ========== Grid operation entries ==========

	@Test
	public void testGridOpEntry() {
		ACell entry = Maps.of(
			Strings.intern("op"), Strings.create("test:echo"),
			Strings.intern("input"), Maps.of(Strings.create("greeting"), Strings.create("hello from op")),
			Strings.intern("label"), Strings.create("Echo Op")
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNotNull(msg, "Should resolve grid operation");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("[Context: Echo Op]"));
		assertTrue(content.contains("hello from op"));
	}

	@Test
	public void testGridOpWorkspaceRead() {
		// Write data, then load via op entry
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/context-test"),
				Strings.create("value"), Strings.create("loaded via op")),
			ctx).awaitResult(5000);

		ACell entry = Maps.of(
			Strings.intern("op"), Strings.create("covia:read"),
			Strings.intern("input"), Maps.of(Strings.create("path"), Strings.create("w/context-test"))
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNotNull(msg, "Should resolve covia:read op");
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("loaded via op"));
	}

	@Test
	public void testGridOpFailureNotRequired() {
		ACell entry = Maps.of(
			Strings.intern("op"), Strings.create("test:error"),
			Strings.intern("input"), Maps.of(Strings.create("message"), Strings.create("boom"))
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNull(msg, "Failed non-required op should return null");
	}

	@Test
	public void testGridOpFailureRequired() {
		ACell entry = Maps.of(
			Strings.intern("op"), Strings.create("test:error"),
			Strings.intern("input"), Maps.of(Strings.create("message"), Strings.create("boom")),
			Strings.intern("required"), CVMBool.TRUE
		);
		assertThrows(RuntimeException.class, () -> loader.resolveEntry(entry, ctx));
	}

	// ========== Map entries with ref ==========

	@Test
	public void testMapRefWorkspace() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/ref-test"),
				Strings.create("value"), Strings.create("ref content")),
			ctx).awaitResult(5000);

		ACell entry = Maps.of(
			Strings.intern("ref"), Strings.create("w/ref-test"),
			Strings.intern("label"), Strings.create("Custom Label")
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNotNull(msg);
		String content = RT.ensureString(RT.getIn(msg, Strings.intern("content"))).toString();
		assertTrue(content.contains("[Context: Custom Label]"));
		assertTrue(content.contains("ref content"));
	}

	@Test
	public void testMapRefRequiredMissing() {
		ACell entry = Maps.of(
			Strings.intern("ref"), Strings.create("w/does-not-exist"),
			Strings.intern("required"), CVMBool.TRUE
		);
		assertThrows(RuntimeException.class, () -> loader.resolveEntry(entry, ctx));
	}

	@Test
	public void testMapRefOptionalMissing() {
		ACell entry = Maps.of(
			Strings.intern("ref"), Strings.create("w/does-not-exist")
		);
		ACell msg = loader.resolveEntry(entry, ctx);
		assertNull(msg, "Optional missing ref should return null");
	}

	// ========== Batch resolve ==========

	@Test
	public void testResolveMultipleEntries() {
		// Write workspace data
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/batch-test"),
				Strings.create("value"), Strings.create("batch value")),
			ctx).awaitResult(5000);

		AVector<ACell> entries = Vectors.of(
			(ACell) Strings.create("Inline instruction"),
			(ACell) Strings.create("w/batch-test"),
			(ACell) Strings.create("w/nonexistent")  // should be skipped
		);

		AVector<ACell> messages = loader.resolve(entries, ctx);
		assertEquals(2, messages.count(), "Should resolve 2 of 3 entries (one missing)");
	}

	@Test
	public void testResolveEmptyVector() {
		AVector<ACell> messages = loader.resolve(Vectors.empty(), ctx);
		assertEquals(0, messages.count());
	}

	@Test
	public void testResolveNull() {
		AVector<ACell> messages = loader.resolve(null, ctx);
		assertEquals(0, messages.count());
	}

	// ========== Null/edge cases ==========

	@Test
	public void testNullEntry() {
		ACell msg = loader.resolveEntry(null, ctx);
		assertNull(msg);
	}

	@Test
	public void testEmptyString() {
		ACell msg = loader.resolveEntry(Strings.create(""), ctx);
		assertNotNull(msg, "Empty string is literal text");
	}
}
