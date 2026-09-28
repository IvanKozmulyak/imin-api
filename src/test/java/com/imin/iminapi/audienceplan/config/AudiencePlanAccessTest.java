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
    void softOptIn_defaultsFalse() {
        assertThat(new AudiencePlanProperties().getSoftOptInEnabled()).isFalse();
    }

    @Test
    void softOptIn_blankEnvVar_bindsFalse() {
        runner.withPropertyValues("IMIN_AUDIENCE_PLAN_SOFT_OPT_IN=",
                        "imin.audience-plan.soft-opt-in-enabled=${IMIN_AUDIENCE_PLAN_SOFT_OPT_IN:false}")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanProperties.class).getSoftOptInEnabled()).isFalse());
    }

    @Test
    void softOptIn_true_binds() {
        runner.withPropertyValues("imin.audience-plan.soft-opt-in-enabled=true")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanProperties.class).getSoftOptInEnabled()).isTrue());
    }

    @Test
    void softOptIn_shippedYamlDefaultsToFalse() throws Exception {
        String main = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yaml"));
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application.yaml"));
        assertThat(main).contains("soft-opt-in-enabled: ${IMIN_AUDIENCE_PLAN_SOFT_OPT_IN:false}");
        assertThat(test).contains("soft-opt-in-enabled: false");
    }

    @Test
    void softOptIn_nullSetter_staysFalse() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setSoftOptInEnabled(null);
        assertThat(props.getSoftOptInEnabled()).isFalse();
    }

    @Test
    void retentionJob_defaultsFalse() {
        assertThat(new AudiencePlanProperties().getRetentionJobEnabled()).isFalse();
    }

    @Test
    void retentionJob_blankEnvVar_bindsFalse() {
        runner.withPropertyValues("IMIN_AUDIENCE_RETENTION_ENABLED=",
                        "imin.audience-plan.retention-job-enabled=${IMIN_AUDIENCE_RETENTION_ENABLED:false}")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanProperties.class).getRetentionJobEnabled()).isFalse());
    }

    @Test
    void retentionJob_true_binds() {
        runner.withPropertyValues("imin.audience-plan.retention-job-enabled=true")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanProperties.class).getRetentionJobEnabled()).isTrue());
    }

    @Test
    void retentionJob_shippedYamlDefaultsToFalse() throws Exception {
        String main = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yaml"));
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application.yaml"));
        assertThat(main).contains("retention-job-enabled: ${IMIN_AUDIENCE_RETENTION_ENABLED:false}");
        assertThat(test).contains("retention-job-enabled: false");
    }

    @Test
    void retentionJob_nullSetter_staysFalse() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setRetentionJobEnabled(null);
        assertThat(props.getRetentionJobEnabled()).isFalse();
    }

    @Test
    void consentGateAllCampaigns_defaultsFalse() {
        assertThat(new AudiencePlanProperties().getConsentGateAllCampaigns()).isFalse();
    }

    @Test
    void consentGateAllCampaigns_blankEnvVar_bindsFalse() {
        runner.withPropertyValues("IMIN_CONSENT_GATE_ALL_CAMPAIGNS=",
                        "imin.audience-plan.consent-gate-all-campaigns=${IMIN_CONSENT_GATE_ALL_CAMPAIGNS:false}")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanProperties.class).getConsentGateAllCampaigns()).isFalse());
    }

    @Test
    void consentGateAllCampaigns_true_binds() {
        runner.withPropertyValues("imin.audience-plan.consent-gate-all-campaigns=true")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanProperties.class).getConsentGateAllCampaigns()).isTrue());
    }

    @Test
    void consentGateAllCampaigns_shippedYamlDefaultsToFalse() throws Exception {
        String main = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yaml"));
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application.yaml"));
        assertThat(main).contains("consent-gate-all-campaigns: ${IMIN_CONSENT_GATE_ALL_CAMPAIGNS:false}");
        assertThat(test).contains("consent-gate-all-campaigns: false");
    }

    @Test
    void consentGateAllCampaigns_nullSetter_staysFalse() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setConsentGateAllCampaigns(null);
        assertThat(props.getConsentGateAllCampaigns()).isFalse();
    }

    @Test
    void legalIdentityAllCampaigns_defaultsFalse() {
        assertThat(new AudiencePlanProperties().getLegalIdentityAllCampaigns()).isFalse();
        assertThat(new AudiencePlanAccess(new AudiencePlanProperties()).legalIdentityAllCampaigns()).isFalse();
    }

    @Test
    void legalIdentityAllCampaigns_blankEnvVar_bindsFalse() {
        runner.withPropertyValues("IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS=",
                        "imin.audience-plan.legal-identity-all-campaigns=${IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS:false}")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanAccess.class).legalIdentityAllCampaigns()).isFalse());
    }

    @Test
    void legalIdentityAllCampaigns_true_binds() {
        runner.withPropertyValues("imin.audience-plan.legal-identity-all-campaigns=true")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanAccess.class).legalIdentityAllCampaigns()).isTrue());
    }

    @Test
    void legalIdentityAllCampaigns_shippedYamlDefaultsToFalse() throws Exception {
        String main = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yaml"));
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application.yaml"));
        assertThat(main).contains("legal-identity-all-campaigns: ${IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS:false}");
        assertThat(test).contains("legal-identity-all-campaigns: false");
    }

    @Test
    void legalIdentityAllCampaigns_nullSetter_staysFalse() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setLegalIdentityAllCampaigns(null);
        assertThat(props.getLegalIdentityAllCampaigns()).isFalse();
    }

    @Test
    void consentConfirmationEmails_defaultsFalse() {
        assertThat(new AudiencePlanProperties().getConsentConfirmationEmailsEnabled()).isFalse();
        assertThat(new AudiencePlanAccess(new AudiencePlanProperties()).consentConfirmationEmailsEnabled()).isFalse();
    }

    @Test
    void consentConfirmationEmails_blankEnvVar_bindsFalse() {
        runner.withPropertyValues("IMIN_CONSENT_CONFIRMATION_EMAILS_ENABLED=",
                        "imin.audience-plan.consent-confirmation-emails-enabled=${IMIN_CONSENT_CONFIRMATION_EMAILS_ENABLED:false}")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanAccess.class).consentConfirmationEmailsEnabled()).isFalse());
    }

    @Test
    void consentConfirmationEmails_true_binds() {
        runner.withPropertyValues("imin.audience-plan.consent-confirmation-emails-enabled=true")
                .run(ctx -> assertThat(ctx.getBean(AudiencePlanAccess.class).consentConfirmationEmailsEnabled()).isTrue());
    }

    @Test
    void consentConfirmationEmails_shippedYamlDefaultsToFalse() throws Exception {
        String main = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yaml"));
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application.yaml"));
        assertThat(main).contains("consent-confirmation-emails-enabled: ${IMIN_CONSENT_CONFIRMATION_EMAILS_ENABLED:false}");
        assertThat(test).contains("consent-confirmation-emails-enabled: false");
    }

    @Test
    void consentConfirmationEmails_nullSetter_staysFalse() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setConsentConfirmationEmailsEnabled(null);
        assertThat(props.getConsentConfirmationEmailsEnabled()).isFalse();
    }

    @Test
    void legalIdentityRequired_flagOff_onlyAudiencePlan() {
        AudiencePlanAccess access = new AudiencePlanAccess(new AudiencePlanProperties());
        assertThat(access.legalIdentityRequired("audience_plan")).isTrue();
        assertThat(access.legalIdentityRequired("manual")).isFalse();
        assertThat(access.legalIdentityRequired("momentum")).isFalse();
        assertThat(access.legalIdentityRequired(null)).isFalse();
    }

    @Test
    void legalIdentityRequired_flagOn_everyOrigin() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setLegalIdentityAllCampaigns(true);
        AudiencePlanAccess access = new AudiencePlanAccess(props);
        assertThat(access.legalIdentityRequired("audience_plan")).isTrue();
        assertThat(access.legalIdentityRequired("manual")).isTrue();
        assertThat(access.legalIdentityRequired("momentum")).isTrue();
        assertThat(access.legalIdentityRequired(null)).isTrue();
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
