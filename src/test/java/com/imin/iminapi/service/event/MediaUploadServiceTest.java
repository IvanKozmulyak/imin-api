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

        assertThat(r.url()).startsWith("https://media.test/events/").endsWith("/poster.png");
        assertThat(r.contentType()).isEqualTo("image/png");
        assertThat(r.durationSec()).isNull();
        assertThat(e.getPosterUrl()).isEqualTo(r.url());
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
    void video_with_unreadable_duration_rejected() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(video.probeMp4DurationSec(any())).thenReturn(null);

        byte[] mp4Bytes = mp4Bytes(200);
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.VIDEO, mp4Bytes, "video/mp4", "v.mp4"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
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
}
