package com.imin.iminapi.storage;

import java.util.HashMap;
import java.util.Map;

public class InMemoryMediaStorage implements MediaStorage {

    private final Map<String, byte[]> blobs = new HashMap<>();
    private final String publicPrefix;

    public InMemoryMediaStorage(String publicPrefix) {
        this.publicPrefix = publicPrefix.endsWith("/") ? publicPrefix : publicPrefix + "/";
    }

    @Override
    public Stored put(String key, byte[] bytes, String contentType) {
        blobs.put(key, bytes);
        return new Stored(urlFor(key), bytes.length, contentType);
    }

    @Override
    public void delete(String key) { blobs.remove(key); }

    @Override
    public String urlFor(String key) { return publicPrefix + key; }

    public Map<String, byte[]> blobs() { return blobs; }
}
