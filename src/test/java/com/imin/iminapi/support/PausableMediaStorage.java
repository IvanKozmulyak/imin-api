package com.imin.iminapi.support;

import com.imin.iminapi.storage.InMemoryMediaStorage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The R2 boundary fake: a test can hold one storage call open to prove no row lock spans it.
 * Inert unless armed; {@link #reset()} runs after every test and releases any held call.
 */
public class PausableMediaStorage extends InMemoryMediaStorage {

    /** {@code entered} counts down when the held call starts; count {@code release} down to let it finish. */
    public record Pause(CountDownLatch entered, CountDownLatch release) {}

    private Pause nextPut;
    private Pause nextDelete;
    private final List<Pause> armed = new ArrayList<>();
    private final Set<Pause> cancelled = new HashSet<>();

    public PausableMediaStorage(String publicPrefix) {
        super(publicPrefix);
    }

    /** Holds the next {@code put} only; later calls run straight through. */
    public synchronized Pause pauseNextPut() {
        nextPut = arm();
        return nextPut;
    }

    /** Holds the next {@code delete} only; later calls run straight through. */
    public synchronized Pause pauseNextDelete() {
        nextDelete = arm();
        return nextDelete;
    }

    /** Disarms both hooks and releases any call still held; a call released here is dropped, not run. */
    public synchronized void reset() {
        nextPut = null;
        nextDelete = null;
        cancelled.addAll(armed);
        armed.forEach(p -> p.release().countDown());
        armed.clear();
    }

    @Override
    public Stored put(String key, byte[] bytes, String contentType) {
        Pause p = takePut();
        hold(p);
        synchronized (this) {
            // A call reset() let go belongs to a finished test; storing it would leak into the next one.
            armed.remove(p);
            if (p != null && cancelled.remove(p)) return new Stored(urlFor(key), bytes.length, contentType);
            return super.put(key, bytes, contentType);
        }
    }

    @Override
    public void delete(String key) {
        Pause p = takeDelete();
        hold(p);
        synchronized (this) {
            armed.remove(p);
            if (p != null && cancelled.remove(p)) return;
            super.delete(key);
        }
    }

    private Pause arm() {
        Pause p = new Pause(new CountDownLatch(1), new CountDownLatch(1));
        armed.add(p);
        return p;
    }

    private synchronized Pause takePut() {
        Pause p = nextPut;
        nextPut = null;
        return p;
    }

    private synchronized Pause takeDelete() {
        Pause p = nextDelete;
        nextDelete = null;
        return p;
    }

    /** Runs outside the monitor, so reset() can release a held call. */
    private static void hold(Pause p) {
        if (p == null) return;
        p.entered().countDown();
        try {
            if (!p.release().await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("held media call was not released within 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while a media call was held", e);
        }
    }
}
