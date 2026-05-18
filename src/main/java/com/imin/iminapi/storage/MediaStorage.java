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

    /**
     * Inverse of {@link #urlFor(String)}: return the storage key for a public
     * URL produced by this storage, or {@code null} if {@code url} was not
     * produced here (different prefix). Used by callers that hold only the
     * stored URL and need to issue a {@link #delete(String)}.
     */
    String keyFor(String url);

    record Stored(String url, long sizeBytes, String contentType) {}
}
