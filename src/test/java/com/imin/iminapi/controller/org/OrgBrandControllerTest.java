package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The brand book over the real OrgBrandService. */
@IminIntegrationTest
class OrgBrandControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;

    private final ObjectMapper om = new ObjectMapper();

    /** The dashboard highlights the one bad swatch, so the error names its index. */
    @Test
    void an_invalid_hex_surfaces_its_per_index_field_key() throws Exception {
        User owner = fx.owner(fx.org());

        mvc.perform(put("/api/v1/org/brand")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "accentColors", List.of("#ec4899", "nope", "#a78bfa")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields['accentColors[1]']").exists())
                .andExpect(jsonPath("$.error.fields['accentColors[0]']").doesNotExist())
                .andExpect(jsonPath("$.error.fields['accentColors[2]']").doesNotExist());
    }

    @Test
    void an_owner_saves_the_brand_and_uploads_then_removes_the_logo() throws Exception {
        User owner = fx.owner(fx.org());
        var auth = authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

        mvc.perform(put("/api/v1/org/brand").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("brandName", "Tortuga Collective",
                                "accentColors", List.of("#ec4899"), "logoOnPosters", false))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/org/brand").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").value("Tortuga Collective"))
                .andExpect(jsonPath("$.accentColors[0]").value("#ec4899"))
                .andExpect(jsonPath("$.logoOnPosters").value(false));

        String logoUrl = JsonPath.read(mvc.perform(multipart("/api/v1/org/brand/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", png())).with(auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.logoUrl");
        assertThat(logoUrl).contains("orgs/" + owner.getOrgId() + "/brand/logo-");

        mvc.perform(delete("/api/v1/org/brand/logo").with(auth)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/org/brand").with(auth))
                .andExpect(jsonPath("$.logoUrl").value(nullValue()));
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
