package covia.venue.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.Hash;
import convex.core.crypto.Hashing;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import covia.grid.AContent;

/**
 * Tests for FileStorage - filesystem-based content storage.
 */
public class FileStorageTest {

	@TempDir
	Path tempDir;

	private FileStorage storage;

	@BeforeEach
	public void setup() throws IOException {
		storage = new FileStorage(tempDir);
	}

	@AfterEach
	public void teardown() {
		if (storage != null) {
			storage.close();
		}
	}

	// ========== Basic Operations ==========

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

	// ========== File Naming ==========

	@Test
	public void testFileNameIsLowercaseHex() throws IOException {
		byte[] data = "naming test".getBytes();
		Hash hash = Hashing.sha256(data);

		storage.store(hash, new ByteArrayInputStream(data));

		String expectedFileName = hash.toHexString().toLowerCase();
		Path expectedPath = tempDir.resolve(expectedFileName);
		assertTrue(Files.exists(expectedPath), "File should exist at lowercase hex path");
	}

	// ========== Constructor Validation ==========

	@Test
	public void testConstructorThrowsForNonExistentDirectory() {
		Path nonExistent = tempDir.resolve("does-not-exist");
		assertThrows(IllegalArgumentException.class, () -> new FileStorage(nonExistent));
	}

	@Test
	public void testConstructorThrowsForFile() throws IOException {
		Path file = tempDir.resolve("not-a-directory.txt");
		Files.writeString(file, "I am a file");
		assertThrows(IllegalArgumentException.class, () -> new FileStorage(file));
	}

	@Test
	public void testConstructorNullPath() {
		assertThrows(IllegalArgumentException.class, () -> new FileStorage((Path) null));
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
			storage.store(hash, (java.io.InputStream) null));
	}

	@Test
	public void testGetContentNullHash() {
		assertThrows(IllegalArgumentException.class, () -> storage.getContent(null));
	}

	@Test
	public void testExistsNullHash() {
		assertFalse(storage.exists(null));
	}

	// ========== Count ==========

	@Test
	public void testCount() throws IOException {
		assertEquals(0, storage.count());

		byte[] data1 = "data1".getBytes();
		byte[] data2 = "data2".getBytes();
		Hash h1 = Hashing.sha256(data1);
		Hash h2 = Hashing.sha256(data2);

		storage.store(h1, new ByteArrayInputStream(data1));
		assertEquals(1, storage.count());

		storage.store(h2, new ByteArrayInputStream(data2));
		assertEquals(2, storage.count());
	}

	// ========== Content Integrity ==========

	@Test
	public void testContentIntegrity() throws IOException {
		byte[] originalData = "The quick brown fox jumps over the lazy dog".getBytes();
		Hash hash = Hashing.sha256(originalData);

		storage.store(hash, new ByteArrayInputStream(originalData));

		AContent retrieved = storage.getContent(hash);
		assertNotNull(retrieved);

		byte[] retrievedData = retrieved.getBlob().getBytes();
		assertEquals(originalData.length, retrievedData.length);
		for (int i = 0; i < originalData.length; i++) {
			assertEquals(originalData[i], retrievedData[i], "Byte mismatch at index " + i);
		}
	}

	@Test
	public void testOverwrite() throws IOException {
		Hash hash = Hash.fromHex("6666666666666666666666666666666666666666666666666666666666666666");
		byte[] data1 = "first content".getBytes();
		byte[] data2 = "second content".getBytes();

		storage.store(hash, new ByteArrayInputStream(data1));
		storage.store(hash, new ByteArrayInputStream(data2));

		AContent content = storage.getContent(hash);
		assertEquals(data2.length, content.getSize());
	}

	// ========== isInitialised ==========

	@Test
	public void testIsInitialised() {
		assertTrue(storage.isInitialised());
	}

	// ========== String Constructor ==========

	@Test
	public void testStringPathConstructor() throws IOException {
		FileStorage stringStorage = new FileStorage(tempDir.toString());

		byte[] data = "string constructor test".getBytes();
		Hash hash = Hashing.sha256(data);
		stringStorage.store(hash, new ByteArrayInputStream(data));

		assertTrue(stringStorage.exists(hash));
		stringStorage.close();
	}

	// ========== ToString ==========

	@Test
	public void testToString() throws IOException {
		String str = storage.toString();
		assertTrue(str.contains("FileStorage"));
		assertTrue(str.contains("0 files"));

		byte[] data = "test".getBytes();
		storage.store(Hashing.sha256(data), new ByteArrayInputStream(data));

		str = storage.toString();
		assertTrue(str.contains("1 files"));
	}

	// ========== DLFS In-Memory FileSystem ==========

	@Test
	public void testWithDLFSInMemoryFileSystem() throws IOException {
		// Create an in-memory DLFS filesystem
		DLFileSystem dlfs = DLFS.createLocal();
		Path root = dlfs.getRoot();

		// Create a directory for storage
		Path storageDir = Files.createDirectory(root.resolve("content"));

		// Create FileStorage backed by DLFS
		FileStorage dlfsStorage = new FileStorage(storageDir);

		// Store content
		byte[] data1 = "hello from DLFS".getBytes();
		byte[] data2 = "another file in memory".getBytes();
		Hash h1 = Hashing.sha256(data1);
		Hash h2 = Hashing.sha256(data2);

		dlfsStorage.store(h1, new ByteArrayInputStream(data1));
		dlfsStorage.store(h2, new ByteArrayInputStream(data2));

		// Verify content exists
		assertTrue(dlfsStorage.exists(h1));
		assertTrue(dlfsStorage.exists(h2));
		assertEquals(2, dlfsStorage.count());

		// Verify content can be retrieved
		AContent content1 = dlfsStorage.getContent(h1);
		assertNotNull(content1);
		assertEquals(data1.length, content1.getSize());

		byte[] retrieved = content1.getBlob().getBytes();
		for (int i = 0; i < data1.length; i++) {
			assertEquals(data1[i], retrieved[i], "Byte mismatch at index " + i);
		}

		// Verify files exist in the DLFS filesystem
		Path file1Path = storageDir.resolve(h1.toHexString());
		Path file2Path = storageDir.resolve(h2.toHexString());
		assertTrue(Files.exists(file1Path), "File should exist in DLFS");
		assertTrue(Files.exists(file2Path), "File should exist in DLFS");
		assertEquals(data1.length, Files.size(file1Path));
		assertEquals(data2.length, Files.size(file2Path));

		// Delete content
		assertTrue(dlfsStorage.delete(h1));
		assertFalse(dlfsStorage.exists(h1));
		assertFalse(Files.exists(file1Path), "File should be deleted from DLFS");

		dlfsStorage.close();
	}

	@Test
	public void testDLFSLargeFile() throws IOException {
		// Create an in-memory DLFS filesystem
		DLFileSystem dlfs = DLFS.createLocal();
		Path storageDir = Files.createDirectory(dlfs.getRoot().resolve("large-content"));
		FileStorage dlfsStorage = new FileStorage(storageDir);

		// Create larger content (10KB)
		byte[] largeData = new byte[10240];
		for (int i = 0; i < largeData.length; i++) {
			largeData[i] = (byte) (i % 256);
		}
		Hash hash = Hashing.sha256(largeData);

		// Store and retrieve
		dlfsStorage.store(hash, new ByteArrayInputStream(largeData));

		AContent content = dlfsStorage.getContent(hash);
		assertNotNull(content);
		assertEquals(largeData.length, content.getSize());

		byte[] retrieved = content.getBlob().getBytes();
		assertEquals(largeData.length, retrieved.length);
		for (int i = 0; i < largeData.length; i++) {
			assertEquals(largeData[i], retrieved[i], "Byte mismatch at index " + i);
		}

		dlfsStorage.close();
	}
}
