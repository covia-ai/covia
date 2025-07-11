package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import convex.core.data.Hash;

/**
 * Simple in-memory storage implementation for testing and demonstration.
 * This is not suitable for production use as data is lost when the application stops.
 */
public class MemoryStorage extends AStorage {
    
    private final Map<Hash, AContent> storage = new HashMap<>();
    
    @Override
    public void store(Hash hash, AContent content) throws IOException {
        if (hash == null) {
            throw new IllegalArgumentException("Hash cannot be null");
        }
        
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        
        storage.put(hash, content);
    }
     
    @Override
    public void store(Hash hash, InputStream inputStream) throws IOException  {
        if (!isInitialized()) {
            throw new IllegalStateException("Storage not initialized");
        }
        
        if (hash == null) {
            throw new IllegalArgumentException("Hash cannot be null");
        }
        
        if (inputStream == null) {
            throw new IllegalArgumentException("InputStream cannot be null");
        }
        
        storage.put(hash, BlobContent.from(inputStream));
    }
    
    @Override
    public AContent getContent(Hash hash) {
        if (!isInitialized()) {
            throw new IllegalStateException("Storage not initialized");
        }
        
        if (hash == null) {
            throw new IllegalArgumentException("Hash cannot be null");
        }
        
        return storage.get(hash);
    }
    
    @Override
    public boolean exists(Hash hash) {
        if (!isInitialized()) {
            return false;
        }
        
        return hash != null && storage.containsKey(hash);
    }
    
    @Override
    public boolean delete(Hash hash) throws IOException {
        if (!isInitialized()) {
            throw new IllegalStateException("Storage not initialized");
        }
        
        if (hash == null) {
            throw new IllegalArgumentException("Hash cannot be null");
        }
        
        return storage.remove(hash) != null;
    }
    
    @Override
    public long getSize(Hash hash) throws IllegalStateException {        
        AContent storedContent = getContent(hash);
        if (storedContent == null) {
            throw new IllegalStateException("Content does not exist for hash: " + hash);
        }
        return storedContent.getSize();
    }
    
    @Override
    public void initialize() {
        // no initialisation needed
    }
    
    @Override
    public void close() {
        storage.clear();
    }
    
    @Override
    public boolean isInitialized() {
        return true;
    }
    
    /**
     * Get the number of stored items.
     * @return The number of items in storage
     */
    public int size() {
        return storage.size();
    }
    
    /**
     * Clear all stored content.
     */
    public void clear() {
        storage.clear();
    }
} 