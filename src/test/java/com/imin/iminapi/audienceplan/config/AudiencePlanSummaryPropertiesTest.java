package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AudiencePlanSummaryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudiencePlanConfig.class);

    @Test
    void defaults_onWithHaiku45AndItsPrices() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        assertThat(props.getSummaryEnabled()).isTrue();
        assertThat(props.getSummaryModel()).isEqualTo("anthropic/claude-haiku-4.5");
        assertThat(props.getSummaryPriceInputUsdPerMtok()).isEqualByComparingTo("1");
        assertThat(props.getSummaryPriceOutputUsdPerMtok()).isEqualByComparingTo("5");
        assertThat(props.getSummaryDailyCapPerOrg()).isEqualTo(50);
        assertThat(props.getSummaryTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shippedPlaceholders_withEnvVarsStubbedEmpty_bindTheDefaults() {
        runner.withPropertyValues(
                        "IMIN_AUDIENCE_PLAN_SUMMARY_ENABLED=", "IMIN_AUDIENCE_PLAN_SUMMARY_MODEL=",
                        "IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_IN=", "IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_OUT=",
                        "IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP=", "IMIN_AUDIENCE_PLAN_SUMMARY_TIMEOUT=",
                        "imin.audience-plan.summary-enabled=${IMIN_AUDIENCE_PLAN_SUMMARY_ENABLED:true}",
                        "imin.audience-plan.summary-model=${IMIN_AUDIENCE_PLAN_SUMMARY_MODEL:}",
                        "imin.audience-plan.summary-price-input-usd-per-mtok=${IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_IN:}",
                        "imin.audience-plan.summary-price-output-usd-per-mtok=${IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_OUT:}",
                        "imin.audience-plan.summary-daily-cap-per-org=${IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP:50}",
                        "imin.audience-plan.summary-timeout=${IMIN_AUDIENCE_PLAN_SUMMARY_TIMEOUT:30s}")
                .run(ctx -> {
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
    void setValues_bind() {
        runner.withPropertyValues("imin.audience-plan.summary-enabled=false",
                        "imin.audience-plan.summary-model= openai/gpt-4o-mini ",
                        "imin.audience-plan.summary-price-input-usd-per-mtok=0.15",
                        "imin.audience-plan.summary-price-output-usd-per-mtok=0.60",
                        "imin.audience-plan.summary-daily-cap-per-org=7",
                        "imin.audience-plan.summary-timeout=12s")
                .run(ctx -> {
                    AudiencePlanProperties p = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(p.getSummaryEnabled()).isFalse();
                    assertThat(p.getSummaryModel()).isEqualTo("openai/gpt-4o-mini");
                    assertThat(p.getSummaryPriceInputUsdPerMtok()).isEqualByComparingTo(new BigDecimal("0.15"));
                    assertThat(p.getSummaryPriceOutputUsdPerMtok()).isEqualByComparingTo(new BigDecimal("0.60"));
                    assertThat(p.getSummaryDailyCapPerOrg()).isEqualTo(7);
                    assertThat(p.getSummaryTimeout()).isEqualTo(Duration.ofSeconds(12));
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

    @Test
    void shippedYaml_listsEveryKey_andTheTestYamlSwitchesSummariesOff() throws Exception {
        String main = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertThat(main).contains("summary-enabled: ${IMIN_AUDIENCE_PLAN_SUMMARY_ENABLED:true}")
                .contains("summary-model: ${IMIN_AUDIENCE_PLAN_SUMMARY_MODEL:}")
                .contains("summary-price-input-usd-per-mtok: ${IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_IN:}")
                .contains("summary-price-output-usd-per-mtok: ${IMIN_AUDIENCE_PLAN_SUMMARY_PRICE_OUT:}")
                .contains("summary-daily-cap-per-org: ${IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP:50}")
                .contains("summary-timeout: ${IMIN_AUDIENCE_PLAN_SUMMARY_TIMEOUT:30s}");
        String test = Files.readString(Path.of("src/test/resources/application.yaml"));
        assertThat(test).contains("summary-enabled: false");
    }
}
