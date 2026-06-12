package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.org.BrandBookDto;
import com.imin.iminapi.dto.org.LogoUploadResponse;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.org.OrgBrandService;
import com.imin.iminapi.service.org.OrgMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class OrgBrandControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean OrgBrandService brandService;
    @MockitoBean OrgMediaService mediaService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubAuthFactory.class)
    public @interface WithStubUser {}

    public static class StubAuthFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override
        public SecurityContext createSecurityContext(WithStubUser annotation) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    void get_without_token_returns_AUTH_MISSING() throws Exception {
        mvc.perform(get("/api/v1/org/brand"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    @Test
    @WithStubUser
    void get_returns_brand() throws Exception {
        when(brandService.get(any(AuthPrincipal.class))).thenReturn(
                new BrandBookDto("Tortuga Collective", "https://cdn/logo.png",
                        List.of("#ec4899", "#f6c04a"), true));
        mvc.perform(get("/api/v1/org/brand"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").value("Tortuga Collective"))
                .andExpect(jsonPath("$.accentColors[0]").value("#ec4899"))
                .andExpect(jsonPath("$.logoOnPosters").value(true));
    }

    @Test
    @WithStubUser
    void put_returns_updated_brand() throws Exception {
        when(brandService.put(any(AuthPrincipal.class), any())).thenReturn(
                new BrandBookDto("Tortuga Collective", null, List.of("#ec4899"), false));
        mvc.perform(put("/api/v1/org/brand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "brandName", "Tortuga Collective",
                                "accentColors", List.of("#ec4899"),
                                "logoOnPosters", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoOnPosters").value(false));
    }

    @Test
    @WithStubUser
    void put_invalid_hex_surfaces_per_index_field_key() throws Exception {
        when(brandService.put(any(AuthPrincipal.class), any())).thenThrow(
                new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                        Map.of("accentColors[1]", "must be a 6-digit hex colour like #ec4899")));
        mvc.perform(put("/api/v1/org/brand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "accentColors", List.of("#ec4899", "nope", "#a78bfa")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields['accentColors[1]']").exists());
    }

    @Test
    @WithStubUser
    void post_logo_multipart_returns_url() throws Exception {
        when(mediaService.uploadLogo(any(AuthPrincipal.class), any(), any(), any()))
                .thenReturn(new LogoUploadResponse("https://cdn/orgs/x/brand/logo-aabbccdd.png"));
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/v1/org/brand/logo").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value("https://cdn/orgs/x/brand/logo-aabbccdd.png"));
    }

    @Test
    @WithStubUser
    void delete_logo_returns_204() throws Exception {
        mvc.perform(delete("/api/v1/org/brand/logo"))
                .andExpect(status().isNoContent());
        verify(mediaService).deleteLogo(any(AuthPrincipal.class));
    }
}
