package com.imin.iminapi.service.poster;

import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PosterImageStorageTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MediaStorage> providerOf(MediaStorage media) {
        ObjectProvider<MediaStorage> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(media);
        return p;
    }

    @Test
    void writePng_usesObjectStorage_whenAvailable(@TempDir Path tmp) {
        InMemoryMediaStorage media = new InMemoryMediaStorage("https://cdn.example/");
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example", providerOf(media));

        String url = storage.writePng(new byte[]{1, 2, 3});

        assertThat(url).startsWith("https://cdn.example/ai-posters/").endsWith(".png");
        assertThat(media.blobs()).hasSize(1);
    }

    @Test
    void writePng_fallsBackToAbsoluteLocalUrl_whenNoObjectStorage(@TempDir Path tmp) {
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example/", providerOf(null));

        String url = storage.writePng(new byte[]{1, 2, 3});

        // Absolute (API public base), not a bare relative /images path the FE origin can't resolve.
        assertThat(url).startsWith("https://api.example/images/").endsWith(".png");
    }
}
