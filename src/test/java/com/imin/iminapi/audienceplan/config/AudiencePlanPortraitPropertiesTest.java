package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudiencePlanPortraitPropertiesTest {

    @Test
    void shippedYaml_withEnvVarsUnsetOrBlank_bindsTheSpendCaps() {
        ShippedYaml.run(List.of(
                "IMIN_AUDIENCE_PORTRAIT_MODEL", "IMIN_AUDIENCE_PORTRAIT_PRICE_IN",
                "IMIN_AUDIENCE_PORTRAIT_PRICE_OUT", "IMIN_AUDIENCE_PORTRAIT_TIMEOUT",
                "IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_PER_ORG", "IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_GLOBAL",
                "IMIN_AUDIENCE_PORTRAIT_REFRESH_BATCH"),
                ctx -> {
                    AudiencePlanProperties p = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(p.getPortraitModel()).isEqualTo("anthropic/claude-haiku-4.5");
                    assertThat(p.getPortraitPriceInputUsdPerMtok()).isEqualByComparingTo(BigDecimal.ONE);
                    assertThat(p.getPortraitPriceOutputUsdPerMtok()).isEqualByComparingTo(new BigDecimal("5"));
                    assertThat(p.getPortraitTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(p.getPortraitDailyCapPerOrg()).isEqualTo(10);
                    assertThat(p.getPortraitDailyCapGlobal()).isEqualTo(100);
                    assertThat(p.getPortraitRefreshBatch()).isEqualTo(20);
                });
    }

    @Test
    void invalidValues_fallBackToTheDefaults() {
        AudiencePlanProperties p = new AudiencePlanProperties();
        p.setPortraitTimeout(Duration.ZERO);
        p.setPortraitDailyCapPerOrg(-1);
        p.setPortraitDailyCapGlobal(-1);
        p.setPortraitRefreshBatch(0);
        p.setPortraitModel("  ");
        assertThat(p.getPortraitTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getPortraitDailyCapPerOrg()).isEqualTo(10);
        assertThat(p.getPortraitDailyCapGlobal()).isEqualTo(100);
        assertThat(p.getPortraitRefreshBatch()).isEqualTo(20);
        assertThat(p.getPortraitModel()).isEqualTo("anthropic/claude-haiku-4.5");
    }
}
