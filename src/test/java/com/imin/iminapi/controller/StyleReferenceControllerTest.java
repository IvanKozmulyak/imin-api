package com.imin.iminapi.controller;

import com.imin.iminapi.config.SecurityConfig;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = StyleReferenceController.class,
        excludeAutoConfiguration = Saml2RelyingPartyAutoConfiguration.class
)
@Import(SecurityConfig.class)
class StyleReferenceControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ReferenceImageLibrary library;

    @Test
    void list_returnsCatalogWithImageUrls() throws Exception {
        when(library.tags()).thenReturn(List.of("neon_underground", "chrome_tropical"));
        when(library.referenceCount("neon_underground")).thenReturn(3);
        when(library.referenceCount("chrome_tropical")).thenReturn(2);

        mockMvc.perform(get("/api/posters/style-references"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].tag").value("neon_underground"))
                .andExpect(jsonPath("$[0].label").value("Neon Underground"))
                .andExpect(jsonPath("$[0].imageUrls").isArray())
                .andExpect(jsonPath("$[0].imageUrls.length()").value(3))
                .andExpect(jsonPath("$[0].imageUrls[0]")
                        .value("/api/posters/style-references/neon_underground/0"))
                .andExpect(jsonPath("$[1].imageUrls.length()").value(2));
    }

    @Test
    void image_validTagAndIndex_returnsPngBytes() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        when(library.loadBytes("neon_underground", 0)).thenReturn(png);

        mockMvc.perform(get("/api/posters/style-references/neon_underground/0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(png));
    }

    @Test
    void image_unknownTag_returns404() throws Exception {
        when(library.loadBytes("nope", 0))
                .thenThrow(new IllegalArgumentException("Unknown sub-style tag: nope"));

        mockMvc.perform(get("/api/posters/style-references/nope/0"))
                .andExpect(status().isNotFound());
    }

    @Test
    void image_indexOutOfRange_returns404() throws Exception {
        when(library.loadBytes("neon_underground", 99))
                .thenThrow(new IllegalArgumentException("Index 99 out of range"));

        mockMvc.perform(get("/api/posters/style-references/neon_underground/99"))
                .andExpect(status().isNotFound());
    }
}
