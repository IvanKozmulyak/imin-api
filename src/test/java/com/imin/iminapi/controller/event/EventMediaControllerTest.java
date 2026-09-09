package com.imin.iminapi.controller.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.MediaUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class EventMediaControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean MediaUploadService uploadService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubUser
    void post_poster_returns_url() throws Exception {
        UUID id = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "p.png", "image/png", new byte[]{(byte) 0x89, 'P','N','G'});
        when(uploadService.upload(any(), eq(id), eq(MediaKind.POSTER), any(), eq("image/png"), eq("p.png"), isNull(), isNull()))
                .thenReturn(new MediaUploadResponse("https://media.test/events/" + id + "/poster.png", 4, "image/png", null));

        mvc.perform(multipart("/api/v1/events/" + id + "/media/poster").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.durationSec").doesNotExist());
    }

    @Test
    @WithStubUser
    void post_video_returns_url_and_duration() throws Exception {
        UUID id = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[]{0,0,0,0});
        when(uploadService.upload(any(), eq(id), eq(MediaKind.VIDEO), any(), eq("video/mp4"), eq("v.mp4"), isNull(), isNull()))
                .thenReturn(new MediaUploadResponse("https://media.test/events/" + id + "/video.mp4", 4, "video/mp4", 12));

        mvc.perform(multipart("/api/v1/events/" + id + "/media/video").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationSec").value(12));
    }

    @Test
    @WithStubUser
    void uploadsDjPhotoKind() throws Exception {
        when(uploadService.upload(any(), eq(eventId), eq(MediaKind.DJ_PHOTO), any(), eq("image/png"), eq("dj.png"), isNull(), eq(Boolean.TRUE)))
                .thenReturn(new MediaUploadResponse("https://cdn.example/dj.png", 123L, "image/png", null));
        mvc.perform(multipart("/api/v1/events/" + eventId + "/media/dj-photo")
                        .file(new MockMultipartFile("file", "dj.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}))
                        .param("rightsAttested", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://cdn.example/dj.png"));
    }

    /**
     * AI Act Art.50: the Poster Studio uploads its own output through this same
     * endpoint, and the bytes cannot tell AI art from an organizer's artwork —
     * so the flag has to reach the service, not stop at the controller.
     */
    @Test
    @WithStubUser
    void poster_upload_forwards_the_ai_generated_flag() throws Exception {
        UUID id = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "p.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        when(uploadService.upload(any(), eq(id), eq(MediaKind.POSTER), any(), eq("image/png"),
                eq("p.png"), eq(Boolean.TRUE), isNull()))
                .thenReturn(new MediaUploadResponse("https://media.test/p.png", 4, "image/png", null));

        mvc.perform(multipart("/api/v1/events/" + id + "/media/poster")
                        .file(file)
                        .param("aiGenerated", "true"))
                .andExpect(status().isOk());

        verify(uploadService).upload(any(), eq(id), eq(MediaKind.POSTER), any(), eq("image/png"),
                eq("p.png"), eq(Boolean.TRUE), isNull());
    }

    /**
     * Rights attestation (droit à l'image). The gate itself lives in the service
     * — this asserts the flag survives the wire, which is the half a controller
     * can get wrong.
     */
    @Test
    @WithStubUser
    void dj_photo_upload_forwards_the_rights_attestation() throws Exception {
        when(uploadService.upload(any(), eq(eventId), eq(MediaKind.DJ_PHOTO), any(),
                eq("image/png"), eq("dj.png"), isNull(), eq(Boolean.TRUE)))
                .thenReturn(new MediaUploadResponse("https://cdn.example/dj.png", 123L, "image/png", null));

        mvc.perform(multipart("/api/v1/events/" + eventId + "/media/dj-photo")
                        .file(new MockMultipartFile("file", "dj.png", "image/png",
                                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}))
                        .param("rightsAttested", "true"))
                .andExpect(status().isOk());

        verify(uploadService).upload(any(), eq(eventId), eq(MediaKind.DJ_PHOTO), any(),
                eq("image/png"), eq("dj.png"), isNull(), eq(Boolean.TRUE));
    }

    /** No param at all still reaches the service, which is what refuses it. */
    @Test
    @WithStubUser
    void dj_photo_upload_without_the_param_passes_null_through() throws Exception {
        when(uploadService.upload(any(), eq(eventId), eq(MediaKind.DJ_PHOTO), any(),
                eq("image/png"), eq("dj.png"), isNull(), isNull()))
                .thenThrow(new com.imin.iminapi.security.ApiException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        com.imin.iminapi.security.ErrorCode.RIGHTS_ATTESTATION_REQUIRED,
                        "You must confirm you hold the rights to this image"));

        mvc.perform(multipart("/api/v1/events/" + eventId + "/media/dj-photo")
                        .file(new MockMultipartFile("file", "dj.png", "image/png",
                                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RIGHTS_ATTESTATION_REQUIRED"));
    }

    @Test
    @WithStubUser
    void unknownKindIsCleanNotFoundNot500() throws Exception {
        mvc.perform(multipart("/api/v1/events/" + eventId + "/media/banner")
                        .file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @WithStubUser
    void delete_returns_204() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/v1/events/" + id + "/media/poster"))
                .andExpect(status().isNoContent());
    }
    /**
     * api-13: spring.servlet.multipart.max-file-size is 60MB because the VIDEO kind needs it,
     * but a POSTER is capped at 5 MB — and the cap was only consulted inside
     * MediaUploadService.validate, i.e. AFTER {@code file.getBytes()} had already copied the
     * whole part onto the heap. A 60 MB poster upload therefore allocated 60 MB per concurrent
     * request to be told it was 12x over the limit. The response was always correct; what this
     * pins is the ORDER, which is the only thing that was wrong.
     */
    @Test
    void oversized_poster_is_rejected_before_the_part_is_copied_onto_the_heap() {
        MediaUploadService svc = org.mockito.Mockito.mock(MediaUploadService.class);
        EventMediaController controller = new EventMediaController(svc);
        AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());

        // Declares 60 MB but refuses to materialise: reaching getBytes() is the defect.
        MockMultipartFile huge = new MockMultipartFile("file", "big.png", "image/png", new byte[0]) {
            @Override public long getSize() { return 60L * 1024 * 1024; }
            @Override public byte[] getBytes() {
                throw new AssertionError("getBytes() ran before the per-kind size limit");
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller.upload(p, eventId, "poster", huge, null, null))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.FIELD_INVALID);

        org.mockito.Mockito.verifyNoInteractions(svc);
    }

}
