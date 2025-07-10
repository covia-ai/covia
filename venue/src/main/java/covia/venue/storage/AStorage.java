package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.Hash;

/**
 * Abstract base class for storage adapters.
 * Provides methods to store and retrieve content indexed by Hash.
 */
public abstract class AStorage {
    
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
     * @param contentType The content type/mime type of the content
     * @throws IOException if storage fails
     */
    public abstract void store(Hash hash, InputStream inputStream, String contentType) throws IOException;
    
    /**
     * Retrieve content for the given hash.
     * 
     * @param hash The hash key to retrieve content for
     * @return The content associated with the hash, or null if not found
     * @throws IOException if retrieval fails
     */
    public abstract AContent retrieve(Hash hash) throws IOException;
    
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
    public abstract void initialize() throws IOException;
    
    /**
     * Close the storage system and release any resources.
     * 
     * @throws IOException if closing fails
     */
    public abstract void close() throws IOException;
    
    /**
     * Check if the storage system is initialized and ready for use.
     * 
     * @return true if the storage is ready, false otherwise
     */
    public abstract boolean isInitialized();
} 