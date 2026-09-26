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
    void defaults_openToAnyOrg() {
        // Plain construction: no property source or env var can override the field defaults.
        AudiencePlanProperties props = new AudiencePlanProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getBetaOrgIds()).isEmpty();
        assertThatCode(() -> new AudiencePlanAccess(props).requireEnabled(A)).doesNotThrowAnyException();
    }

    @Test
    void disabled_throws404_evenWhenOrgListed() {
        runner.withPropertyValues("imin.audience-plan.enabled=false", "imin.audience-plan.beta-org-ids=" + A)
                .run(ctx -> assertNotFound(ctx.getBean(AudiencePlanAccess.class), A));
    }

    @Test
    void disabled_blankList_throws404() {
        runner.withPropertyValues("imin.audience-plan.enabled=false", "imin.audience-plan.beta-org-ids=")
                .run(ctx -> assertNotFound(ctx.getBean(AudiencePlanAccess.class), A));
    }

    @Test
    void enabled_orgAbsentFromNonBlankList_throws404() {
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
    void enabled_blankList_allowsAnyOrg() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=")
                .run(ctx -> {
                    assertThat(ctx.getBean(AudiencePlanProperties.class).getBetaOrgIds()).isEmpty();
                    AudiencePlanAccess access = ctx.getBean(AudiencePlanAccess.class);
                    assertThatCode(() -> access.requireEnabled(A)).doesNotThrowAnyException();
                    assertThatCode(() -> access.requireEnabled(B)).doesNotThrowAnyException();
                });
    }

    @Test
    void enabled_trailingComma_dropsBlankElement() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=" + A + ",")
                .run(ctx -> {
                    assertThat(ctx.getBean(AudiencePlanProperties.class).getBetaOrgIds()).isEqualTo(Set.of(A));
                    assertThatCode(() -> ctx.getBean(AudiencePlanAccess.class).requireEnabled(A))
                            .doesNotThrowAnyException();
                    assertNotFound(ctx.getBean(AudiencePlanAccess.class), B);
                });
    }

    @Test
    void nullOrgId_throws404_evenWithBlankList() {
        runner.withPropertyValues("imin.audience-plan.enabled=true", "imin.audience-plan.beta-org-ids=")
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

    @Test
    void sendsEnabled_defaultsFalse() {
        assertThat(new AudiencePlanProperties().getSendsEnabled()).isFalse();
    }

    @Test
    void sendsEnabled_blankEnvVar_bindsFalse() {
        // The shipped placeholder, with the env var stubbed to empty so a local value cannot leak in.
        runner.withPropertyValues("IMIN_AUDIENCE_PLAN_SENDS_ENABLED=",
                        "imin.audience-plan.sends-enabled=${IMIN_AUDIENCE_PLAN_SENDS_ENABLED:false}")
                .run(ctx -> {
                    assertThat(ctx.getBean(AudiencePlanProperties.class).getSendsEnabled()).isFalse();
                    assertThat(ctx.getBean(AudiencePlanAccess.class).sendsEnabled()).isFalse();
                });
    }

    @Test
    void sendsEnabled_shippedYamlDefaultsToFalse() throws Exception {
        String yaml = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yaml"));
        assertThat(yaml).contains("sends-enabled: ${IMIN_AUDIENCE_PLAN_SENDS_ENABLED:false}");
    }

    @Test
    void sendsEnabled_nullSetter_staysFalse() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setSendsEnabled(null);
        assertThat(props.getSendsEnabled()).isFalse();
    }

    @Test
    void sendsOff_audiencePlanOrigin_throws409() {
        AudiencePlanAccess access = new AudiencePlanAccess(new AudiencePlanProperties());
        assertThatThrownBy(() -> access.requireSendsAllowed("audience_plan"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(ErrorCode.AUDIENCE_SENDS_DISABLED);
                    assertThat(e.getMessage()).isEqualTo("Sending audience plan campaigns is not enabled yet");
                });
    }

    @Test
    void sendsOff_otherOrigins_pass() {
        AudiencePlanAccess access = new AudiencePlanAccess(new AudiencePlanProperties());
        assertThatCode(() -> access.requireSendsAllowed("manual")).doesNotThrowAnyException();
        assertThatCode(() -> access.requireSendsAllowed("momentum")).doesNotThrowAnyException();
        assertThatCode(() -> access.requireSendsAllowed(null)).doesNotThrowAnyException();
    }

    @Test
    void sendsOn_audiencePlanOrigin_passes() {
        runner.withPropertyValues("imin.audience-plan.sends-enabled=true")
                .run(ctx -> {
                    AudiencePlanAccess access = ctx.getBean(AudiencePlanAccess.class);
                    assertThat(access.sendsEnabled()).isTrue();
                    assertThatCode(() -> access.requireSendsAllowed("audience_plan")).doesNotThrowAnyException();
                });
    }

    // ---- isEnabled: the non-throwing form background work uses ----

    @Test
    void isEnabled_defaults_trueForAnyOrg() {
        assertThat(new AudiencePlanAccess(new AudiencePlanProperties()).isEnabled(A)).isTrue();
    }

    @Test
    void isEnabled_killSwitchOff_false() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setEnabled(false);
        assertThat(new AudiencePlanAccess(props).isEnabled(A)).isFalse();
    }

    @Test
    void isEnabled_nullOrg_false() {
        assertThat(new AudiencePlanAccess(new AudiencePlanProperties()).isEnabled(null)).isFalse();
    }

    @Test
    void isEnabled_nonBlankList_onlyListedOrgs() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setBetaOrgIds(Set.of(A));
        AudiencePlanAccess access = new AudiencePlanAccess(props);
        assertThat(access.isEnabled(A)).isTrue();
        assertThat(access.isEnabled(B)).isFalse();
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
