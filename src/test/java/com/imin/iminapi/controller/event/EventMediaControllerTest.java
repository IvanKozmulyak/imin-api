package com.imin.iminapi.controller.event;

import com.imin.iminapi.config.TestMediaStorageConfig;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestRateLimitConfig.class, TestMediaStorageConfig.class})
class EventMediaControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean MediaUploadService uploadService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

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
        when(uploadService.upload(any(), eq(id), eq(MediaKind.POSTER), any(), eq("image/png"), eq("p.png")))
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
        when(uploadService.upload(any(), eq(id), eq(MediaKind.VIDEO), any(), eq("video/mp4"), eq("v.mp4")))
                .thenReturn(new MediaUploadResponse("https://media.test/events/" + id + "/video.mp4", 4, "video/mp4", 12));

        mvc.perform(multipart("/api/v1/events/" + id + "/media/video").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationSec").value(12));
    }

    @Test
    @WithStubUser
    void delete_returns_204() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/v1/events/" + id + "/media/poster"))
                .andExpect(status().isNoContent());
    }
}
