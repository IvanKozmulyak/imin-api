package com.imin.iminapi.storage;

public interface MediaStorage {

    /**
     * Store {@code bytes} at {@code key} (e.g. "events/uuid/poster.png").
     * Returns the publicly-readable URL.
     */
    Stored put(String key, byte[] bytes, String contentType);

    void delete(String key);

    /**
     * Return the deterministic public URL for a given key without uploading.
     * Used to persist the URL in the DB before calling put, so a failed upload
     * does not orphan objects in remote storage.
     */
    String urlFor(String key);

    record Stored(String url, long sizeBytes, String contentType) {}
}
