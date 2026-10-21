package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.service.ai.provenance.ImageAiMarker;
import com.imin.iminapi.service.poster.PosterImageStorage;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KitImageStorageTest {

    @TempDir Path tmp;
    InMemoryMediaStorage media;
    KitImageStorage storage;
    ObjectProvider<com.imin.iminapi.storage.MediaStorage> provider;
    PosterImageStorage posters;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        media = new InMemoryMediaStorage("https://cdn.example/");
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(media);
        posters = new PosterImageStorage(tmp.toString(), "https://api.example", provider);
        storage = new KitImageStorage(provider, posters, 1_000_000);
    }

    private static byte[] image(String format, int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    // The marker must be on kits made from an AI poster and absent from kits made from an organizer's upload.
    @Test
    void aiMarkerIsWrittenOnlyWhenThePosterIsAi() throws IOException {
        byte[] jpeg = image("jpg", 40, 40);
        UUID eventId = UUID.randomUUID();

        KitImageStorage.Uploaded ai = storage.put(eventId, "story", jpeg, true);
        KitImageStorage.Uploaded human = storage.put(eventId, "story", jpeg, false);

        assertThat(ImageAiMarker.readDigitalSourceType(media.blobs().get(ai.storageKey())))
                .isEqualTo(ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA.uri());
        assertThat(ImageAiMarker.readDigitalSourceType(media.blobs().get(human.storageKey()))).isNull();
    }

    // A key under ai-posters/ would make isAiGeneratedPosterUrl call a human's poster derivative a real AI render.
    @Test
    void keysAreUnderTheKitPrefixSoTheyAreNeverTakenForAiRenders() throws IOException {
        KitImageStorage.Uploaded up = storage.put(UUID.randomUUID(), "d7", image("jpg", 40, 40), true);

        assertThat(up.storageKey()).startsWith("story-kits/");
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl(up.url())).isFalse();
    }

    @Test
    void onlyOurOwnStorageIsReadable() {
        assertThat(storage.isOwnPoster("https://cdn.example/events/a/poster.png")).isTrue();
        assertThat(storage.isOwnPoster("https://api.example/images/../../etc/passwd")).isFalse();
        assertThatThrownBy(() -> storage.loadPoster("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(KitImageStorage.PosterUnreadableException.class);
    }

    // A tiny file can declare a huge canvas; the header is checked before any pixel is decoded.
    @Test
    void anOversizedPosterIsRefusedBeforeDecoding() throws IOException {
        media.put("events/a/wide.png", image("png", KitImageStorage.MAX_SIDE + 1, 1), "image/png");
        media.put("events/a/ok.png", image("png", 300, 400), "image/png");

        assertThatThrownBy(() -> storage.loadPoster("https://cdn.example/events/a/wide.png"))
                .isInstanceOf(KitImageStorage.PosterUnreadableException.class);
        assertThat(storage.loadPoster("https://cdn.example/events/a/ok.png").getHeight()).isEqualTo(400);
    }

    // A big upload is decoded near its drawn size (never under the 1350 px post), so a render holds no full-size copy.
    @ParameterizedTest
    @CsvSource({"6000, 2000", "2400, 2400", "1000, 1000"})
    void aLargePosterIsDecodedNearItsDrawnSizeAsRgb(int longSide, int decodedLongSide) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(longSide, longSide * 2 / 3, BufferedImage.TYPE_BYTE_GRAY), "png", png);
        media.put("events/a/large.png", png.toByteArray(), "image/png");

        BufferedImage decoded = storage.loadPoster("https://cdn.example/events/a/large.png");

        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(decodedLongSide);
        assertThat(decoded.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
    }

    // A valid image that is merely too many bytes is refused, not decoded.
    @Test
    void aPosterOverTheByteCapIsRefused() throws IOException {
        BufferedImage noise = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(1);
        for (int x = 0; x < 120; x++) for (int y = 0; y < 120; y++) noise.setRGB(x, y, random.nextInt());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", png);
        media.put("events/a/big.png", png.toByteArray(), "image/png");
        KitImageStorage tight = new KitImageStorage(provider, posters, 5_000);

        assertThat(png.size()).isGreaterThan(5_000);
        assertThatThrownBy(() -> tight.loadPoster("https://cdn.example/events/a/big.png"))
                .isInstanceOf(KitImageStorage.PosterUnreadableException.class);
        assertThat(storage.loadPoster("https://cdn.example/events/a/big.png").getWidth()).isEqualTo(120);
    }
}
