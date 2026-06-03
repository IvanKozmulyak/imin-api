package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PosterTextCompositorClientTest {

    private record Harness(PosterTextCompositorClient client, MockRestServiceServer server) {}

    private Harness harness(boolean enabled) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://compositor.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PosterTextCompositorClient client = new PosterTextCompositorClient(builder.build(), enabled, 1080);
        return new Harness(client, server);
    }

    @Test
    void supports_onlyCuratedVibes_whenEnabled() {
        PosterTextCompositorClient c = harness(true).client();
        assertThat(c.supports("brutalist_techno")).isTrue();
        assertThat(c.supports("psytrance_goa")).isTrue();
        assertThat(c.supports("liquid_melodic")).isFalse(); // text-only, no template in imin-public
        assertThat(c.supports("neon_underground")).isFalse(); // legacy tag
        assertThat(c.supports(null)).isFalse();
    }

    @Test
    void supports_alwaysFalse_whenDisabled() {
        assertThat(harness(false).client().supports("brutalist_techno")).isFalse();
        assertThat(harness(false).client().isEnabled()).isFalse();
    }

    @Test
    void composite_postsExpectedBody_andReturnsPngBytes() {
        Harness h = harness(true);
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d};
        h.server().expect(requestTo("http://compositor.test/api/poster"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.vibeId").value("brutalist_techno"))
                .andExpect(jsonPath("$.width").value(1080))
                .andExpect(jsonPath("$.backgroundPngBase64").exists())
                .andExpect(jsonPath("$.event.title").value("ЗАЛІЗОБЕТОН"))
                .andExpect(jsonPath("$.event.venue").value("Київ"))
                .andExpect(jsonPath("$.event.lineup[0]").value("VTSS"))
                .andExpect(jsonPath("$.event.qrData").value("https://imin.wtf/e/x"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        byte[] out = h.client().composite(
                new byte[]{1, 2, 3},
                "brutalist_techno",
                new PosterTextCompositorClient.EventText(
                        "ЗАЛІЗОБЕТОН", "14 Jun 2026", "Київ", List.of("VTSS", "NASTIA"),
                        null, null, "https://imin.wtf/e/x"));

        assertThat(out).containsExactly(0x89, 0x50, 0x4e, 0x47, 0x0d);
        h.server().verify();
    }

    @Test
    void eventText_from_splitsDjNamesAndFormatsDate() {
        var r = new com.imin.iminapi.dto.EventCreatorRequest(
                "vibe", "tone", "techno", "Berlin",
                java.time.LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                "VTSS, NASTIA · I Hate Models", "Closed Zone", "ЗАЛІЗОБЕТОН", null,
                "вул. Кирилівська 41", "https://imin.wtf/e/x", "brutalist_techno", null);

        PosterTextCompositorClient.EventText ev = PosterTextCompositorClient.EventText.from(r);

        assertThat(ev.title()).isEqualTo("ЗАЛІЗОБЕТОН");
        assertThat(ev.venue()).isEqualTo("Closed Zone");
        assertThat(ev.lineup()).containsExactly("VTSS", "NASTIA", "I Hate Models");
        assertThat(ev.date()).isEqualTo("14 Jun 2026");
        assertThat(ev.qrData()).isEqualTo("https://imin.wtf/e/x");
        assertThat(ev.address()).isEqualTo("вул. Кирилівська 41");
    }
}
