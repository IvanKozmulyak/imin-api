package com.imin.iminapi.audienceplan.config;

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

class AudiencePlanAccessTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudiencePlanConfig.class, AudiencePlanAccess.class);

    @Test
    void disabled_throws404_evenWhenOrgListed() {
        runner.withPropertyValues("imin.audience-plan.enabled=false", "imin.audience-plan.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(AudiencePlanAccess.class), A));
    }

    @Test
    void enabled_orgAbsent_throws404() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(AudiencePlanAccess.class), B));
    }

    @Test
    void enabled_orgListed_passes() {
        runner.withPropertyValues("imin.audience-plan.enabled=true",
                        "imin.audience-plan.beta-org-ids=" + A + ", " + B)
                .run(ctx -> assertThatCode(() -> ctx.getBean(AudiencePlanAccess.class).requireEnabled(B))
                        .doesNotThrowAnyException());
    }

    @Test
    void enabled_blankList_throws404() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=")
                .run(ctx -> {
                    assertThat(ctx.getBean(AudiencePlanProperties.class).getBetaOrgIds()).isEmpty();
                    assertNotFound(ctx.getBean(AudiencePlanAccess.class), A);
                });
    }

    @Test
    void enabled_trailingComma_dropsBlankElement() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=" + A + ",")
                .run(ctx -> {
                    assertThat(ctx.getBean(AudiencePlanProperties.class).getBetaOrgIds()).isEqualTo(Set.of(A));
                    assertThatCode(() -> ctx.getBean(AudiencePlanAccess.class).requireEnabled(A))
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void nullOrgId_throws404() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(AudiencePlanAccess.class), null));
    }

    @Test
    void malformedUuid_failsStartup() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=not-a-uuid")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    BindException bind = findCause(ctx.getStartupFailure(), BindException.class);
                    assertThat(bind).isNotNull();
                    assertThat(bind.getMessage()).contains("imin.audience-plan.beta-org-ids");
                });
    }

    private static void assertNotFound(AudiencePlanAccess access, UUID orgId) {
        assertThatThrownBy(() -> access.requireEnabled(orgId))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Audience plan not found");
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
