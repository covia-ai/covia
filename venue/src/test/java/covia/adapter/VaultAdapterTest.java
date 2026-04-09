package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;

public class VaultAdapterTest {

	static Engine engine;
	static final AString ALICE_DID = Strings.create("did:key:z6MkVaultAlice");

	@BeforeAll
	static void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	private ACell run(String op, ACell input) {
		return engine.jobs().invokeOperation(
			op, input, RequestContext.of(ALICE_DID)
		).awaitResult(5000);
	}

	@Test
	public void testWriteAndRead() {
		// Write to vault — no drive parameter needed
		ACell result = run("vault:write", Maps.of(
			"path", "profile.json",
			"content", "{\"name\": \"Sarah Smith\", \"nhsNumber\": \"485 777 3456\"}"
		));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Read back
		result = run("vault:read", Maps.of("path", "profile.json"));
		String content = RT.ensureString(RT.getIn(result, "content")).toString();
		assertTrue(content.contains("Sarah Smith"));
		assertEquals("utf-8", RT.ensureString(RT.getIn(result, "encoding")).toString());
	}

	@Test
	public void testMkdirAndList() {
		run("vault:mkdir", Maps.of("path", "lab-results"));
		run("vault:write", Maps.of("path", "lab-results/panel-q4.json", "content", "{\"tsh\": 2.8}"));

		// List lab-results dir
		ACell result = run("vault:list", Maps.of("path", "lab-results"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertEquals(1, entries.count());
		assertEquals("panel-q4.json", RT.getIn(entries.get(0), "name").toString());
	}

	@Test
	public void testDelete() {
		run("vault:mkdir", Maps.of("path", "tmp"));
		run("vault:write", Maps.of("path", "tmp/deleteme.txt", "content", "delete me"));
		ACell result = run("vault:delete", Maps.of("path", "tmp/deleteme.txt"));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));
	}

	@Test
	public void testNoAuthFails() {
		// Anonymous context should fail
		assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation(
				"vault:list", Maps.empty(), RequestContext.ANONYMOUS
			).awaitResult(5000)
		);
	}
}
