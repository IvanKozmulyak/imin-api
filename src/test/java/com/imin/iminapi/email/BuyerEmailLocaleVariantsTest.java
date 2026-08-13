package com.imin.iminapi.email;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression net for the localized BUYER email templates (W1.G).
 *
 * <p>{@link EmailTemplateRenderer} throws on any {@code {{placeholder}}} it has no value
 * for, and it silently falls back to English when a localized file is missing. Together
 * those two behaviours mean a typo'd or dropped placeholder in an {@code .es}/{@code .fr}/
 * {@code .uk} file would not surface until a real buyer's email failed to send — and a
 * whole missing variant would never surface at all. So: render EVERY new variant with the
 * full values map and assert it (a) substitutes cleanly and (b) is genuinely NOT the
 * English file.
 *
 * <p>Add a template to {@link #TEMPLATES} whenever a new buyer email gets localized.
 */
class BuyerEmailLocaleVariantsTest {

    private static final List<String> LOCALES = List.of("es", "fr", "uk");

    /** Buyer templates that ship localized variants, with every placeholder they use. */
    private static final Map<String, Map<String, String>> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("ticket-issued", Map.of(
                "eventName", "Warehouse 7",
                "eventWhen", "Friday, 5 Jun 2026 · 23:00",
                "eventWhereSeparator", " · ",
                "eventWhere", "Sala Apolo, Barcelona",
                "ticketBlocks", "__TICKETS_BLOCK_PLACEHOLDER__",
                "orderUrl", "https://app.imin.wtf/order/abc",
                "recoverUrl", "https://app.imin.wtf/recover"));

        TEMPLATES.put("order-recovery", Map.of(
                "links", "__LINKS_PLACEHOLDER__"));

        TEMPLATES.put("refund-confirmed", Map.of(
                "eventName", "Warehouse 7",
                "orderShortCode", "a1b2c3d4",
                "ticketCount", "2",
                "amountFormatted", "€53.48",
                "organizerContact", "hola@sala.example"));

        TEMPLATES.put("refund-request-received-buyer", Map.of(
                "requestId", "6f1c2f18-9a0e-4c1e-8f2a-1d3b5c7e9a11"));

        TEMPLATES.put("notify-release", Map.of(
                "eventName", "Warehouse 7",
                "eventWhen", "Friday, 5 Jun 2026 · 23:00",
                "eventWhere", "Sala Apolo, Barcelona",
                "eventUrl", "https://app.imin.wtf/e/abc"));

        // ---- Buyer accounts ----
        // R1.2's three templates were localized but never registered here, so a
        // dropped or misspelled placeholder in one of their .es/.fr/.uk files
        // would only have surfaced as a failed send to a real buyer. Registered
        // now alongside R1.3's two.
        TEMPLATES.put("buyer-verification-code", Map.of(
                "code", "428106",
                "expiresInMinutes", "10"));

        TEMPLATES.put("buyer-account-exists", Map.of(
                "signInUrl", "https://app.imin.wtf/auth/login"));

        TEMPLATES.put("buyer-password-changed", Map.of(
                "signInUrl", "https://app.imin.wtf/auth/login"));

        TEMPLATES.put("buyer-address-added", Map.of(
                "address", "work@company.example",
                "profileUrl", "https://app.imin.wtf/profile/emails"));

        TEMPLATES.put("buyer-primary-changed", Map.of(
                "address", "work@company.example",
                "signInUrl", "https://app.imin.wtf/auth/login"));

        TEMPLATES.put("buyer-deletion-scheduled", Map.of(
                "deletionDate", "11 September 2026",
                "accountUrl", "https://app.imin.wtf/profile/delete"));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> variants() {
        return TEMPLATES.keySet().stream()
                .flatMap(name -> LOCALES.stream()
                        .map(locale -> org.junit.jupiter.params.provider.Arguments.of(name, locale)));
    }

    final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("variants")
    void every_localized_buyer_template_renders_with_all_placeholders_filled(String name, String locale) {
        Map<String, String> values = TEMPLATES.get(name);

        // Throws if the variant references a placeholder we don't supply — i.e. if a
        // translator invented or misspelled one.
        EmailTemplateRenderer.Rendered localized = renderer.render(name, locale, values);

        assertThat(localized.html()).doesNotContain("{{");
        assertThat(localized.text()).doesNotContain("{{");
        assertThat(localized.html()).isNotBlank();
        assertThat(localized.text()).isNotBlank();

        // The renderer falls back to EN when a file is missing, so "renders fine" alone
        // proves nothing. Difference from EN is what proves the variant is actually there.
        EmailTemplateRenderer.Rendered english = renderer.render(name, null, values);
        assertThat(localized.html())
                .as("%s.%s.html must exist and differ from the English original", name, locale)
                .isNotEqualTo(english.html());
        assertThat(localized.text())
                .as("%s.%s.txt must exist and differ from the English original", name, locale)
                .isNotEqualTo(english.text());
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("variants")
    void localized_html_declares_its_language(String name, String locale) {
        EmailTemplateRenderer.Rendered localized =
                renderer.render(name, locale, TEMPLATES.get(name));
        assertThat(localized.html()).contains("<html lang=\"" + locale + "\">");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templateNames")
    void unsupported_locale_falls_back_to_the_english_buyer_template(String name) {
        Map<String, String> values = TEMPLATES.get(name);
        EmailTemplateRenderer.Rendered de = renderer.render(name, "de", values);
        EmailTemplateRenderer.Rendered en = renderer.render(name, null, values);
        assertThat(de.html()).isEqualTo(en.html());
        assertThat(de.text()).isEqualTo(en.text());
    }

    static Stream<String> templateNames() {
        return TEMPLATES.keySet().stream();
    }
}
