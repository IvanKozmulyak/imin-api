package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AudiencePlanAccessTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudiencePlanConfig.class, AudiencePlanAccess.class);

    static Stream<Arguments> accessRows() {
        return Stream.of(
                // enabled, betaOrgIds, org, allowed
                arguments(null, null, A, true),                       // shipped defaults: open to any org
                arguments("false", "" + A, A, false),                 // kill switch beats the allow-list
                arguments("false", "", A, false),
                arguments("true", "" + A, B, false),                  // org absent from a non-blank list
                arguments("true", A + ", " + B, B, true),
                arguments("true", "", A, true),                       // blank list allows any org
                arguments("true", "", B, true),
                arguments("true", A + ",", A, true),                  // trailing comma drops the blank element
                arguments("true", A + ",", B, false),
                arguments("true", "", null, false));                  // no org is never allowed
    }

    @ParameterizedTest
    @MethodSource("accessRows")
    void access_followsTheKillSwitchAndTheAllowList(String enabled, String betaOrgs, UUID org, boolean allowed) {
        ContextConsumer<AssertableApplicationContext> check = ctx -> {
            AudiencePlanAccess access = ctx.getBean(AudiencePlanAccess.class);
            assertThat(access.isEnabled(org)).isEqualTo(allowed);
            if (allowed) {
                assertThatCode(() -> access.requireEnabled(org)).doesNotThrowAnyException();
            } else {
                assertNotFound(access, org);
            }
        };
        if (enabled == null) {
            ShippedYaml.run(List.of("IMIN_AUDIENCE_PLAN_BETA_ORGS"), check);
        } else {
            runner.withPropertyValues("imin.audience-plan.enabled=" + enabled,
                    "imin.audience-plan.beta-org-ids=" + betaOrgs).run(check);
        }
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

    static Stream<Arguments> protectingFlags() {
        return Stream.of(
                arguments("IMIN_AUDIENCE_PLAN_SENDS_ENABLED", (Function<AudiencePlanProperties, Boolean>) AudiencePlanProperties::getSendsEnabled),
                arguments("IMIN_AUDIENCE_PLAN_SOFT_OPT_IN", (Function<AudiencePlanProperties, Boolean>) AudiencePlanProperties::getSoftOptInEnabled),
                arguments("IMIN_AUDIENCE_RETENTION_ENABLED", (Function<AudiencePlanProperties, Boolean>) AudiencePlanProperties::getRetentionJobEnabled),
                arguments("IMIN_CONSENT_GATE_ALL_CAMPAIGNS", (Function<AudiencePlanProperties, Boolean>) AudiencePlanProperties::getConsentGateAllCampaigns),
                arguments("IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS", (Function<AudiencePlanProperties, Boolean>) AudiencePlanProperties::getLegalIdentityAllCampaigns),
                arguments("IMIN_CONSENT_CONFIRMATION_EMAILS_ENABLED", (Function<AudiencePlanProperties, Boolean>) AudiencePlanProperties::getConsentConfirmationEmailsEnabled));
    }

    @ParameterizedTest
    @MethodSource("protectingFlags")
    void shippedYaml_withEnvVarsUnsetOrBlank_keepsEveryProtectingFlagOff(String envVar,
                                                                         Function<AudiencePlanProperties, Boolean> flag) {
        ShippedYaml.run(List.of(envVar),
                ctx -> assertThat(flag.apply(ctx.getBean(AudiencePlanProperties.class))).isFalse());
    }

    static Stream<Arguments> legalIdentityRows() {
        return Stream.of(
                // allCampaigns flag, origin, required
                arguments(false, "audience_plan", true),
                arguments(false, "manual", false),
                arguments(false, "momentum", false),
                arguments(false, null, false),
                arguments(true, "audience_plan", true),
                arguments(true, "manual", true),
                arguments(true, "momentum", true),
                arguments(true, null, true));
    }

    @ParameterizedTest
    @MethodSource("legalIdentityRows")
    void legalIdentityRequired_dependsOnTheFlagAndTheOrigin(boolean allCampaigns, String origin, boolean required) {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setLegalIdentityAllCampaigns(allCampaigns);
        assertThat(new AudiencePlanAccess(props).legalIdentityRequired(origin)).isEqualTo(required);
    }

    @Test
    void requireLegalIdentity_manualWithoutIdentity_flagOff_passes_flagOn_throws409() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        AudiencePlanAccess access = new AudiencePlanAccess(props);
        com.imin.iminapi.model.Organization org = new com.imin.iminapi.model.Organization();
        assertThatCode(() -> access.requireLegalIdentity("manual", org)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireLegalIdentity("manual", null)).doesNotThrowAnyException();

        props.setLegalIdentityAllCampaigns(true);
        assertThatThrownBy(() -> access.requireLegalIdentity("manual", org))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(ErrorCode.ORG_LEGAL_IDENTITY_MISSING);
                });
        assertThatThrownBy(() -> access.requireLegalIdentity("momentum", null)).isInstanceOf(ApiException.class);
        org.setLegalName("Night SAS");
        org.setLegalContact("legal@night.test");
        assertThatCode(() -> access.requireLegalIdentity("manual", org)).doesNotThrowAnyException();
    }

    static Stream<Arguments> sendsRows() {
        return Stream.of(
                // sends flag, origin, blocked
                arguments(false, "audience_plan", true),
                arguments(false, "manual", false),
                arguments(false, "momentum", false),
                arguments(false, null, false),
                arguments(true, "audience_plan", false));
    }

    @ParameterizedTest
    @MethodSource("sendsRows")
    void requireSendsAllowed_blocksOnlyAudiencePlanCampaignsWhileTheSwitchIsOff(boolean sends, String origin,
                                                                               boolean blocked) {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setSendsEnabled(sends);
        AudiencePlanAccess access = new AudiencePlanAccess(props);
        if (blocked) {
            assertThatThrownBy(() -> access.requireSendsAllowed(origin))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo(ErrorCode.AUDIENCE_SENDS_DISABLED);
                        assertThat(e.getMessage()).isEqualTo("Sending audience plan campaigns is not enabled yet");
                    });
        } else {
            assertThatCode(() -> access.requireSendsAllowed(origin)).doesNotThrowAnyException();
        }
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
