package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecraftClientTest {

    private static final String PNG_B64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});

    private record Harness(RecraftClient client, MockRestServiceServer server) {}

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://external.api.recraft.ai/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecraftClient client = new RecraftClient(builder.build(), "recraftv3", "realistic_image");
        return new Harness(client, server);
    }

    @Test
    void generate_withStyleId_sendsStyleIdAndModelAndDecodesB64() {
        Harness h = harness();
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/images/generations"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.prompt").value("a \"VOID\" poster"))
                .andExpect(jsonPath("$.model").value("recraftv3"))
                .andExpect(jsonPath("$.size").value("1024x1365"))
                .andExpect(jsonPath("$.n").value(1))
                .andExpect(jsonPath("$.response_format").value("b64_json"))
                .andExpect(jsonPath("$.image_format").value("png"))
                .andExpect(jsonPath("$.style_id").value("style-abc-123"))
                .andExpect(jsonPath("$.style").doesNotExist())
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}",
                        MediaType.APPLICATION_JSON));

        RecraftClient.RecraftResult result = h.client().generate(
                "a \"VOID\" poster", "4:5", RecraftClient.RenderSpec.trained("style-abc-123"),
                List.of(new byte[]{9, 9}));

        assertThat(result.imageBytes()).containsExactly(1, 2, 3, 4);
        assertThat(result.model()).isEqualTo("recraftv3");
        h.server().verify();
    }

    @Test
    void generate_withoutStyleId_fallsBackToBaseStyle() {
        Harness h = harness();
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/images/generations"))
                .andExpect(jsonPath("$.style").value("realistic_image"))
                .andExpect(jsonPath("$.style_id").doesNotExist())
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}",
                        MediaType.APPLICATION_JSON));

        RecraftClient.RecraftResult result = h.client().generate(
                "p", "1:1", RecraftClient.RenderSpec.trained(null), List.of());

        assertThat(result.imageBytes()).containsExactly(1, 2, 3, 4);
        h.server().verify();
    }

    @Test
    void generate_curatedSubstyle_sendsStyleSubstyleAndColorControls() {
        Harness h = harness();
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/images/generations"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.style").value("realistic_image"))
                .andExpect(jsonPath("$.substyle").value("hard_flash"))
                .andExpect(jsonPath("$.style_id").doesNotExist())
                .andExpect(jsonPath("$.controls.artistic_level").value(4))
                .andExpect(jsonPath("$.controls.colors[0].rgb[0]").value(12))
                .andExpect(jsonPath("$.controls.colors[0].rgb[1]").value(13))
                .andExpect(jsonPath("$.controls.colors[0].rgb[2]").value(14))
                .andExpect(jsonPath("$.controls.colors[1].rgb[0]").value(255))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}",
                        MediaType.APPLICATION_JSON));

        RecraftClient.RecraftResult result = h.client().generate(
                "flash club photo", "4:5",
                RecraftClient.RenderSpec.curated("hard_flash",
                        List.of(new com.imin.iminapi.dto.Rgb(12, 13, 14), new com.imin.iminapi.dto.Rgb(255, 84, 0))),
                List.of());

        assertThat(result.imageBytes()).containsExactly(1, 2, 3, 4);
        h.server().verify();
    }

    @Test
    void createStyle_postsMultipartAndReturnsId() {
        Harness h = harness();
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/styles"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("{\"id\":\"trained-style-42\"}", MediaType.APPLICATION_JSON));

        String id = h.client().createStyle(List.of(new byte[]{1}, new byte[]{2}));

        assertThat(id).isEqualTo("trained-style-42");
        h.server().verify();
    }

    @Test
    void createStyle_webpBytes_useWebpFilenameAndContentType() {
        Harness h = harness();
        // RIFF....WEBP magic header
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1, 2, 3, 4};
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/styles"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"ref_0.webp\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image/webp")))
                .andRespond(withSuccess("{\"id\":\"s1\"}", MediaType.APPLICATION_JSON));

        String id = h.client().createStyle(List.of(webp));

        assertThat(id).isEqualTo("s1");
        h.server().verify();
    }

    @Test
    void createStyle_jpegBytes_useJpgFilenameAndContentType() {
        Harness h = harness();
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/styles"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"ref_0.jpg\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image/jpeg")))
                .andRespond(withSuccess("{\"id\":\"s1\"}", MediaType.APPLICATION_JSON));

        h.client().createStyle(List.of(jpeg));

        h.server().verify();
    }

    @Test
    void createStyle_unknownBytes_defaultToPng() {
        Harness h = harness();
        h.server().expect(requestTo("https://external.api.recraft.ai/v1/styles"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"ref_0.png\"")))
                .andRespond(withSuccess("{\"id\":\"s1\"}", MediaType.APPLICATION_JSON));

        h.client().createStyle(List.of(new byte[]{1, 2, 3, 4}));

        h.server().verify();
    }

    @Test
    void createStyle_emptyReferences_throws() {
        Harness h = harness();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> h.client().createStyle(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapAspectRatio_square() {
        assertThat(RecraftClient.mapAspectRatio("1:1")).isEqualTo("1024x1024");
    }

    @Test
    void mapAspectRatio_landscape() {
        assertThat(RecraftClient.mapAspectRatio("16:9")).isEqualTo("1365x1024");
        assertThat(RecraftClient.mapAspectRatio("3:2")).isEqualTo("1365x1024");
    }

    @Test
    void mapAspectRatio_portraitAndNullDefault() {
        assertThat(RecraftClient.mapAspectRatio("4:5")).isEqualTo("1024x1365");
        assertThat(RecraftClient.mapAspectRatio("3:4")).isEqualTo("1024x1365");
        assertThat(RecraftClient.mapAspectRatio(null)).isEqualTo("1024x1365");
    }

    @Test
    void mapAspectRatio_nineSixteen() {
        assertThat(RecraftClient.mapAspectRatio("9:16")).isEqualTo("1024x1820");
    }
}
