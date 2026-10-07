package com.imin.iminapi.service.analytics;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the referrer-host → UTM suggestion helper. */
class ChannelSuggesterTest {

    private static void assertSuggest(String host, String source, String medium) {
        ChannelSuggester.Suggestion s = ChannelSuggester.suggest(host);
        assertThat(s.source()).isEqualTo(source);
        assertThat(s.medium()).isEqualTo(medium);
        assertThat(s.campaign()).isEqualTo("");
    }

    @ParameterizedTest(name = "{0} → {1}/{2}")
    @CsvSource({
            "instagram.com, instagram, social",
            "wa.me, whatsapp, social",
            "l.wa.me, whatsapp, social",
            "whatsapp.com, whatsapp, social",
            "mail.google.com, newsletter, email",
            "outlook.live.com, newsletter, email",
            "outlook.office365.com, newsletter, email",
            "t.co, twitter, social",
            "twitter.com, twitter, social",
            "x.com, twitter, social",
            "facebook.com, facebook, social",
            "l.facebook.com, facebook, social",
            // unknown host falls back to the host as source, referral medium
            "blog.example.com, blog.example.com, referral"
    })
    void known_hosts_map_to_their_channel(String host, String source, String medium) {
        assertSuggest(host, source, medium);
    }

    @ParameterizedTest(name = "[{0}] → [{1}]/{2}")
    @CsvSource(nullValues = "NULL", value = {
            // case-insensitive, strips www, trims
            "'WWW.Instagram.com', instagram, social",
            "'  X.COM  ', twitter, social",
            // null or blank falls back to an empty source
            "NULL, '', referral",
            "'   ', '', referral"
    })
    void hosts_are_normalised_before_matching(String host, String source, String medium) {
        assertSuggest(host, source, medium);
    }
}
