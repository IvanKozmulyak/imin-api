package com.imin.iminapi.predictor.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateCheckAccessTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PredictorConfig.class, DateCheckAccess.class);

    @Test
    void offReturns404() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=false",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(DateCheckAccess.class), A));
    }

    @Test
    void onButOrgNotInBetaReturns404() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(DateCheckAccess.class), B));
    }

    @Test
    void onAndInBetaPasses() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=" + A + ", " + B)
                .run(ctx -> assertThatCode(() -> ctx.getBean(DateCheckAccess.class).requireEnabled(B))
                        .doesNotThrowAnyException());
    }

    @Test
    void emptyBetaListMeansNobody() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=")
                .run(ctx -> {
                    assertThat(ctx.getBean(DateCheckProperties.class).getBetaOrgIds()).isEmpty();
                    DateCheckAccess access = ctx.getBean(DateCheckAccess.class);
                    assertNotFound(access, A);
                    assertNotFound(access, B);
                });
    }

    @Test
    void nullOrgReturns404() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(DateCheckAccess.class), null));
    }

    @Test
    void allOrgsLetsNonBetaOrgThrough() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.all-orgs=true",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> assertThatCode(() -> ctx.getBean(DateCheckAccess.class).requireEnabled(B))
                        .doesNotThrowAnyException());
    }

    @Test
    void allOrgsStill404WhenDisabled() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=false",
                        "imin.predictor.date-check.all-orgs=true",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> {
                    DateCheckAccess access = ctx.getBean(DateCheckAccess.class);
                    assertNotFound(access, A);
                    assertNotFound(access, B);
                });
    }

    @Test
    void allOrgsStill404ForNullOrg() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.all-orgs=true")
                .run(ctx -> assertNotFound(ctx.getBean(DateCheckAccess.class), null));
    }

    @Test
    void allOrgsFalseKeepsBetaListSemantics() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.all-orgs=false",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> {
                    DateCheckAccess access = ctx.getBean(DateCheckAccess.class);
                    assertThatCode(() -> access.requireEnabled(A)).doesNotThrowAnyException();
                    assertNotFound(access, B);
                });
    }

    @Test
    void allOrgsDefaultsFalse() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=" + A)
                .run(ctx -> {
                    assertThat(ctx.getBean(DateCheckProperties.class).getAllOrgs()).isFalse();
                    assertNotFound(ctx.getBean(DateCheckAccess.class), B);
                });
    }

    @Test
    void trailingCommaDropsBlankElement() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=" + A + ",")
                .run(ctx -> {
                    assertThat(ctx.getBean(DateCheckProperties.class).getBetaOrgIds()).isEqualTo(Set.of(A));
                    assertThatCode(() -> ctx.getBean(DateCheckAccess.class).requireEnabled(A))
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void isEnabledFalseWhenClosed() {
        DateCheckProperties props = new DateCheckProperties();
        props.setBetaOrgIds(Set.of(A));
        DateCheckAccess access = new DateCheckAccess(props);

        assertThat(access.isEnabled(A)).isFalse();
    }

    @Test
    void isEnabledTrueForBetaOrg() {
        DateCheckProperties props = new DateCheckProperties();
        props.setEnabled(true);
        props.setBetaOrgIds(Set.of(A));
        DateCheckAccess access = new DateCheckAccess(props);

        assertThat(access.isEnabled(A)).isTrue();
        assertThat(access.isEnabled(B)).isFalse();
        assertThat(access.isEnabled(null)).isFalse();
    }

    @Test
    void malformedUuidFailsStartup() {
        runner.withPropertyValues("imin.predictor.date-check.enabled=true",
                        "imin.predictor.date-check.beta-org-ids=not-a-uuid")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    BindException bind = findCause(ctx.getStartupFailure(), BindException.class);
                    assertThat(bind).isNotNull();
                    assertThat(bind.getMessage()).contains("imin.predictor.date-check.beta-org-ids");
                });
    }

    @Test
    void defaultsAreOff() {
        // Plain construction: no property source or env var can override the field defaults.
        DateCheckProperties props = new DateCheckProperties();
        assertThat(props.getEnabled()).isFalse();
        assertThat(props.getResearchEnabled()).isFalse();
        assertThat(props.getRadarEnabled()).isFalse();
        assertThat(props.getAllOrgs()).isFalse();
        assertThat(props.getBetaOrgIds()).isEmpty();
        assertThat(props.getMaxDates()).isEqualTo(5);
        assertThat(props.getMaxHorizonMonths()).isEqualTo(18);
        assertNotFound(new DateCheckAccess(props), A);
    }

    @Test
    void blankEnvVarsBindSafeDefaults() {
        // The shipped placeholders, with each env var stubbed to empty so a local value cannot leak in.
        runner.withPropertyValues(
                        "PREDICTOR_DATE_CHECK_ENABLED=",
                        "PREDICTOR_DATE_CHECK_ALL_ORGS=",
                        "PREDICTOR_DATE_CHECK_RESEARCH_ENABLED=",
                        "PREDICTOR_DATE_CHECK_RADAR_ENABLED=",
                        "PREDICTOR_DATE_CHECK_MAX_DATES=",
                        "PREDICTOR_DATE_CHECK_MAX_HORIZON_MONTHS=",
                        "imin.predictor.date-check.enabled=${PREDICTOR_DATE_CHECK_ENABLED:false}",
                        "imin.predictor.date-check.all-orgs=${PREDICTOR_DATE_CHECK_ALL_ORGS:false}",
                        "imin.predictor.date-check.research-enabled=${PREDICTOR_DATE_CHECK_RESEARCH_ENABLED:false}",
                        "imin.predictor.date-check.radar-enabled=${PREDICTOR_DATE_CHECK_RADAR_ENABLED:false}",
                        "imin.predictor.date-check.max-dates=${PREDICTOR_DATE_CHECK_MAX_DATES:5}",
                        "imin.predictor.date-check.max-horizon-months=${PREDICTOR_DATE_CHECK_MAX_HORIZON_MONTHS:18}")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
                    assertThat(props.getEnabled()).isFalse();
                    assertThat(props.getAllOrgs()).isFalse();
                    assertThat(props.getResearchEnabled()).isFalse();
                    assertThat(props.getRadarEnabled()).isFalse();
                    assertThat(props.getMaxDates()).isEqualTo(5);
                    assertThat(props.getMaxHorizonMonths()).isEqualTo(18);
                });
    }

    @Test
    void nonPositiveLimitsFallBackToDefaults() {
        runner.withPropertyValues("imin.predictor.date-check.max-dates=0",
                        "imin.predictor.date-check.max-horizon-months=-1")
                .run(ctx -> {
                    DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
                    assertThat(props.getMaxDates()).isEqualTo(5);
                    assertThat(props.getMaxHorizonMonths()).isEqualTo(18);
                });
        runner.withPropertyValues("imin.predictor.date-check.max-dates=3",
                        "imin.predictor.date-check.max-horizon-months=12")
                .run(ctx -> {
                    DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
                    assertThat(props.getMaxDates()).isEqualTo(3);
                    assertThat(props.getMaxHorizonMonths()).isEqualTo(12);
                });
    }

    @Test
    void shippedYamlIsDarkAndEnumeratesEveryKey() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains(
                "      enabled: ${PREDICTOR_DATE_CHECK_ENABLED:false}\n",
                "      all-orgs: ${PREDICTOR_DATE_CHECK_ALL_ORGS:false}\n",
                "      beta-org-ids: ${PREDICTOR_DATE_CHECK_BETA_ORGS:}\n",
                "      research-enabled: ${PREDICTOR_DATE_CHECK_RESEARCH_ENABLED:false}\n",
                "      radar-enabled: ${PREDICTOR_DATE_CHECK_RADAR_ENABLED:false}\n",
                "      max-dates: ${PREDICTOR_DATE_CHECK_MAX_DATES:5}\n",
                "      max-horizon-months: ${PREDICTOR_DATE_CHECK_MAX_HORIZON_MONTHS:18}\n");

        String block = dateCheckBlock(yaml);
        Set<String> missing = new TreeSet<>();
        for (Field f : DateCheckProperties.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
            String key = f.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
            if (!block.contains("\n      " + key + ":")) missing.add(key);
        }
        assertThat(missing).as("DateCheckProperties fields missing from the date-check yaml block").isEmpty();
    }

    /** From the nested {@code date-check:} line to the next key indented 4 spaces or less. */
    private static String dateCheckBlock(String yaml) {
        int start = yaml.indexOf("\n    date-check:\n");
        assertThat(start).as("date-check block under imin.predictor").isNotNegative();
        int from = start + "\n    date-check:\n".length();
        java.util.regex.Matcher end = java.util.regex.Pattern.compile("\n {0,4}[a-z]").matcher(yaml);
        int stop = end.find(from) ? end.start() : yaml.length();
        return yaml.substring(start, stop);
    }

    private static void assertNotFound(DateCheckAccess access, UUID orgId) {
        assertThatThrownBy(() -> access.requireEnabled(orgId))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Date check not found");
                });
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
