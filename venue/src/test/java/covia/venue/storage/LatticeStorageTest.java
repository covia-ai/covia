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
import covia.lattice.CASLattice;
import covia.lattice.Covia;
import covia.lattice.GridLattice;
import covia.lattice.VenueLattice;

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

		Index<Hash, ABlob> state = storage.getState();
		assertNotNull(state);
		assertEquals(2, state.count());
	}

	@Test
	public void testMerge() throws IOException {
		LatticeStorage storage1 = new LatticeStorage();
		storage1.initialise();
		LatticeStorage storage2 = new LatticeStorage();
		storage2.initialise();

		byte[] data1 = "from storage1".getBytes();
		byte[] data2 = "from storage2".getBytes();
		Hash h1 = Hashing.sha256(data1);
		Hash h2 = Hashing.sha256(data2);

		storage1.store(h1, new ByteArrayInputStream(data1));
		storage2.store(h2, new ByteArrayInputStream(data2));

		storage1.merge(storage2);

		assertEquals(2, storage1.count());
		assertTrue(storage1.exists(h1));
		assertTrue(storage1.exists(h2));
	}

	@Test
	public void testMergeIdempotent() throws IOException {
		byte[] data = "idempotent".getBytes();
		Hash hash = Hashing.sha256(data);
		storage.store(hash, new ByteArrayInputStream(data));

		Index<Hash, ABlob> before = storage.getState();
		storage.merge(before);
		Index<Hash, ABlob> after = storage.getState();

		assertEquals(before, after, "Merging same state should be idempotent");
	}

	@Test
	public void testMergeCommutative() throws IOException {
		LatticeStorage s1 = new LatticeStorage();
		s1.initialise();
		LatticeStorage s2 = new LatticeStorage();
		s2.initialise();

		byte[] data1 = "alpha".getBytes();
		byte[] data2 = "beta".getBytes();
		Hash h1 = Hashing.sha256(data1);
		Hash h2 = Hashing.sha256(data2);

		s1.store(h1, new ByteArrayInputStream(data1));
		s2.store(h2, new ByteArrayInputStream(data2));

		Index<Hash, ABlob> state1 = s1.getState();
		Index<Hash, ABlob> state2 = s2.getState();

		LatticeStorage merged12 = new LatticeStorage();
		merged12.initialise();
		merged12.merge(state1);
		merged12.merge(state2);

		LatticeStorage merged21 = new LatticeStorage();
		merged21.initialise();
		merged21.merge(state2);
		merged21.merge(state1);

		assertEquals(merged12.getState(), merged21.getState(), "Merge should be commutative");
	}

	// ========== Integration with Lattice Cursor ==========

	@Test
	public void testWithLatticeCursor() throws IOException {
		// Create a venue lattice state with proper structure
		var venueState = VenueLattice.INSTANCE.zero();
		var gridState = Maps.of(
			Covia.GRID, Maps.of(
				GridLattice.VENUES, Maps.of(
					Strings.create("did:test:venue"), venueState
				)
			)
		);

		// Create cursor path to storage
		@SuppressWarnings("unchecked")
		ACursor<ACell> rootCursor = (ACursor<ACell>) (ACursor<?>) Cursors.of(gridState);
		@SuppressWarnings("unchecked")
		ACursor<Index<Hash, ABlob>> storageCursor =
			(ACursor<Index<Hash, ABlob>>) (ACursor<?>) rootCursor.path(
				Covia.GRID, GridLattice.VENUES, Strings.create("did:test:venue"), VenueLattice.STORAGE);

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
		Index<Hash, ABlob> cursorState = storageCursor.get();
		assertNotNull(cursorState);
		assertTrue(cursorState.containsKey(hash));
	}

	// ========== Real-world Scenarios ==========

	@Test
	public void testDistributedSync() throws IOException {
		LatticeStorage node1 = new LatticeStorage();
		node1.initialise();
		LatticeStorage node2 = new LatticeStorage();
		node2.initialise();

		// Node 1 stores data
		byte[] data1 = "document from node 1".getBytes();
		Hash h1 = Hashing.sha256(data1);
		node1.store(h1, new ByteArrayInputStream(data1));

		// Node 2 stores different data
		byte[] data2 = "document from node 2".getBytes();
		Hash h2 = Hashing.sha256(data2);
		node2.store(h2, new ByteArrayInputStream(data2));

		// Sync: each merges the other's state
		node1.merge(node2);
		node2.merge(node1);

		// Both should have all data
		assertEquals(2, node1.count());
		assertEquals(2, node2.count());
		assertTrue(node1.exists(h1));
		assertTrue(node1.exists(h2));
		assertTrue(node2.exists(h1));
		assertTrue(node2.exists(h2));
	}

	@Test
	public void testContentDeduplication() throws IOException {
		LatticeStorage s1 = new LatticeStorage();
		s1.initialise();
		LatticeStorage s2 = new LatticeStorage();
		s2.initialise();

		// Both nodes store the same content
		byte[] sharedData = "shared document".getBytes();
		Hash sharedHash = Hashing.sha256(sharedData);

		s1.store(sharedHash, new ByteArrayInputStream(sharedData));
		s2.store(sharedHash, new ByteArrayInputStream(sharedData));

		// Each also stores unique content
		byte[] unique1 = "unique to s1".getBytes();
		byte[] unique2 = "unique to s2".getBytes();
		s1.store(Hashing.sha256(unique1), new ByteArrayInputStream(unique1));
		s2.store(Hashing.sha256(unique2), new ByteArrayInputStream(unique2));

		// After merge, shared content is not duplicated
		s1.merge(s2);
		assertEquals(3, s1.count(), "Shared content should be deduplicated");
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
