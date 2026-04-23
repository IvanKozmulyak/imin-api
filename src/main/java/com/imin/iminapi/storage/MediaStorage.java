package com.imin.iminapi.storage;

public interface MediaStorage {

    /**
     * Store {@code bytes} at {@code key} (e.g. "events/uuid/poster.png").
     * Returns the publicly-readable URL.
     */
    Stored put(String key, byte[] bytes, String contentType);

    void delete(String key);

    record Stored(String url, long sizeBytes, String contentType) {}
}
