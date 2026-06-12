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

    private static final StyleReferencePart CHAR_REF =
            new StyleReferencePart(new byte[]{1, 2, 3}, "dj-photo.jpg", "image/jpeg");
    private static final List<String> PALETTE = List.of("#ec4899", "#f6c04a");

    private record Harness(IdeogramV3Client client, MockRestServiceServer server) {}

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.ideogram.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IdeogramV3Client client = new IdeogramV3Client(builder.build(), "QUALITY", "TURBO", true,
                false, false, false);
        return new Harness(client, server);
    }

    private IdeogramV3Client clientWithFlags(boolean palette, boolean seed, boolean style) {
        return new IdeogramV3Client(null, "QUALITY", "TURBO", true, palette, seed, style);
    }

    @Test
    void generateWithoutCharacterRefIsUnchangedBaseline() {
        var parts = clientWithFlags(false, false, false)
                .buildGenerateParts("prompt", 42L, List.of(), "MONOCHROME", PALETTE, null);
        assertThat(parts.containsKey("character_reference_images")).isFalse();
        assertThat(parts.getFirst("seed")).isEqualTo("42");
        assertThat(parts.containsKey("color_palette")).isTrue();
        assertThat(parts.getFirst("style_preset")).isEqualTo("MONOCHROME");
    }

    @Test
    void characterRefOmitsGatedParamsWhenFlagsOff() {
        var parts = clientWithFlags(false, false, false)
                .buildGenerateParts("prompt", 42L, List.of(), "MONOCHROME", PALETTE, CHAR_REF);
        assertThat(parts.containsKey("character_reference_images")).isTrue();
        assertThat(parts.containsKey("seed")).isFalse();
        assertThat(parts.containsKey("color_palette")).isFalse();
        assertThat(parts.containsKey("style_preset")).isFalse();
        assertThat(parts.containsKey("style_reference_images")).isFalse();
    }

    @Test
    void characterRefKeepsGatedParamsWhenFlagsOn() {
        var parts = clientWithFlags(true, true, true)
                .buildGenerateParts("prompt", 42L, List.of(), "MONOCHROME", PALETTE, CHAR_REF);
        assertThat(parts.containsKey("character_reference_images")).isTrue();
        assertThat(parts.getFirst("seed")).isEqualTo("42");
        assertThat(parts.containsKey("color_palette")).isTrue();
        assertThat(parts.getFirst("style_preset")).isEqualTo("MONOCHROME");
    }

    @Test
    void remixCarriesCharacterRefToo() {
        var parts = clientWithFlags(false, false, false)
                .buildRemixParts(new byte[]{9}, "fix", 70, 42L, List.of(), null, PALETTE, CHAR_REF);
        assertThat(parts.containsKey("character_reference_images")).isTrue();
        assertThat(parts.containsKey("image")).isTrue();
        assertThat(parts.containsKey("seed")).isFalse();
        assertThat(parts.containsKey("color_palette")).isFalse();
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
                "HIGH_CONTRAST", List.of(), null); // preset present but refs win

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

        IdeogramV3Client.IdeogramResult r = h.client().generate("p", 7L, List.of(), "HIGH_CONTRAST", List.of(), null);

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
                new byte[]{1, 1, 1, 1}, "p\n\nCORRECTION — fix text", 70, 99L, List.of(), "HIGH_CONTRAST", List.of(), null);

        assertThat(r.imageBytes()).containsExactly(7, 8);
        assertThat(r.seed()).isEqualTo(99L);
        h.server().verify();
    }

    @Test
    void generate_withBrandColors_sendsColorPaletteAlongsideStyleRefs() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(content().string(Matchers.containsString("name=\"color_palette\"")))
                .andExpect(content().string(Matchers.containsString(
                        "{\"members\":[{\"color_hex\":\"#ec4899\",\"color_weight\":1.0},"
                        + "{\"color_hex\":\"#f6c04a\",\"color_weight\":0.5},"
                        + "{\"color_hex\":\"#a78bfa\",\"color_weight\":0.25}]}")))
                .andExpect(content().string(Matchers.containsString("name=\"style_reference_images\"")))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/c.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/c.png"))
                .andRespond(withSuccess(new byte[]{1}, MediaType.IMAGE_PNG));

        h.client().generate("p", 1L,
                List.of(new StyleReferencePart(new byte[]{9}, "ref0.png", "image/png")), null,
                List.of("#ec4899", "#f6c04a", "#a78bfa"), null);

        h.server().verify();
    }

    @Test
    void generate_withoutBrandColors_omitsColorPalette() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("color_palette"))))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/d.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/d.png"))
                .andRespond(withSuccess(new byte[]{1}, MediaType.IMAGE_PNG));

        h.client().generate("p", 1L, List.of(), "HIGH_CONTRAST", null, null);

        h.server().verify();
    }

    @Test
    void generate_filtersMalformedHexes_omitsPaletteWhenNoneValid() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("color_palette"))))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/e.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/e.png"))
                .andRespond(withSuccess(new byte[]{1}, MediaType.IMAGE_PNG));

        h.client().generate("p", 1L, List.of(), null, List.of("magenta", "#12345", ""), null);

        h.server().verify();
    }

    @Test
    void remix_withBrandColors_sendsColorPalette() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/remix"))
                .andExpect(content().string(Matchers.containsString("name=\"color_palette\"")))
                .andExpect(content().string(Matchers.containsString(
                        "{\"members\":[{\"color_hex\":\"#ec4899\",\"color_weight\":1.0}]}")))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/f.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/f.png"))
                .andRespond(withSuccess(new byte[]{1}, MediaType.IMAGE_PNG));

        h.client().remix(new byte[]{1}, "p", 70, 1L, List.of(), null, List.of("#ec4899"), null);

        h.server().verify();
    }
}
