package com.imin.iminapi.email;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The organizer half of {@link BuyerEmailLocaleVariantsTest}, and for the same reason:
 * {@link EmailTemplateRenderer} throws on a {@code {{placeholder}}} it has no value for, and
 * falls back to English SILENTLY when a localized file is missing. So a dropped {@code .fr}
 * variant or a translator-invented placeholder would surface only as a failed send to a real
 * organizer — or never at all.
 *
 * <p>Add a template here whenever a new organizer email gets localized.
 */
class OrganizerEmailLocaleVariantsTest {

    private static final List<String> LOCALES = List.of("es", "fr", "uk");

    /** Organizer templates that ship localized variants, with every placeholder they use. */
    private static final Map<String, Map<String, String>> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("payout-blocked", Map.of(
                "eventName", "Warehouse 7",
                "amountFormatted", "90.00 EUR",
                "blockedReason", "__REASON_PLACEHOLDER__",
                "nextStep", "__NEXT_STEP_PLACEHOLDER__",
                "dashboardUrl", "https://dashboard.imin.wtf/events/abc"));

        TEMPLATES.put("payout-arrived", Map.of(
                "eventName", "Warehouse 7",
                "amountFormatted", "90.00 EUR",
                "dashboardUrl", "https://dashboard.imin.wtf/events/abc"));

        TEMPLATES.put("dispute-opened", Map.of(
                "eventName", "Warehouse 7",
                "amountFormatted", "42.00 EUR",
                "dashboardUrl", "https://dashboard.imin.wtf/events/abc"));

        Map<String, String> milestone = Map.of(
                "eventName", "Warehouse 7",
                "tierName", "Early bird",
                "percentSold", "80",
                "soldCount", "80",
                "capacity", "100",
                "dashboardUrl", "https://dashboard.imin.wtf/events/abc");
        TEMPLATES.put("sales-milestone-50", milestone);
        TEMPLATES.put("sales-milestone-80", milestone);
        TEMPLATES.put("sales-milestone-100", milestone);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> variants() {
        return TEMPLATES.keySet().stream()
                .flatMap(name -> LOCALES.stream()
                        .map(locale -> org.junit.jupiter.params.provider.Arguments.of(name, locale)));
    }

    final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("variants")
    void every_localized_organizer_template_renders_with_all_placeholders_filled(String name, String locale) {
        Map<String, String> values = TEMPLATES.get(name);

        // Throws if the variant references a placeholder we don't supply — i.e. if a
        // translator invented or misspelled one.
        EmailTemplateRenderer.Rendered localized = renderer.render(name, locale, values);

        assertThat(localized.html()).doesNotContain("{{").isNotBlank();
        assertThat(localized.text()).doesNotContain("{{").isNotBlank();

        // The renderer falls back to EN when a file is missing, so "renders fine" alone proves
        // nothing. Difference from EN is what proves the variant is actually there.
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
    void unsupported_locale_falls_back_to_the_english_organizer_template(String name) {
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
