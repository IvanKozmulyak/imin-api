package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.LogoUploadResponse;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrgMediaServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    InMemoryMediaStorage storage = new InMemoryMediaStorage("https://media.test/");
    OrgBrandService brandService = mock(OrgBrandService.class);
    com.imin.iminapi.service.poster.BrandLogoCompositor logoCompositor = mock(com.imin.iminapi.service.poster.BrandLogoCompositor.class);
    OrgMediaService sut = new OrgMediaService(orgs, storage, brandService, logoCompositor);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Organization org(UUID id) {
        Organization o = new Organization();
        o.setId(id); o.setName("Org"); o.setContactEmail("a@b.com"); o.setCountry("DE");
        return o;
    }

    /** A real PNG of the given dimensions (so ImageIO decode in the service succeeds). */
    private static byte[] png(int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void valid_square_png_uploads_and_sets_url() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        LogoUploadResponse r = sut.uploadLogo(owner(id), png(256, 256), "image/png", "logo.png");

        assertThat(r.logoUrl())
                .startsWith("https://media.test/orgs/" + id + "/brand/logo-")
                .endsWith(".png")
                .matches("https://media\\.test/orgs/" + id + "/brand/logo-[0-9a-f]{16}\\.png");
        verify(brandService).setLogoUrl(any(AuthPrincipal.class), eq(r.logoUrl()));
        assertThat(storage.blobs()).hasSize(1);
    }

    @Test
    void non_png_content_type_rejected() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
        assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(256, 256), "image/jpeg", "logo.jpg"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void png_declared_but_not_png_magic_rejected() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
        byte[] notPng = new byte[256];
        assertThatThrownBy(() -> sut.uploadLogo(owner(id), notPng, "image/png", "logo.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void over_2mb_rejected() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
        // valid PNG header but oversize body (size is checked before decode)
        byte[] big = png(256, 256);
        byte[] padded = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(big, 0, padded, 0, big.length);
        assertThatThrownBy(() -> sut.uploadLogo(owner(id), padded, "image/png", "logo.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void short_side_under_128_rejected() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
        assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(127, 256), "image/png", "logo.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void extreme_aspect_ratio_rejected() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
        // 128 x 640 = 1:5, outside the 1:4..4:1 band
        assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(128, 640), "image/png", "logo.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void four_to_one_aspect_is_allowed() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
        // 512 x 128 = exactly 4:1 — allowed
        LogoUploadResponse r = sut.uploadLogo(owner(id), png(512, 128), "image/png", "logo.png");
        assertThat(r.logoUrl()).endsWith(".png");
    }

    @Test
    void reupload_deletes_old_object_after_new_put() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        LogoUploadResponse r1 = sut.uploadLogo(owner(id), png(256, 256), "image/png", "a.png");
        // simulate the URL persisted (the real OrgBrandService.setLogoUrl would have)
        o.setBrandLogoUrl(r1.logoUrl());

        byte[] second = png(300, 300); // different bytes → different hash → different key
        LogoUploadResponse r2 = sut.uploadLogo(owner(id), second, "image/png", "b.png");

        assertThat(r1.logoUrl()).isNotEqualTo(r2.logoUrl());
        assertThat(storage.blobs()).hasSize(1);
        assertThat(storage.blobs().keySet()).containsExactly(storage.keyFor(r2.logoUrl()));
    }

    @Test
    void reupload_invalidates_old_logo_in_compositor_cache() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        LogoUploadResponse r1 = sut.uploadLogo(owner(id), png(256, 256), "image/png", "a.png");
        o.setBrandLogoUrl(r1.logoUrl());
        sut.uploadLogo(owner(id), png(300, 300), "image/png", "b.png");

        verify(logoCompositor).invalidate(r1.logoUrl());
    }

    @Test
    void deleteLogo_removes_blob_and_clears_url() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        String key = "orgs/" + id + "/brand/logo-aabbccdd.png";
        o.setBrandLogoUrl("https://media.test/" + key);
        storage.put(key, new byte[1], "image/png");
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        sut.deleteLogo(owner(id));

        verify(brandService).clearLogoUrl(any(AuthPrincipal.class));
        assertThat(storage.blobs()).isEmpty();
    }

    @Test
    void other_org_never_leaks_returns_NOT_FOUND_when_org_absent() {
        UUID id = UUID.randomUUID();
        when(orgs.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(256, 256), "image/png", "logo.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
    }
}
