package com.imin.iminapi.email;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateRendererTest {

    EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void renders_html_and_text_with_substitutions() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "verification-code",
                Map.of("code", "1234", "expiresInMinutes", "10"));
        assertThat(r.html()).contains("1234").contains("10");
        assertThat(r.text()).contains("1234").contains("10");
        assertThat(r.html()).doesNotContain("{{");
        assertThat(r.text()).doesNotContain("{{");
    }

    @Test
    void substitutes_repeated_placeholder_occurrences() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "welcome",
                Map.of("name", "Ada", "appBaseUrl", "https://app.imin"));
        assertThat(r.html()).doesNotContain("{{");
        assertThat(r.text()).doesNotContain("{{");
    }

    @Test
    void throws_when_template_has_unfilled_placeholder() {
        assertThatThrownBy(() -> renderer.render("verification-code", Map.of("code", "1234")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiresInMinutes");
    }

    @Test
    void throws_when_template_does_not_exist() {
        assertThatThrownBy(() -> renderer.render("nonexistent", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent");
    }
}
