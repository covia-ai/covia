package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;

public class DLFSAdapterTest {

	static Engine engine;
	static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

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
	public void testCreateAndListDrives() {
		// No drives initially
		ACell result = run("dlfs:listDrives", Maps.empty());
		AVector<?> drives = RT.ensureVector(RT.getIn(result, "drives"));
		assertNotNull(drives);
		long initialCount = drives.count();

		// Create health-vault drive
		result = run("dlfs:createDrive", Maps.of("name", "health-vault"));
		assertEquals(true, RT.bool(RT.getIn(result, "created")));

		// List should show it
		result = run("dlfs:listDrives", Maps.empty());
		drives = RT.ensureVector(RT.getIn(result, "drives"));
		assertEquals(initialCount + 1, drives.count());
		assertTrue(drives.toString().contains("health-vault"));

		// Creating same drive again should return created: false
		result = run("dlfs:createDrive", Maps.of("name", "health-vault"));
		assertEquals(false, RT.bool(RT.getIn(result, "created")));
	}

	@Test
	public void testWriteAndReadFile() {
		run("dlfs:createDrive", Maps.of("name", "test-rw"));

		// Write a file
		ACell result = run("dlfs:write", Maps.of(
			"drive", "test-rw",
			"path", "profile.json",
			"content", "{\"name\": \"Sarah Smith\"}"
		));
		assertTrue(RT.bool(RT.getIn(result, "created")));
		long written = RT.ensureLong(RT.getIn(result, "written")).longValue();
		assertTrue(written > 0);

		// Read it back
		result = run("dlfs:read", Maps.of("drive", "test-rw", "path", "profile.json"));
		String content = RT.ensureString(RT.getIn(result, "content")).toString();
		assertEquals("{\"name\": \"Sarah Smith\"}", content);
		assertEquals("utf-8", RT.ensureString(RT.getIn(result, "encoding")).toString());
	}

	@Test
	public void testMkdirAndList() {
		run("dlfs:createDrive", Maps.of("name", "test-dir"));

		// Create directory
		ACell result = run("dlfs:mkdir", Maps.of("drive", "test-dir", "path", "medications"));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Write file inside
		run("dlfs:write", Maps.of(
			"drive", "test-dir",
			"path", "medications/levothyroxine.json",
			"content", "{\"dose\": \"75mcg\"}"
		));

		// List root
		result = run("dlfs:list", Maps.of("drive", "test-dir"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertEquals(1, entries.count());
		assertEquals("medications", RT.getIn(entries.get(0), "name").toString());
		assertEquals("directory", RT.getIn(entries.get(0), "type").toString());

		// List medications dir
		result = run("dlfs:list", Maps.of("drive", "test-dir", "path", "medications"));
		entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertEquals(1, entries.count());
		assertEquals("levothyroxine.json", RT.getIn(entries.get(0), "name").toString());
		assertEquals("file", RT.getIn(entries.get(0), "type").toString());
	}

	@Test
	public void testDeleteFile() {
		run("dlfs:createDrive", Maps.of("name", "test-del"));
		run("dlfs:write", Maps.of("drive", "test-del", "path", "temp.txt", "content", "delete me"));

		// Delete
		ACell result = run("dlfs:delete", Maps.of("drive", "test-del", "path", "temp.txt"));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));

		// Read should fail
		assertThrows(Exception.class, () ->
			run("dlfs:read", Maps.of("drive", "test-del", "path", "temp.txt"))
		);
	}

	@Test
	public void testDeleteDrive() {
		run("dlfs:createDrive", Maps.of("name", "test-remove"));

		ACell result = run("dlfs:deleteDrive", Maps.of("name", "test-remove"));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));

		// Drive should be gone — file ops should fail
		assertThrows(Exception.class, () ->
			run("dlfs:list", Maps.of("drive", "test-remove"))
		);
	}

	@Test
	public void testDriveNotFound() {
		assertThrows(Exception.class, () ->
			run("dlfs:read", Maps.of("drive", "nonexistent", "path", "foo.txt"))
		);
	}

	@Test
	public void testOverwriteFile() {
		run("dlfs:createDrive", Maps.of("name", "test-overwrite"));
		run("dlfs:write", Maps.of("drive", "test-overwrite", "path", "data.txt", "content", "v1"));

		// Overwrite
		ACell result = run("dlfs:write", Maps.of(
			"drive", "test-overwrite", "path", "data.txt", "content", "v2"
		));
		assertEquals(false, RT.bool(RT.getIn(result, "created")));

		// Read should return v2
		result = run("dlfs:read", Maps.of("drive", "test-overwrite", "path", "data.txt"));
		assertEquals("v2", RT.ensureString(RT.getIn(result, "content")).toString());
	}
}
