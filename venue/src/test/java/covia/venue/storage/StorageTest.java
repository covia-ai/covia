package covia.venue.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.crypto.Hashing;

class StorageTest {
    
    private MemoryStorage storage;
    private Hash testHash;
    private Blob testBlob;
    private String testContent;
    
    @BeforeEach
    void setUp() throws IOException {
        storage = new MemoryStorage();
        storage.initialize();
        
        testContent = "Hello, World!";
        testBlob = Blob.wrap(testContent.getBytes());
        testHash = Hashing.sha256(testBlob.getBytes());
    }
    
    @Test
    void testStoreAndRetrieveContent() throws IOException {
        // Create content
        BlobContent content = new BlobContent(testBlob);
        
        // Store content
        storage.store(testHash, content);
        
        // Verify content exists
        assertTrue(storage.exists(testHash));
        
        // Retrieve content
        AContent retrieved = storage.getContent(testHash);
        assertNotNull(retrieved);
        
        // Verify content matches
        assertEquals(testBlob, retrieved.getBlob());
        assertEquals(testContent.length(), retrieved.getSize());
        
        // Verify input stream
        InputStream is = retrieved.getInputStream();
        assertNotNull(is);
        String retrievedContent = new String(is.readAllBytes());
        assertEquals(testContent, retrievedContent);
    }
    
    @Test
    void testStoreAndRetrieveInputStream() throws IOException {
        // Store content using InputStream
        InputStream inputStream = new ByteArrayInputStream(testContent.getBytes());
        storage.store(testHash, inputStream);
        
        // Verify content exists
        assertTrue(storage.exists(testHash));
        
        // Retrieve content
        AContent retrieved = storage.getContent(testHash);
        assertNotNull(retrieved);
        
        // Verify content matches
        assertEquals(testContent.length(), retrieved.getSize());
        
        // Verify input stream
        InputStream is = retrieved.getInputStream();
        assertNotNull(is);
        String retrievedContent = new String(is.readAllBytes());
        assertEquals(testContent, retrievedContent);
    }
    
    @Test
    void testDeleteContent() throws IOException {
        // Store content
        BlobContent content = new BlobContent(testBlob);
        storage.store(testHash, content);
        
        // Verify content exists
        assertTrue(storage.exists(testHash));
        
        // Delete content
        boolean deleted = storage.delete(testHash);
        assertTrue(deleted);
        
        // Verify content no longer exists
        assertFalse(storage.exists(testHash));
        assertNull(storage.getContent(testHash));
    }
    
    @Test
    void testGetSize() throws IOException {
        // Store content
        BlobContent content = new BlobContent(testBlob);
        storage.store(testHash, content);
        
        // Verify size
        assertEquals(testContent.length(), storage.getSize(testHash));
    }
    
    @Test
    void testNonExistentContent() {
        // Verify non-existent content
        assertFalse(storage.exists(testHash));
        assertThrows(IllegalStateException.class, () -> {
            storage.getSize(testHash);
        });
    }
    
    @Test
    void testNullParameters() throws IOException {
        // Test null hash
        assertThrows(IllegalArgumentException.class, () -> {
            storage.store(null, new BlobContent(testBlob));
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            storage.getContent(null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            storage.delete(null);
        });
        
        // Test null content
        assertThrows(IllegalArgumentException.class, () -> {
            storage.store(testHash, (AContent) null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            storage.store(testHash, (InputStream) null);
        });
    }
} 