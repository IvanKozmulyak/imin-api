package com.imin.iminapi.marketing.email;

import org.junit.jupiter.api.Test;
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
}
