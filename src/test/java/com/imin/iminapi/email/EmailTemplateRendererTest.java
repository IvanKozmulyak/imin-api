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

    @Test
    void html_template_escapes_special_characters_in_values() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "verification-code",
                Map.of("code", "<script>alert(1)</script>", "expiresInMinutes", "10"));
        assertThat(r.html()).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(r.html()).doesNotContain("<script>");
    }

    @Test
    void html_template_escapes_attribute_breaking_characters() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "welcome",
                Map.of("name", "x", "appBaseUrl", "https://x.example/\" onmouseover=\"alert(1)"));
        // Quote must be escaped so it cannot break out of href="..." attribute
        assertThat(r.html()).contains("&quot;");
        assertThat(r.html()).doesNotContain("\" onmouseover=\"");
    }

    @Test
    void text_template_does_not_escape_html_special_characters() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "verification-code",
                Map.of("code", "<x&y>", "expiresInMinutes", "10"));
        // Plain text emails should render the value verbatim
        assertThat(r.text()).contains("<x&y>");
        assertThat(r.text()).doesNotContain("&amp;");
        assertThat(r.text()).doesNotContain("&lt;");
    }
}
