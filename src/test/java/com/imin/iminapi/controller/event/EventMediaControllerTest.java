package com.imin.iminapi.controller.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.MediaUploadService;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Event media uploads over the real MediaUploadService and the in-memory storage. */
@IminIntegrationTest
class EventMediaControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired MediaUploadService uploadService;
    @Autowired InMemoryMediaStorage storage;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private boolean storedFor(Event e) {
        return storage.blobs().keySet().stream().anyMatch(k -> k.startsWith("events/" + e.getId() + "/"));
    }

    /**
     * The multipart ceiling is 60 MB for VIDEO, but a POSTER is capped at 5 MB: the cap must be checked on the
     * declared size before getBytes() copies the part onto the heap.
     */
    @Test
    void an_oversized_poster_is_rejected_before_the_part_is_copied_onto_the_heap() {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        MockMultipartFile huge = new MockMultipartFile("file", "big.png", "image/png", new byte[0]) {
            @Override public long getSize() { return 60L * 1024 * 1024; }
            @Override public byte[] getBytes() {
                throw new AssertionError("getBytes() ran before the per-kind size limit");
            }
        };

        assertThatThrownBy(() -> new EventMediaController(uploadService)
                        .upload(fx.principal(owner), e.getId(), "poster", huge, null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
        assertThat(storedFor(e)).isFalse();
    }

    @Test
    void an_unknown_media_kind_is_a_clean_404() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);

        mvc.perform(multipart("/api/v1/events/{id}/media/banner", e.getId())
                        .file(new MockMultipartFile("file", "x.png", "image/png", png(16, 16)))
                        .with(as(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(storedFor(e)).isFalse();
    }

    /** The AI-provenance flag (AI Act Art.50) and the DJ-photo rights attestation must survive the wire. */
    @ParameterizedTest
    @ValueSource(strings = {"ai-poster", "attested-dj-photo", "unattested-dj-photo"})
    void the_provenance_and_rights_flags_reach_the_stored_event(String row) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        String kind = row.equals("ai-poster") ? "poster" : "dj-photo";
        MockMultipartHttpServletRequestBuilder req = multipart("/api/v1/events/{id}/media/{kind}", e.getId(), kind);
        req.file(new MockMultipartFile("file", kind + ".png", "image/png", png(600, 800)));
        if (row.equals("ai-poster")) req.param("aiGenerated", "true");
        if (row.equals("attested-dj-photo")) req.param("rightsAttested", "true");

        if (row.equals("unattested-dj-photo")) {
            mvc.perform(req.with(as(owner)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("RIGHTS_ATTESTATION_REQUIRED"));
            assertThat(events.findById(e.getId()).orElseThrow().getDjPhotoUrl()).isNull();
            assertThat(storedFor(e)).isFalse();
            return;
        }
        mvc.perform(req.with(as(owner))).andExpect(status().isOk());

        Event after = events.findById(e.getId()).orElseThrow();
        if (row.equals("ai-poster")) {
            assertThat(after.getPosterUrl()).isNotNull();
            assertThat(after.getPosterAiGenerated()).isTrue();
        } else {
            assertThat(after.getDjPhotoUrl()).isNotNull();
            assertThat(after.getDjPhotoRightsAttestedAt()).isNotNull();
        }
    }

    @Test
    void deleting_an_own_poster_clears_it_and_its_object() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        mvc.perform(multipart("/api/v1/events/{id}/media/poster", e.getId())
                        .file(new MockMultipartFile("file", "p.png", "image/png", png(600, 800)))
                        .with(as(owner)))
                .andExpect(status().isOk());
        assertThat(storedFor(e)).isTrue();

        mvc.perform(delete("/api/v1/events/{id}/media/poster", e.getId()).with(as(owner)))
                .andExpect(status().isNoContent());

        assertThat(events.findById(e.getId()).orElseThrow().getPosterUrl()).isNull();
        assertThat(storedFor(e)).isFalse();
    }
}
