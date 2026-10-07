package com.imin.iminapi.predictor.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;

import java.util.Set;
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

    // ---- web research ----

    @Test
    void researchFollowsDateCheckAccess() {
        DateCheckProperties beta = new DateCheckProperties();
        beta.setEnabled(true);
        beta.setBetaOrgIds(Set.of(A));
        DateCheckAccess betaAccess = new DateCheckAccess(beta);
        assertThat(betaAccess.isResearchAvailable(A)).isTrue();
        assertThat(betaAccess.isResearchAvailable(B)).isFalse();
        assertThat(betaAccess.isResearchAvailable(null)).isFalse();

        beta.setAllOrgs(true);
        assertThat(betaAccess.isResearchAvailable(B)).isTrue();
        assertThat(betaAccess.isResearchAvailable(null)).isFalse();

        beta.setEnabled(false);
        assertThat(betaAccess.isResearchAvailable(A)).isFalse();
        assertThat(betaAccess.isResearchAvailable(B)).isFalse();
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
