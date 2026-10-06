package com.imin.iminapi.predictor.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchBlocklistTest {

    @Test
    void listedSitesAndTheirSubdomainsAreBlocked() {
        for (String url : new String[] {
                "https://www.facebook.com/events/1", "https://m.facebook.com/x", "https://fb.com/e", "https://fb.me/e",
                "https://www.instagram.com/p/x", "https://www.threads.net/@x", "https://www.whatsapp.com/c",
                "https://www.messenger.com/t", "https://about.meta.com/", "https://open.spotify.com/concert/1",
                "https://ra.co/events/1", "https://de.ra.co/events/2", "https://shotgun.live/fr/events/x",
                "https://www.cestlagreve.fr/greve", "HTTPS://WWW.FACEBOOK.COM./events"}) {
            assertThat(SearchBlocklist.blocked(url)).as(url).isTrue();
        }
    }

    @Test
    void lookalikeHostsAreNotBlocked() {
        for (String url : new String[] {
                "https://ra.com/x", "https://notra.co/x", "https://myspotify.com/x", "https://fb.com.example.org/x",
                "https://www.infoconcert.com/x", "https://www.zenithdelille.com/agenda"}) {
            assertThat(SearchBlocklist.blocked(url)).as(url).isFalse();
        }
    }

    @Test
    void unreadableOrNonWebUrlsAreBlocked() {
        for (String url : new String[] {null, "", "not a url", "ftp://example.org/x", "javascript:alert(1)",
                "https:///nohost"}) {
            assertThat(SearchBlocklist.blocked(url)).as(String.valueOf(url)).isTrue();
        }
    }
}
