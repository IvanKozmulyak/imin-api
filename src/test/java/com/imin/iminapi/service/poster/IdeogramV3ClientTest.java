package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdeogramV3ClientTest {

    private record Harness(IdeogramV3Client client, MockRestServiceServer server) {}

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.ideogram.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IdeogramV3Client client = new IdeogramV3Client(builder.build(), "QUALITY", "TURBO", true);
        return new Harness(client, server);
    }

    @Test
    void generate_withRefs_sendsMultipartFieldsAndDownloadsImage() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(Matchers.containsString("name=\"prompt\"")))
                .andExpect(content().string(Matchers.containsString("a \"VOID\" poster")))
                .andExpect(content().string(Matchers.containsString("name=\"aspect_ratio\"")))
                .andExpect(content().string(Matchers.containsString("4x5")))
                .andExpect(content().string(Matchers.containsString("name=\"rendering_speed\"")))
                .andExpect(content().string(Matchers.containsString("QUALITY")))
                .andExpect(content().string(Matchers.containsString("name=\"magic_prompt\"")))
                .andExpect(content().string(Matchers.containsString("OFF")))
                .andExpect(content().string(Matchers.containsString("name=\"enable_copyright_detection\"")))
                .andExpect(content().string(Matchers.containsString("name=\"seed\"")))
                .andExpect(content().string(Matchers.containsString("name=\"style_reference_images\"")))
                .andExpect(content().string(Matchers.containsString("filename=\"ref0.png\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("style_preset"))))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/x.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/x.png"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[]{1, 2, 3, 4}, MediaType.IMAGE_PNG));

        IdeogramV3Client.IdeogramResult r = h.client().generate(
                "a \"VOID\" poster", 42L,
                List.of(new StyleReferencePart(new byte[]{9, 9}, "ref0.png", "image/png")),
                "HIGH_CONTRAST"); // preset present but refs win

        assertThat(r.imageBytes()).containsExactly(1, 2, 3, 4);
        assertThat(r.seed()).isEqualTo(42L);
        h.server().verify();
    }

    @Test
    void generate_noRefs_fallsBackToStylePreset() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(content().string(Matchers.containsString("name=\"style_preset\"")))
                .andExpect(content().string(Matchers.containsString("HIGH_CONTRAST")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("style_reference_images"))))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/y.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/y.png"))
                .andRespond(withSuccess(new byte[]{5, 6}, MediaType.IMAGE_PNG));

        IdeogramV3Client.IdeogramResult r = h.client().generate("p", 7L, List.of(), "HIGH_CONTRAST");

        assertThat(r.imageBytes()).containsExactly(5, 6);
        h.server().verify();
    }

    @Test
    void remix_sendsImagePartAndImageWeightAndPrompt() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/remix"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(Matchers.containsString("name=\"image\"")))
                .andExpect(content().string(Matchers.containsString("name=\"image_weight\"")))
                .andExpect(content().string(Matchers.containsString("70")))
                .andExpect(content().string(Matchers.containsString("name=\"rendering_speed\"")))
                .andExpect(content().string(Matchers.containsString("TURBO")))
                .andExpect(content().string(Matchers.containsString("CORRECTION")))
                .andExpect(content().string(Matchers.containsString("name=\"magic_prompt\"")))
                .andExpect(content().string(Matchers.containsString("OFF")))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/z.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/z.png"))
                .andRespond(withSuccess(new byte[]{7, 8}, MediaType.IMAGE_PNG));

        IdeogramV3Client.IdeogramResult r = h.client().remix(
                new byte[]{1, 1, 1, 1}, "p\n\nCORRECTION — fix text", 70, 99L, List.of(), "HIGH_CONTRAST");

        assertThat(r.imageBytes()).containsExactly(7, 8);
        assertThat(r.seed()).isEqualTo(99L);
        h.server().verify();
    }
}
