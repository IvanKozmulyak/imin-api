package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecraftClientTest {

    private record Harness(RecraftClient client, MockRestServiceServer server) {}

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://external.api.recraft.ai/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecraftClient client = new RecraftClient(builder.build(), "realistic_image");
        return new Harness(client, server);
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

}
