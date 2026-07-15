package covia.venue.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.Hashing;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Cursors;
import covia.grid.AContent;
import convex.lattice.generic.CASLattice;
import covia.lattice.Covia;

/**
 * Tests for LatticeStorage - content-addressed storage extending AStorage,
 * backed by CASLattice.
 */
public class LatticeStorageTest {

	private LatticeStorage storage;

	@BeforeEach
	public void setup() throws IOException {
		storage = new LatticeStorage();
		storage.initialise();
	}

	// ========== Basic AStorage Operations ==========

	@Test
	public void testCreateStandalone() throws IOException {
		LatticeStorage s = new LatticeStorage();
		s.initialise();
		assertNotNull(s);
		assertTrue(s.isEmpty());
		assertEquals(0, s.count());
		assertTrue(s.isInitialised());
	}

	@Test
	public void testStoreAndGetContent() throws IOException {
		byte[] data = "hello world".getBytes();
		Hash hash = Hashing.sha256(data);

		storage.store(hash, new ByteArrayInputStream(data));

		AContent content = storage.getContent(hash);
		assertNotNull(content);
		assertEquals(data.length, content.getSize());
	}

	@Test
	public void testExists() throws IOException {
		byte[] data = "test data".getBytes();
		Hash hash = Hashing.sha256(data);

		assertFalse(storage.exists(hash));

		storage.store(hash, new ByteArrayInputStream(data));

		assertTrue(storage.exists(hash));
	}

	@Test
	public void testGetNonExistent() throws IOException {
		Hash hash = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
		assertNull(storage.getContent(hash));
	}

	@Test
	public void testGetSize() throws IOException {
		byte[] data = "size test".getBytes();
		Hash hash = Hashing.sha256(data);

		storage.store(hash, new ByteArrayInputStream(data));

		assertEquals(data.length, storage.getSize(hash));
	}

	@Test
	public void testDelete() throws IOException {
		byte[] data = "delete me".getBytes();
		Hash hash = Hashing.sha256(data);

		storage.store(hash, new ByteArrayInputStream(data));
		assertTrue(storage.exists(hash));

		assertTrue(storage.delete(hash));
		assertFalse(storage.exists(hash));
	}

	@Test
	public void testDeleteNonExistent() throws IOException {
		Hash hash = Hash.fromHex("2222222222222222222222222222222222222222222222222222222222222222");
		assertFalse(storage.delete(hash));
	}

	// ========== Initialization State ==========

	@Test
	public void testNotInitializedThrows() {
		LatticeStorage uninit = new LatticeStorage();
		Hash hash = Hash.fromHex("3333333333333333333333333333333333333333333333333333333333333333");

		assertThrows(IllegalStateException.class, () -> uninit.store(hash, new ByteArrayInputStream(new byte[0])));
		assertThrows(IllegalStateException.class, () -> uninit.getContent(hash));
		assertThrows(IllegalStateException.class, () -> uninit.delete(hash));
		assertThrows(IllegalStateException.class, () -> uninit.getSize(hash));
	}

	@Test
	public void testExistsReturnsFalseWhenNotInitialized() {
		LatticeStorage uninit = new LatticeStorage();
		Hash hash = Hash.fromHex("4444444444444444444444444444444444444444444444444444444444444444");
		assertFalse(uninit.exists(hash));
	}

	// ========== Null Handling ==========

	@Test
	public void testStoreNullHash() {
		assertThrows(IllegalArgumentException.class, () ->
			storage.store(null, new ByteArrayInputStream(new byte[0])));
	}

	@Test
	public void testStoreNullStream() {
		Hash hash = Hash.fromHex("5555555555555555555555555555555555555555555555555555555555555555");
		assertThrows(IllegalArgumentException.class, () ->
			storage.store(hash, (InputStream) null));
	}

	@Test
	public void testGetContentNullHash() {
		assertThrows(IllegalArgumentException.class, () -> storage.getContent(null));
	}

	// ========== Lattice-specific Operations ==========

	@Test
	public void testGetState() throws IOException {
		byte[] data1 = "data1".getBytes();
		byte[] data2 = "data2".getBytes();
		Hash h1 = Hashing.sha256(data1);
		Hash h2 = Hashing.sha256(data2);

		storage.store(h1, new ByteArrayInputStream(data1));
		storage.store(h2, new ByteArrayInputStream(data2));

		Index<ABlob, ABlob> state = storage.getState();
		assertNotNull(state);
		assertEquals(2, state.count());
	}

	// NOTE: LatticeStorage.merge() was deleted (#214 follow-up) — application
	// code never merges lattice values directly; convergence is the cursor
	// layer's job (fork + sync), and CAS union semantics are CASLattice's
	// (convex-core) to test. Storage writes go through cursor.updateAndGet.

	// ========== Integration with Lattice Cursor ==========

	@Test
	public void testWithLatticeCursor() throws IOException {
		// Create a venue lattice state with proper structure
		ACell venueState = Index.none();
		var gridState = Maps.of(
			Covia.GRID, Maps.of(
				Covia.VENUES, Index.of(
					"did:test:venue", venueState
				)
			)
		);

		// Create cursor path to storage
		@SuppressWarnings("unchecked")
		ACursor<ACell> rootCursor = (ACursor<ACell>) (ACursor<?>) Cursors.of(gridState);
		@SuppressWarnings("unchecked")
		ACursor<Index<ABlob, ABlob>> storageCursor =
			(ACursor<Index<ABlob, ABlob>>) (ACursor<?>) rootCursor.path(
				Covia.GRID, Covia.VENUES, Strings.create("did:test:venue"), Covia.STORAGE);

		// Create storage backed by cursor
		LatticeStorage cursorStorage = new LatticeStorage(storageCursor);
		cursorStorage.initialise();

		// Store some data
		byte[] data = "cursor backed storage".getBytes();
		Hash hash = Hashing.sha256(data);
		cursorStorage.store(hash, new ByteArrayInputStream(data));

		// Verify data is accessible
		assertTrue(cursorStorage.exists(hash));
		assertEquals(1, cursorStorage.count());

		// Verify data is reflected in cursor
		Index<ABlob, ABlob> cursorState = storageCursor.get();
		assertNotNull(cursorState);
		assertTrue(cursorState.containsKey(hash));
	}

	// ========== Close ==========

	@Test
	public void testClose() throws IOException {
		byte[] data = "close test".getBytes();
		Hash hash = Hashing.sha256(data);
		storage.store(hash, new ByteArrayInputStream(data));

		storage.close();

		assertFalse(storage.isInitialised());
	}

	// ========== Misc ==========

	@Test
	public void testGetLattice() {
		assertNotNull(storage.getLattice());
		assertTrue(storage.getLattice() instanceof CASLattice);
	}

	@Test
	public void testToString() throws IOException {
		String str = storage.toString();
		assertTrue(str.contains("0 entries"));

		byte[] data = "test".getBytes();
		storage.store(Hashing.sha256(data), new ByteArrayInputStream(data));

		str = storage.toString();
		assertTrue(str.contains("1 entries"));
	}
}
