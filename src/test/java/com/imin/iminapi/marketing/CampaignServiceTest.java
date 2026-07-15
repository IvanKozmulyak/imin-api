package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignServiceTest {

    @Autowired CampaignService service;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean EmailService emailService;
    @Autowired com.imin.iminapi.repository.UserRepository users;
    @Autowired com.imin.iminapi.repository.OrganizationRepository orgs;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    // USER is the caller's user id. testSend resolves it to the organizer's own email, so it MUST
    // correspond to a real users row. User.@Id is @GeneratedValue (persist rejects assigned ids),
    // so we seed a real User via save() and adopt its generated id here (in @BeforeEach).
    static UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private AuthPrincipal principal(UUID org) {
        return new AuthPrincipal(USER, org, UserRole.OWNER, UUID.randomUUID());
    }

    @org.junit.jupiter.api.BeforeEach
    void seedCaller() {
        if (users.findById(USER).isPresent()) return;
        // The user needs a parent organization row (users.org_id FK). The seeded user's org is
        // irrelevant to test-send (which only reads userId -> email), so a fresh org is fine.
        com.imin.iminapi.model.Organization o = new com.imin.iminapi.model.Organization();
        o.setName("Test Org");
        o.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("org@example.com");
        o.setCountry("DE");
        o = orgs.save(o);

        com.imin.iminapi.model.User u = new com.imin.iminapi.model.User();
        u.setOrgId(o.getId());                            // org_id is @Column(nullable=false) with NO default
        u.setRole(com.imin.iminapi.model.UserRole.OWNER); // role  is @Column(nullable=false) with NO default
        u.setEmail("organizer@example.com");              // setEmail() also derives emailLower
        u.setVerifiedAt(java.time.Instant.now());
        // firstName/lastName/avatarInitials/createdAt already have field-initializer defaults (User.java).
        USER = users.save(u).getId();                     // adopt the generated id as the caller id
    }

    @Test
    void create_persists_a_draft_and_returns_detail() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch night", null, null, null, null, null));
        assertThat(d.status()).isEqualTo("draft");
        assertThat(d.channel()).isEqualTo("email");
        assertThat(d.name()).isEqualTo("Launch night");
        assertThat(service.get(principal(ORG), d.id()).name()).isEqualTo("Launch night");
    }

    @Test
    void create_rejects_blank_name() {
        assertThatThrownBy(() -> service.create(principal(ORG),
                new CreateCampaignRequest("email", "  ", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void create_rejects_unknown_channel() {
        assertThatThrownBy(() -> service.create(principal(ORG),
                new CreateCampaignRequest("carrier-pigeon", "x", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void get_other_orgs_campaign_is_not_found() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Mine", null, null, null, null, null));
        assertThatThrownBy(() -> service.get(principal(OTHER_ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void patch_applies_only_supplied_fields_on_a_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch", null, null, "Old subj", null, "body"));
        CampaignDto patched = service.patch(principal(ORG), d.id(),
                new PatchCampaignRequest(null, null, null, "New subj", null, null));
        assertThat(patched.subject()).isEqualTo("New subj");
        assertThat(patched.name()).isEqualTo("Launch");   // untouched
        assertThat(patched.bodyMd()).isEqualTo("body");    // untouched
    }

    @Test
    void patch_rejects_non_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch", null, null, null, null, null));
        service.forceStatusForTest(d.id(), "sent");
        assertThatThrownBy(() -> service.patch(principal(ORG), d.id(),
                new PatchCampaignRequest("x", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void list_is_org_scoped_and_channel_filtered() {
        service.create(principal(ORG),
                new CreateCampaignRequest("email", "E1", null, null, null, null, null));
        service.create(principal(OTHER_ORG),
                new CreateCampaignRequest("email", "Other", null, null, null, null, null));
        List<CampaignSummary> mine = service.list(principal(ORG), "email", null, 0, 50);
        assertThat(mine).extracting(CampaignSummary::name).contains("E1").doesNotContain("Other");
    }

    @Test
    void duplicate_clones_into_a_new_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Repeat night", null, null, "Subj", "Pre", "body"));
        service.forceStatusForTest(d.id(), "sent");
        CampaignDto copy = service.duplicate(principal(ORG), d.id());
        assertThat(copy.id()).isNotEqualTo(d.id());
        assertThat(copy.status()).isEqualTo("draft");
        assertThat(copy.name()).isEqualTo("Repeat night (copy)");
        assertThat(copy.subject()).isEqualTo("Subj");
        assertThat(copy.bodyMd()).isEqualTo("body");
    }

    @Test
    void preview_audience_with_no_segment_is_all_zero() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "No segment", null, null, null, null, null));
        PreviewAudienceResponse r = service.previewAudience(principal(ORG), d.id());
        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().noBasis()).isZero();
    }

    @Test
    void test_send_uses_the_email_service_and_targets_the_caller() {
        // USER is resolved to its own address (seeded in @BeforeEach — see NOTE)
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Test me", null, null,
                        "Subject line", "Preheader", "Hello **there**"));
        service.testSend(principal(ORG), d.id(), null);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(to.capture(), anyString(), anyString(), anyString());
        assertThat(to.getValue()).isEqualTo("organizer@example.com");
    }

    @Test
    void test_send_rejects_a_non_email_campaign_with_empty_subject() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Empty", null, null, null, null, null));
        assertThatThrownBy(() -> service.testSend(principal(ORG), d.id(), null))
                .isInstanceOf(ApiException.class);
    }

    // ---- Task 12: cancel / retry / detail-with-stats ----

    @Test
    void cancel_flips_a_scheduled_campaign_to_canceled() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Cancel me", null, null, null, null, null));
        service.forceStatusForTest(d.id(), "scheduled");
        service.cancel(principal(ORG), d.id());
        assertThat(service.get(principal(ORG), d.id()).status()).isEqualTo("canceled");
    }

    @Test
    void cancel_rejects_a_non_scheduled_campaign() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Sent already", null, null, null, null, null));
        service.forceStatusForTest(d.id(), "sent");
        assertThatThrownBy(() -> service.cancel(principal(ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void retry_requeues_a_failed_campaign_to_scheduled() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Retry me", null, null, null, null, null));
        service.forceStatusForTest(d.id(), "failed");
        service.retry(principal(ORG), d.id());
        CampaignDto after = service.get(principal(ORG), d.id());
        assertThat(after.status()).isEqualTo("scheduled");
        assertThat(after.scheduledAt()).isNotNull();
    }

    @Test
    void retry_rejects_a_non_failed_campaign() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Draft still", null, null, null, null, null));
        assertThatThrownBy(() -> service.retry(principal(ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    // ---- Task B3: draft-only delete ----

    @Test
    void delete_removes_an_own_draft_and_it_is_gone() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Trash me", null, null, null, null, null));
        service.delete(principal(ORG), d.id());
        // The row is actually gone — a subsequent get 404s (org-scoped not-found).
        assertThatThrownBy(() -> service.get(principal(ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void delete_other_orgs_draft_is_not_found() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Mine to keep", null, null, null, null, null));
        assertThatThrownBy(() -> service.delete(principal(OTHER_ORG), d.id()))
                .isInstanceOf(ApiException.class);
        // Still there for the real owner — cross-org delete must not touch it.
        assertThat(service.get(principal(ORG), d.id()).name()).isEqualTo("Mine to keep");
    }

    @Test
    void delete_rejects_a_non_draft_campaign() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Already sent", null, null, null, null, null));
        service.forceStatusForTest(d.id(), "sent");
        assertThatThrownBy(() -> service.delete(principal(ORG), d.id()))
                .isInstanceOf(ApiException.class);
        // A rejected delete leaves the campaign intact.
        assertThat(service.get(principal(ORG), d.id()).status()).isEqualTo("sent");
    }

    @Test
    void detail_with_stats_returns_a_zeroed_stats_block_before_any_send() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Fresh", null, null, null, null, null));
        var detail = service.detailWithStats(principal(ORG), d.id());
        assertThat(detail.name()).isEqualTo("Fresh");
        assertThat(detail.stats()).isNotNull();
        assertThat(detail.stats().sent()).isZero();
        assertThat(detail.stats().opened()).isZero();
        assertThat(detail.stats().attributedPurchases()).isZero();
    }
}
