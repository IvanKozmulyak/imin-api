package com.imin.iminapi.marketing.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingEmailPropertiesTest {

    @Test
    void fromHeader_combinesNameAndAddress() {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setFromAddress("news@news.imin.wtf");
        p.setFromName("imin");
        assertThat(p.fromHeader()).isEqualTo("imin <news@news.imin.wtf>");
    }

    @Test
    void fromHeader_addressOnlyWhenNameBlank() {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setFromAddress("news@news.imin.wtf");
        p.setFromName("");
        assertThat(p.fromHeader()).isEqualTo("news@news.imin.wtf");
    }

    @Test
    void baseUrls_defaultToProdNeverLocalhost() {
        // Prod-safe default (2026-07-22): unset env must never leak a localhost
        // URL into buyer-facing campaign links. Dev overrides in application-dev.yaml.
        // The API base joined this rule on 2026-08-16, when it started carrying
        // the unsubscribe link — a dead opt-out is worse than a dead event link.
        MarketingEmailProperties p = new MarketingEmailProperties();
        assertThat(p.getBuyerSiteBaseUrl()).isEqualTo("https://app.imin.wtf");
        assertThat(p.getApiPublicBaseUrl()).isEqualTo("https://api.imin.wtf");
        assertThat(p.unsubscribeUrl("t")).doesNotContain("localhost");
    }

    /** The per-organizer From header on the configured address, safe against header injection. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("organizerHeaders")
    void fromHeaderForOrganizer(String name, String fromAddress, String organizer, String expected) {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setFromAddress(fromAddress);
        p.setFromName(fromAddress.isEmpty() ? "" : "imin");
        assertThat(p.fromHeader(organizer)).isEqualTo(expected);
    }

    static Stream<Arguments> organizerHeaders() {
        String imin = "hello@imin.support";
        return Stream.of(
                Arguments.of("organizer via IMIN", imin, "Night Org", "\"Night Org via IMIN\" <hello@imin.support>"),
                Arguments.of("quotes and backslashes escaped", imin, "The \"Best\" \\ Club",
                        "\"The \\\"Best\\\" \\\\ Club via IMIN\" <hello@imin.support>"),
                Arguments.of("line breaks stripped", imin, "Night\r\nBcc: x@y.z",
                        "\"Night  Bcc: x@y.z via IMIN\" <hello@imin.support>"),
                Arguments.of("blank organizer falls back", imin, " ", "imin <hello@imin.support>"),
                Arguments.of("null organizer falls back", imin, null, "imin <hello@imin.support>"),
                Arguments.of("blank address falls back", "", "Night Org", ""),
                Arguments.of("unicode separators and bidi overrides stripped", imin, "Night\u2028Bcc\u2029x\u202E\u200F",
                        "\"Night Bcc x via IMIN\" <hello@imin.support>"),
                Arguments.of("only format characters falls back", imin, "\u202E\u200B", "imin <hello@imin.support>"));
    }

    /** The sender the composer previews is the name fans see inside the From header, in every branch. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("displayNames")
    void displayNameIsTheNameInsideTheFromHeader(String label, String fromAddress, String fromName,
                                                 String organizer, String expected) {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setFromAddress(fromAddress);
        p.setFromName(fromName);

        assertThat(p.displayName(organizer)).isEqualTo(expected);
        assertThat(nameIn(p.fromHeader(organizer))).isEqualTo(expected);
    }

    static Stream<Arguments> displayNames() {
        String imin = "contact@imin.support";
        return Stream.of(
                Arguments.of("org name", imin, "IMIN", "Night \"Org\"", "Night \"Org\" via IMIN"),
                Arguments.of("blank org, configured name", imin, "IMIN", " ", "IMIN"),
                Arguments.of("blank org, blank name", imin, "", null, null),
                Arguments.of("blank address", "", "IMIN", "Night Org", "IMIN"));
    }

    /** The display name of a From header: unquoted and unescaped, or null for a bare address. */
    private static String nameIn(String header) {
        int lt = header.lastIndexOf(" <");
        if (lt < 0) return null;
        String phrase = header.substring(0, lt);
        if (phrase.length() >= 2 && phrase.startsWith("\"") && phrase.endsWith("\"")) {
            phrase = phrase.substring(1, phrase.length() - 1).replaceAll("\\\\(.)", "$1");
        }
        return phrase;
    }
}
