package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudiencePlanSummaryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudiencePlanConfig.class);

    @Test
    void shippedYaml_withEnvVarsUnsetOrBlank_bindsTheDefaults() {
        ShippedYaml.run(List.of(
                "IMIN_AUDIENCE_PLAN_SUMMARY_ENABLED", "IMIN_AUDIENCE_PLAN_SUMMARY_MODEL",
                "IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_IN", "IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_OUT",
                "IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP", "IMIN_AUDIENCE_PLAN_SUMMARY_TIMEOUT"),
                ctx -> {
                    AudiencePlanProperties p = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(p.getSummaryEnabled()).isTrue();
                    assertThat(p.getSummaryModel()).isEqualTo("anthropic/claude-haiku-4.5");
                    assertThat(p.getSummaryPriceInputUsdPerMtok()).isEqualByComparingTo("1");
                    assertThat(p.getSummaryPriceOutputUsdPerMtok()).isEqualByComparingTo("5");
                    assertThat(p.getSummaryDailyCapPerOrg()).isEqualTo(50);
                    assertThat(p.getSummaryTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void anotherModel_withoutPrices_recordsNoCost() {
        runner.withPropertyValues("imin.audience-plan.summary-model=openai/gpt-4o-mini",
                        "imin.audience-plan.summary-price-input-usd-per-mtok=",
                        "imin.audience-plan.summary-price-output-usd-per-mtok=")
                .run(ctx -> {
                    AudiencePlanProperties p = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(p.getSummaryModel()).isEqualTo("openai/gpt-4o-mini");
                    assertThat(p.getSummaryPriceInputUsdPerMtok()).isNull();
                    assertThat(p.getSummaryPriceOutputUsdPerMtok()).isNull();
                });
    }

    @Test
    void defaultModel_withAConfiguredPrice_usesThatPrice() {
        runner.withPropertyValues("imin.audience-plan.summary-price-input-usd-per-mtok=0.8")
                .run(ctx -> {
                    AudiencePlanProperties p = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(p.getSummaryModel()).isEqualTo("anthropic/claude-haiku-4.5");
                    assertThat(p.getSummaryPriceInputUsdPerMtok()).isEqualByComparingTo("0.8");
                    assertThat(p.getSummaryPriceOutputUsdPerMtok()).isEqualByComparingTo("5");
                });
    }
}
