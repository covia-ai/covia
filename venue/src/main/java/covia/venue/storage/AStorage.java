package covia.venue.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import convex.core.crypto.Hashing;
import convex.core.data.Hash;
import covia.grid.AContent;

/**
 * Abstract base class for storage adapters.
 * Provides methods to store and retrieve content indexed by Hash.
 */
public abstract class AStorage {

    /** Refusal for content whose SHA-256 is not the hash it was offered under. */
    public static final String HASH_MISMATCH = "Content hash mismatch";

    /**
     * Store content read from a stream under {@code sha256}, but only if the
     * content's SHA-256 is that hash. Content that does not match is never
     * stored, and whatever was already stored under the hash is left as it was.
     *
     * <p>This default reads the whole content into memory to check it first.
     * Storage that can stage a write overrides it to check while streaming.
     *
     * @param sha256 The SHA-256 the content must have
     * @param inputStream The content
     * @throws IllegalArgumentException starting with {@link #HASH_MISMATCH} if the content does not match
     * @throws IOException if reading or storage fails
     */
    public void storeVerified(Hash sha256, InputStream inputStream) throws IOException {
        // Interim (covia#629): the whole content is held in memory. Convex's
        // streaming blob writer, digesting as it writes (Convex-Dev/convex#790),
        // replaces this for lattice-backed storage.
        byte[] data = inputStream.readAllBytes();
        checkHash(sha256, Hashing.sha256(data));
        store(sha256, new ByteArrayInputStream(data));
    }

    /**
     * Refuse content whose SHA-256 is not the expected hash.
     *
     * @throws IllegalArgumentException starting with {@link #HASH_MISMATCH} if they differ
     */
    protected static void checkHash(Hash expected, Hash actual) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(HASH_MISMATCH + ". Expected: " + expected.toHexString()
                + ", Actual: " + actual.toHexString());
        }
    }

    /**
     * Store content with the given hash as the key.
     * 
     * @param hash The hash to use as the key for storing the content
     * @param content The content to store
     * @throws IOException if storage fails
     */
    public abstract void store(Hash hash, AContent content) throws IOException;
    
    /**
     * Store content with the given hash as the key using an InputStream.
     * 
     * @param hash The hash to use as the key for storing the content
     * @param inputStream The input stream containing the content to store
     * @throws IOException if storage fails
     */
    public abstract void store(Hash hash, InputStream inputStream) throws IOException;
    
    /**
     * Retrieve content for the given hash.
     * 
     * @param hash The hash key to retrieve content for
     * @return The content associated with the hash, or null if not found
     * @throws IOException if retrieval fails
     */
    public abstract AContent getContent(Hash hash) throws IOException;
    
    /**
     * Check if content exists for the given hash.
     * 
     * @param hash The hash key to check
     * @return true if content exists for the hash, false otherwise
     */
    public abstract boolean exists(Hash hash);
    
    /**
     * Delete content for the given hash.
     * 
     * @param hash The hash key to delete content for
     * @return true if content was deleted, false if content didn't exist
     * @throws IOException if deletion fails
     */
    public abstract boolean delete(Hash hash) throws IOException;
    
    /**
     * Get the size of stored content for the given hash.
     * 
     * @param hash The hash key to get size for
     * @return The size in bytes
     * @throws IllegalStateException if content doesn't exist
     */
    public abstract long getSize(Hash hash) throws IllegalStateException;
    
    /**
     * Initialize the storage system.
     * This method should be called before using the storage.
     * 
     * @throws IOException if initialization fails
     */
    public abstract void initialise() throws IOException;
    
    /**
     * Close the storage system and release any resources.
     */
    public abstract void close();
    
    /**
     * Check if the storage system is initialized and ready for use.
     * 
     * @return true if the storage is ready, false otherwise
     */
    public abstract boolean isInitialised();
} 