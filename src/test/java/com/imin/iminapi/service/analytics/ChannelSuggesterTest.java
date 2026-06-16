package com.imin.iminapi.service.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the referrer-host → UTM suggestion helper. */
class ChannelSuggesterTest {

    private static void assertSuggest(String host, String source, String medium, String campaign) {
        ChannelSuggester.Suggestion s = ChannelSuggester.suggest(host);
        assertThat(s.source()).isEqualTo(source);
        assertThat(s.medium()).isEqualTo(medium);
        assertThat(s.campaign()).isEqualTo(campaign);
    }

    @Test
    void instagram_is_social() {
        assertSuggest("instagram.com", "instagram", "social", "");
    }

    @Test
    void whatsapp_variants_all_map_to_whatsapp_social() {
        assertSuggest("wa.me", "whatsapp", "social", "");
        assertSuggest("l.wa.me", "whatsapp", "social", "");
        assertSuggest("whatsapp.com", "whatsapp", "social", "");
    }

    @Test
    void webmail_hosts_map_to_newsletter_email() {
        assertSuggest("mail.google.com", "newsletter", "email", "");
        assertSuggest("outlook.live.com", "newsletter", "email", "");
        assertSuggest("outlook.office365.com", "newsletter", "email", "");
    }

    @Test
    void twitter_variants_map_to_twitter_social() {
        assertSuggest("t.co", "twitter", "social", "");
        assertSuggest("twitter.com", "twitter", "social", "");
        assertSuggest("x.com", "twitter", "social", "");
    }

    @Test
    void facebook_variants_map_to_facebook_social() {
        assertSuggest("facebook.com", "facebook", "social", "");
        assertSuggest("l.facebook.com", "facebook", "social", "");
    }

    @Test
    void unknown_host_falls_back_to_host_as_source_and_referral() {
        assertSuggest("blog.example.com", "blog.example.com", "referral", "");
    }

    @Test
    void matching_is_case_insensitive_and_strips_www_and_trims() {
        assertSuggest("WWW.Instagram.com", "instagram", "social", "");
        assertSuggest("  X.COM  ", "twitter", "social", "");
    }

    @Test
    void null_or_blank_host_falls_back_to_empty_source_referral() {
        assertSuggest(null, "", "referral", "");
        assertSuggest("   ", "", "referral", "");
    }
}
