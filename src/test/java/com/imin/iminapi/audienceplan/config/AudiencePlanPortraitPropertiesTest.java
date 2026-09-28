package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AudiencePlanPortraitPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudiencePlanConfig.class);

    @Test
    void defaults_haikuWithItsPrices_30sTimeout_andCaps() {
        AudiencePlanProperties p = new AudiencePlanProperties();
        assertThat(p.getPortraitModel()).isEqualTo("anthropic/claude-haiku-4.5");
        assertThat(p.getPortraitPriceInputUsdPerMtok()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(p.getPortraitPriceOutputUsdPerMtok()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(p.getPortraitTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getPortraitDailyCapPerOrg()).isEqualTo(10);
        assertThat(p.getPortraitDailyCapGlobal()).isEqualTo(100);
        assertThat(p.getPortraitRefreshBatch()).isEqualTo(20);
    }

    @Test
    void shippedPlaceholders_withEnvVarsStubbedEmpty_bindTheDefaults() {
        runner.withPropertyValues(
                        "IMIN_AUDIENCE_PORTRAIT_MODEL=", "IMIN_AUDIENCE_PORTRAIT_PRICE_IN=",
                        "IMIN_AUDIENCE_PORTRAIT_PRICE_OUT=", "IMIN_AUDIENCE_PORTRAIT_TIMEOUT=",
                        "IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_PER_ORG=", "IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_GLOBAL=",
                        "IMIN_AUDIENCE_PORTRAIT_REFRESH_BATCH=",
                        "imin.audience-plan.portrait-model=${IMIN_AUDIENCE_PORTRAIT_MODEL:anthropic/claude-haiku-4.5}",
                        "imin.audience-plan.portrait-price-input-usd-per-mtok=${IMIN_AUDIENCE_PORTRAIT_PRICE_IN:1}",
                        "imin.audience-plan.portrait-price-output-usd-per-mtok=${IMIN_AUDIENCE_PORTRAIT_PRICE_OUT:5}",
                        "imin.audience-plan.portrait-timeout=${IMIN_AUDIENCE_PORTRAIT_TIMEOUT:30s}",
                        "imin.audience-plan.portrait-daily-cap-per-org=${IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_PER_ORG:10}",
                        "imin.audience-plan.portrait-daily-cap-global=${IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_GLOBAL:100}",
                        "imin.audience-plan.portrait-refresh-batch=${IMIN_AUDIENCE_PORTRAIT_REFRESH_BATCH:20}")
                .run(ctx -> {
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
    void setValues_bind() {
        runner.withPropertyValues("imin.audience-plan.portrait-model= openai/gpt-4o-mini:online ",
                        "imin.audience-plan.portrait-price-input-usd-per-mtok=0.15",
                        "imin.audience-plan.portrait-price-output-usd-per-mtok=0.60",
                        "imin.audience-plan.portrait-timeout=45s",
                        "imin.audience-plan.portrait-daily-cap-per-org=4",
                        "imin.audience-plan.portrait-daily-cap-global=0",
                        "imin.audience-plan.portrait-refresh-batch=3")
                .run(ctx -> {
                    AudiencePlanProperties p = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(p.getPortraitModel()).isEqualTo("openai/gpt-4o-mini:online");
                    assertThat(p.getPortraitPriceInputUsdPerMtok()).isEqualByComparingTo(new BigDecimal("0.15"));
                    assertThat(p.getPortraitPriceOutputUsdPerMtok()).isEqualByComparingTo(new BigDecimal("0.60"));
                    assertThat(p.getPortraitTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(p.getPortraitDailyCapPerOrg()).isEqualTo(4);
                    assertThat(p.getPortraitDailyCapGlobal()).isZero();
                    assertThat(p.getPortraitRefreshBatch()).isEqualTo(3);
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

    @Test
    void shippedYaml_listsEveryKey_andTheTestYamlKeepsTheGlobalCapAtZero() throws Exception {
        String main = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertThat(main).contains("portrait-model: ${IMIN_AUDIENCE_PORTRAIT_MODEL:anthropic/claude-haiku-4.5}")
                .contains("portrait-price-input-usd-per-mtok: ${IMIN_AUDIENCE_PORTRAIT_PRICE_IN:1}")
                .contains("portrait-price-output-usd-per-mtok: ${IMIN_AUDIENCE_PORTRAIT_PRICE_OUT:5}")
                .contains("portrait-timeout: ${IMIN_AUDIENCE_PORTRAIT_TIMEOUT:30s}")
                .contains("portrait-daily-cap-per-org: ${IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_PER_ORG:10}")
                .contains("portrait-daily-cap-global: ${IMIN_AUDIENCE_PORTRAIT_DAILY_CAP_GLOBAL:100}")
                .contains("portrait-refresh-batch: ${IMIN_AUDIENCE_PORTRAIT_REFRESH_BATCH:20}");
        String test = Files.readString(Path.of("src/test/resources/application.yaml"));
        assertThat(test).contains("portrait-daily-cap-global: 0");
    }
}
