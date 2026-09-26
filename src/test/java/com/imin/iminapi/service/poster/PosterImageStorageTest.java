package com.imin.iminapi.service.poster;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.service.ai.provenance.ImageAiMarker;
import com.imin.iminapi.service.ai.provenance.ImageAiMarkerTest;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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

        String url = storage.writePng(ImageAiMarkerTest.png(), ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA);

        assertThat(url).startsWith("https://cdn.example/ai-posters/").endsWith(".png");
        assertThat(media.blobs()).hasSize(1);
        byte[] stored = media.blobs().values().iterator().next();
        assertThat(ImageAiMarker.readDigitalSourceType(stored))
                .isEqualTo(ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA.uri());
    }

    @Test
    void writePng_fallsBackToAbsoluteLocalUrl_whenNoObjectStorage(@TempDir Path tmp) {
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example/", providerOf(null));

        String url = storage.writePng(ImageAiMarkerTest.png(),
                ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA);

        // Absolute (API public base), not a bare relative /images path the FE origin can't resolve.
        assertThat(url).startsWith("https://api.example/images/").endsWith(".png");
    }

    @Test
    void writePng_localFallbackFileCarriesTheMarker(@TempDir Path tmp) throws Exception {
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "", providerOf(null));

        String url = storage.writePng(ImageAiMarkerTest.png(),
                ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA);

        byte[] written = Files.readAllBytes(tmp.resolve(url.substring(url.lastIndexOf('/') + 1)));
        assertThat(ImageAiMarker.readDigitalSourceType(written))
                .isEqualTo(ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA.uri());
    }

    @Test
    void writePng_marksAJpegInPlace(@TempDir Path tmp) {
        InMemoryMediaStorage media = new InMemoryMediaStorage("https://cdn.example/");
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example", providerOf(media));

        storage.writePng(ImageAiMarkerTest.jpeg(), ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA);

        byte[] stored = media.blobs().values().iterator().next();
        assertThat(stored[0] & 0xFF).isEqualTo(0xFF);
        assertThat(stored[1] & 0xFF).isEqualTo(0xD8);
        assertThat(ImageAiMarker.readDigitalSourceType(stored))
                .isEqualTo(ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA.uri());
    }

    @Test
    void writePng_transcodesAnotherDecodableFormatToPng_andMarksIt(@TempDir Path tmp) throws Exception {
        InMemoryMediaStorage media = new InMemoryMediaStorage("https://cdn.example/");
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example", providerOf(media));
        byte[] bmp = ImageAiMarkerTest.bmp();
        assertThat(ImageAiMarker.supports(bmp)).isFalse();

        storage.writePng(bmp, ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA);

        byte[] stored = media.blobs().values().iterator().next();
        assertThat(Arrays.copyOf(stored, 4)).isEqualTo(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertThat(ImageAiMarker.readDigitalSourceType(stored))
                .isEqualTo(ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA.uri());
        BufferedImage before = ImageIO.read(new ByteArrayInputStream(bmp));
        BufferedImage after = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(after.getRGB(3, 2)).isEqualTo(before.getRGB(3, 2));
    }

    @Test
    void writePng_storesAnUndecodableImageUnmarked_withAWarning(@TempDir Path tmp) {
        InMemoryMediaStorage media = new InMemoryMediaStorage("https://cdn.example/");
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example", providerOf(media));
        byte[] undecodable = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        Logger logger = (Logger) LoggerFactory.getLogger(PosterImageStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String url = storage.writePng(undecodable, ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA);

            assertThat(url).startsWith("https://cdn.example/ai-posters/");
            assertThat(media.blobs().values().iterator().next()).isEqualTo(undecodable);
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("WITHOUT the machine-readable AI marker");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void writePng_storesACorruptPngUnmarked_insteadOfFailingTheVariant(@TempDir Path tmp) {
        InMemoryMediaStorage media = new InMemoryMediaStorage("https://cdn.example/");
        PosterImageStorage storage = new PosterImageStorage(tmp.toString(), "https://api.example", providerOf(media));
        byte[] truncated = Arrays.copyOf(ImageAiMarkerTest.png(), 20);

        storage.writePng(truncated, ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA);

        assertThat(media.blobs().values().iterator().next()).isEqualTo(truncated);
    }
}
