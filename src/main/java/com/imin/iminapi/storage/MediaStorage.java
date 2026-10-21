package com.imin.iminapi.storage;

public interface MediaStorage {

    /**
     * Store {@code bytes} at {@code key} (e.g. "events/uuid/poster.png").
     * Returns the publicly-readable URL.
     */
    Stored put(String key, byte[] bytes, String contentType);

    void delete(String key);

    /** Public URL for a key, without I/O. */
    String urlFor(String key);

    /**
     * Inverse of {@link #urlFor(String)}: return the storage key for a public
     * URL produced by this storage, or {@code null} if {@code url} was not
     * produced here (different prefix). Used by callers that hold only the
     * stored URL and need to issue a {@link #delete(String)}.
     */
    String keyFor(String url);

    /**
     * The bytes stored at {@code key}, or {@code null} when nothing is there. Throws
     * {@link IllegalStateException} when the object is larger than {@code maxBytes}.
     */
    byte[] read(String key, long maxBytes);

    record Stored(String url, long sizeBytes, String contentType) {}
}
