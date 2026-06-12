package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MediaUploadServiceTest {

    EventRepository events = mock(EventRepository.class);
    InMemoryMediaStorage storage = new InMemoryMediaStorage("https://media.test/");
    VideoMetadata video = mock(VideoMetadata.class);
    MediaUploadService sut = new MediaUploadService(events, storage, video);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Event ev(UUID orgId) {
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(orgId);
        e.setName("X"); e.setSlug("x");
        return e;
    }

    @Test
    void poster_png_under_5mb_uploads_and_sets_url() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] png = pngBytes(1024);
        MediaUploadResponse r = sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, png, "image/png", "poster.png");

        assertThat(r.url())
                .startsWith("https://media.test/events/" + e.getId() + "/poster-")
                .endsWith(".png")
                .matches("https://media\\.test/events/" + e.getId() + "/poster-[0-9a-f]{16}\\.png");
        assertThat(r.contentType()).isEqualTo("image/png");
        assertThat(r.durationSec()).isNull();
        assertThat(e.getPosterUrl()).isEqualTo(r.url());
    }

    @Test
    void reupload_with_different_bytes_produces_different_url_and_deletes_old_object() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] first = pngBytes(1024);
        byte[] second = pngBytes(2048);
        // Make the two PNGs distinct beyond magic bytes — sha256 must diverge.
        second[100] = 0x42;

        MediaUploadResponse r1 = sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, first, "image/png", "a.png");
        MediaUploadResponse r2 = sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, second, "image/png", "b.png");

        assertThat(r1.url()).isNotEqualTo(r2.url());
        assertThat(e.getPosterUrl()).isEqualTo(r2.url());
        // The old object must be gone from storage; only the latest remains.
        assertThat(storage.blobs()).hasSize(1);
        assertThat(storage.blobs().keySet()).containsExactly(storage.keyFor(r2.url()));
    }

    @Test
    void reupload_with_identical_bytes_is_idempotent() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] bytes = pngBytes(1024);
        MediaUploadResponse r1 = sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, bytes, "image/png", "x.png");
        MediaUploadResponse r2 = sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, bytes, "image/png", "x.png");

        // Identical content hashes to the same key, so the URL is stable
        // (no orphans, no cache busting needed because nothing actually changed).
        assertThat(r1.url()).isEqualTo(r2.url());
        assertThat(storage.blobs()).hasSize(1);
    }

    @Test
    void delete_works_for_legacy_url_format_without_hash() {
        // Events that uploaded under the old key scheme (events/{id}/poster.png) must still
        // be deletable after the migration — keyFor strips the public prefix, which works
        // for both old and new URL shapes.
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        String legacyKey = "events/" + e.getId() + "/poster.png";
        e.setPosterUrl("https://media.test/" + legacyKey);
        storage.put(legacyKey, new byte[1], "image/png");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.delete(owner(orgId), e.getId(), MediaKind.POSTER);

        assertThat(e.getPosterUrl()).isNull();
        assertThat(storage.blobs()).isEmpty();
    }

    @Test
    void poster_above_5mb_returns_FIELD_INVALID() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        byte[] big = new byte[6 * 1024 * 1024];
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, big, "image/png", "p.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void poster_with_bad_mime_returns_FIELD_INVALID() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, new byte[10], "image/gif", "x.gif"))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void other_org_event_returns_NOT_FOUND() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(UUID.randomUUID()); // different org
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, pngBytes(10), "image/png", "p.png"))
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
    }

    @Test
    void poster_with_mismatched_magic_bytes_rejected() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        // PNG magic bytes but declared as JPEG
        byte[] fakePngAsJpeg = pngBytes(100);
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, fakePngAsJpeg, "image/jpeg", "poster.jpg"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void video_with_unreadable_duration_still_uploads() {
        // The jcodec probe returns null for many valid MP4s; an unknown duration
        // must not block the upload (duration is informational only).
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(video.probeMp4DurationSec(any())).thenReturn(null);

        byte[] mp4Bytes = mp4Bytes(200);
        MediaUploadResponse r = sut.upload(owner(orgId), e.getId(), MediaKind.VIDEO, mp4Bytes, "video/mp4", "v.mp4");

        assertThat(r.url()).endsWith(".mp4");
        assertThat(r.durationSec()).isNull();
        assertThat(e.getVideoUrl()).isEqualTo(r.url());
    }

    @Test
    void delete_clears_url_field_and_blob() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        e.setPosterUrl("https://media.test/events/" + e.getId() + "/poster.png");
        storage.put("events/" + e.getId() + "/poster.png", new byte[1], "image/png");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.delete(owner(orgId), e.getId(), MediaKind.POSTER);

        assertThat(e.getPosterUrl()).isNull();
        assertThat(storage.blobs()).isEmpty();
    }

    private static byte[] pngBytes(int size) {
        byte[] b = new byte[Math.max(size, 8)];
        b[0] = (byte) 0x89; b[1] = (byte) 0x50; b[2] = (byte) 0x4E; b[3] = (byte) 0x47;
        b[4] = (byte) 0x0D; b[5] = (byte) 0x0A; b[6] = (byte) 0x1A; b[7] = (byte) 0x0A;
        return b;
    }

    private static byte[] mp4Bytes(int size) {
        byte[] b = new byte[Math.max(size, 8)];
        // bytes 4..7 = "ftyp"
        b[4] = 'f'; b[5] = 't'; b[6] = 'y'; b[7] = 'p';
        return b;
    }

    // --- DJ photo kind ---

    private static byte[] realPng(int w, int h) throws java.io.IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void djPhotoUploadStoresUrlOnEvent() throws Exception {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] png = realPng(600, 800);
        MediaUploadResponse res = sut.upload(owner(orgId), e.getId(), MediaKind.DJ_PHOTO, png, "image/png", "dj.png");
        assertThat(res.url()).contains("/events/" + e.getId() + "/dj-photo-");
        assertThat(e.getDjPhotoUrl()).isEqualTo(res.url());
    }

    @Test
    void djPhotoRejectsTooSmallImage() throws Exception {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        byte[] png = realPng(200, 800); // short side 200 < 256
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.DJ_PHOTO, png, "image/png", "dj.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void djPhotoRejectsOversize() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        byte[] big = new byte[(int) (5 * 1024 * 1024) + 1];
        big[0] = (byte) 0x89; big[1] = 0x50; big[2] = 0x4E; big[3] = 0x47;
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.DJ_PHOTO, big, "image/png", "dj.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void djPhotoRejectsNonImageContentType() throws Exception {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        byte[] png = realPng(600, 800);
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.DJ_PHOTO, png, "image/webp", "dj.webp"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void djPhotoDeleteClearsUrl() throws Exception {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] png = realPng(600, 800);
        sut.upload(owner(orgId), e.getId(), MediaKind.DJ_PHOTO, png, "image/png", "dj.png");
        sut.delete(owner(orgId), e.getId(), MediaKind.DJ_PHOTO);
        assertThat(e.getDjPhotoUrl()).isNull();
    }

    @Test
    void mediaKindWireRoundTrip() {
        assertThat(MediaKind.fromWire("dj-photo")).isEqualTo(MediaKind.DJ_PHOTO);
        assertThat(MediaKind.DJ_PHOTO.wireValue()).isEqualTo("dj-photo");
    }
}
