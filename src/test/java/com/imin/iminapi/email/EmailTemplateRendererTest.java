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
    void renders_localized_variant_when_present() {
        Map<String, String> vars = Map.of("name", "Ada", "appBaseUrl", "https://app.imin");
        EmailTemplateRenderer.Rendered en = renderer.render("welcome", "en", vars);
        EmailTemplateRenderer.Rendered fr = renderer.render("welcome", "fr", vars);
        // The French variant exists and must actually be used (differs from EN).
        assertThat(fr.text()).isNotEqualTo(en.text());
        assertThat(fr.html()).isNotEqualTo(en.html());
        assertThat(fr.html()).doesNotContain("{{");
        assertThat(fr.text()).doesNotContain("{{");
    }

    @Test
    void unsupported_locale_falls_back_to_english() {
        Map<String, String> vars = Map.of("name", "Ada", "appBaseUrl", "https://app.imin");
        EmailTemplateRenderer.Rendered de = renderer.render("welcome", "de", vars);
        EmailTemplateRenderer.Rendered en = renderer.render("welcome", null, vars);
        assertThat(de.html()).isEqualTo(en.html());
        assertThat(de.text()).isEqualTo(en.text());
    }

    @Test
    void missing_locale_variant_falls_back_to_english_without_throwing() {
        // refund-request-notify-imin has no localized variants and deliberately never
        // will: its recipient is one internal ops inbox. A supported locale must fall
        // back to the base EN file, never throw.
        // (This used to point at refund-request-rejected, which was translated in the
        // same change that moved this assertion — a template that gains variants stops
        // testing the fallback.)
        Map<String, String> vars = Map.of(
                "eventName", "Warehouse 7",
                "buyerEmail", "buyer@example.com",
                "phoneLine", "",
                "reason", "event_cancelled",
                "explanation", "The event was called off.",
                "orgId", "6f1c2f18-9a0e-4c1e-8f2a-1d3b5c7e9a11",
                "dashboardUrl", "https://dashboard.imin.wtf/refunds");
        EmailTemplateRenderer.Rendered fr = renderer.render("refund-request-notify-imin", "fr", vars);
        EmailTemplateRenderer.Rendered en = renderer.render("refund-request-notify-imin", null, vars);
        assertThat(fr.html()).isEqualTo(en.html());
        assertThat(fr.text()).isEqualTo(en.text());
        assertThat(fr.html()).doesNotContain("{{");
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
