package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@IminIntegrationTest
class CampaignServiceTest {

    @Autowired CampaignService service;
    @Autowired CampaignEmailProvider provider;
    @Autowired MarketingEmailProperties marketingProps;
    @Autowired PropertyFlips flips;
    @Autowired AuditRows audit;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.imin.iminapi.repository.OrganizationRepository orgs;
    @Autowired Clock clock;

    // Campaign orgs need no organizations row (no FK); test-send resolves the caller's user row only.
    private UUID ORG;
    private UUID OTHER_ORG;
    private UUID USER;
    private String callerEmail;
    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ORG = UUID.randomUUID();
        OTHER_ORG = UUID.randomUUID();
        orgIds.add(ORG);
        orgIds.add(OTHER_ORG);
        User caller = fx.owner(fx.org());
        USER = caller.getId();
        callerEmail = caller.getEmail();
        flips.set(marketingProps, "fromAddress", "contact@imin.support");
    }

    // Sends and retries leave scheduled campaigns the dispatcher would claim.
    @AfterEach
    void tearDown() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** Every batch the shared provider fake received, narrowed to this test's caller. */
    @SuppressWarnings("unchecked")
    private List<CampaignEmailProvider.OutgoingEmail> sentToCaller() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> callerEmail.equals(e.to())).toList();
    }

    private CampaignEmailProvider.OutgoingEmail sentTest() {
        List<CampaignEmailProvider.OutgoingEmail> mine = sentToCaller();
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    private AuthPrincipal principal(UUID org) {
        return new AuthPrincipal(USER, org, UserRole.OWNER, UUID.randomUUID());
    }

    /**
     * mkt-core-7: 'sms' is an accepted channel, but CampaignRepository.claimDue filters
     * WHERE channel='email', so a scheduled SMS campaign was never claimed, never failed and
     * never timed out — it sat 'scheduled' for ever with no signal to the organizer. Refuse
     * the send instead of manufacturing a terminal-looking state that cannot progress.
     */
    @Test
    void send_refuses_an_sms_campaign_while_no_sms_dispatcher_exists() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("sms", "Text blast", null, null, null, null, null, null));

        assertThatThrownBy(() -> service.send(d.id(), principal(ORG), "idem-" + UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SMS");

        // Still a draft — nothing was moved into a state nothing can drain.
        assertThat(service.get(principal(ORG), d.id()).status()).isEqualTo("draft");
    }

    @Test
    void create_persists_a_draft_and_returns_detail() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch night", null, null, null, null, null, null));
        assertThat(d.status()).isEqualTo("draft");
        assertThat(d.channel()).isEqualTo("email");
        assertThat(d.name()).isEqualTo("Launch night");
        assertThat(service.get(principal(ORG), d.id()).name()).isEqualTo("Launch night");
    }

    @Test
    void create_rejects_blank_name() {
        assertThatThrownBy(() -> service.create(principal(ORG),
                new CreateCampaignRequest("email", "  ", null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void create_rejects_unknown_channel() {
        assertThatThrownBy(() -> service.create(principal(ORG),
                new CreateCampaignRequest("carrier-pigeon", "x", null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void get_other_orgs_campaign_is_not_found() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Mine", null, null, null, null, null, null));
        assertThatThrownBy(() -> service.get(principal(OTHER_ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void patch_applies_only_supplied_fields_on_a_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch", null, null, "Old subj", null, "body", null));
        CampaignDto patched = service.patch(principal(ORG), d.id(),
                new PatchCampaignRequest(null, null, null, "New subj", null, null, null));
        assertThat(patched.subject()).isEqualTo("New subj");
        assertThat(patched.name()).isEqualTo("Launch");   // untouched
        assertThat(patched.bodyMd()).isEqualTo("body");    // untouched
    }

    @Test
    void patch_rejects_non_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch", null, null, null, null, null, null));
        service.forceStatusForTest(d.id(), "sent");
        assertThatThrownBy(() -> service.patch(principal(ORG), d.id(),
                new PatchCampaignRequest("x", null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void list_is_org_scoped_and_channel_filtered() {
        service.create(principal(ORG),
                new CreateCampaignRequest("email", "E1", null, null, null, null, null, null));
        service.create(principal(OTHER_ORG),
                new CreateCampaignRequest("email", "Other", null, null, null, null, null, null));
        List<CampaignSummary> mine = service.list(principal(ORG), "email", null, 0, 50);
        assertThat(mine).extracting(CampaignSummary::name).contains("E1").doesNotContain("Other");
    }

    @Test
    void list_is_newest_first_with_no_filters() {
        Instant now = clock.instant();
        saveDraft(ORG, "A", now);
        saveDraft(OTHER_ORG, "B-other", now.plusSeconds(1));
        saveDraft(ORG, "C", now.plusSeconds(5));

        List<CampaignSummary> mine = service.list(principal(ORG), null, null, 0, 50);

        assertThat(mine).extracting(CampaignSummary::name).containsExactly("C", "A");
    }

    private void saveDraft(UUID org, String name, Instant createdAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org);
        c.setChannel("email");
        c.setName(name);
        c.setStatus("draft");
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(createdAt);
        campaignRepo.save(c);
    }

    @Test
    void duplicate_clones_into_a_new_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Repeat night", null, null, "Subj", "Pre", "body", null));
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
                new CreateCampaignRequest("email", "No segment", null, null, null, null, null, null));
        PreviewAudienceResponse r = service.previewAudience(principal(ORG), d.id());
        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().noBasis()).isZero();
    }

    @Test
    void test_send_uses_the_marketing_provider_and_targets_the_caller() {
        // USER is resolved to its own address (seeded in @BeforeEach — see NOTE)
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Test me", null, null,
                        "Subject line", "Preheader", "Hello **there**", null));
        service.testSend(principal(ORG), d.id(), null);

        CampaignEmailProvider.OutgoingEmail e = sentTest();
        assertThat(e.to()).isEqualTo(callerEmail);
        assertThat(e.subject()).isEqualTo("[TEST] Subject line");
        assertThat(e.unsubscribeUrl()).contains("preview");
    }

    @Test
    void test_send_isFromTheOrganizerViaImin_withTheLegalFooterInHtmlAndText() {
        com.imin.iminapi.model.Organization o = new com.imin.iminapi.model.Organization();
        o.setName("Night Org");
        o.setBrandName("Night");
        o.setSlug("ts-" + UUID.randomUUID());
        o.setContactEmail(fx.email("ops"));
        o.setCountry("FR");
        o.setLegalName("Night SAS");
        o.setLegalContact("legal@night.test");
        UUID orgId = orgs.save(o).getId();
        orgIds.add(orgId);
        CampaignDto d = service.create(principal(orgId),
                new CreateCampaignRequest("email", "Footer", null, null, "Subject", "Pre", "Body", null));

        service.testSend(principal(orgId), d.id(), null);

        CampaignEmailProvider.OutgoingEmail e = sentTest();
        assertThat(e.from()).isEqualTo("\"Night via IMIN\" <contact@imin.support>");
        assertThat(e.html()).contains("Night &middot; Night SAS &middot; legal@night.test");
        assertThat(e.text()).contains("Night · Night SAS · legal@night.test\nUnsubscribe: ");
    }

    @Test
    void test_send_renders_through_the_branded_template_shell_not_bare_text() {
        // Regression: test-send used to ship a bare <pre> escape of the markdown (no template,
        // no branded shell, no footer). It must now render through the SAME CampaignEmailRenderer
        // path as the real batch send: branded HTML + rendered markdown + mandatory unsubscribe.
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Branded", null, null,
                        "Subject line", "Preheader", "Hello **there**", "midnight"));
        service.testSend(principal(ORG), d.id(), null);

        CampaignEmailProvider.OutgoingEmail e = sentTest();
        assertThat(e.html()).contains("<!DOCTYPE html>");
        assertThat(e.html()).contains("<strong>there</strong>"); // markdown was rendered
        assertThat(e.html()).contains("#0f0a1f");                 // midnight template applied
        assertThat(e.html().toLowerCase()).contains("unsubscribe");
        assertThat(e.text()).contains("Unsubscribe:");            // plain-text part carries the footer too
    }

    @Test
    void test_send_falls_back_to_the_classic_template_when_key_is_absent() {
        // A campaign created with no templateKey stores/render-resolves to the classic builtin —
        // the test send is still a branded shell, never bare text.
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "No template", null, null,
                        "Subject", "Pre", "Body copy", null));
        service.testSend(principal(ORG), d.id(), null);

        String html = sentTest().html();
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("#f4f2fa"); // classic page background
    }

    @Test
    void test_send_withoutAMarketingFromAddress_fails_andSendsNothing() {
        flips.set(marketingProps, "fromAddress", "");
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "No sender", null, null, "Subject", "Pre", "Body", null));

        assertThatThrownBy(() -> service.testSend(principal(ORG), d.id(), null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Marketing email sender not configured");
        assertThat(sentToCaller()).isEmpty();
        verify(provider, never()).sendBatch(any());
    }

    @Test
    void test_send_rejects_a_non_email_campaign_with_empty_subject() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Empty", null, null, null, null, null, null));
        assertThatThrownBy(() -> service.testSend(principal(ORG), d.id(), null))
                .isInstanceOf(ApiException.class);
    }

    // ---- Task 12: cancel / retry / detail-with-stats ----

    @Test
    void cancel_flips_a_scheduled_campaign_to_canceled() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Cancel me", null, null, null, null, null, null));
        service.forceStatusForTest(d.id(), "scheduled");
        service.cancel(principal(ORG), d.id());
        assertThat(service.get(principal(ORG), d.id()).status()).isEqualTo("canceled");
    }

    @Test
    void cancel_rejects_a_non_scheduled_campaign() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Sent already", null, null, null, null, null, null));
        service.forceStatusForTest(d.id(), "sent");
        assertThatThrownBy(() -> service.cancel(principal(ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void retry_requeues_a_failed_campaign_to_scheduled() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Retry me", null, null, null, null, null, null));
        service.forceStatusForTest(d.id(), "failed");
        service.retry(principal(ORG), d.id());
        CampaignDto after = service.get(principal(ORG), d.id());
        assertThat(after.status()).isEqualTo("scheduled");
        assertThat(after.scheduledAt()).isNotNull();
    }

    @Test
    void retry_rejects_a_non_failed_campaign() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Draft still", null, null, null, null, null, null));
        assertThatThrownBy(() -> service.retry(principal(ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    /**
     * mkt-edge-2: /send was the one campaign mutation with neither a role gate nor an audit
     * row — any MEMBER could dispatch bulk mail to the whole audience and leave no trace of
     * who did it. Now ADMIN-or-above, 403 for a MEMBER (within-org refusal, per RoleGuard).
     */
    @Test
    void send_refuses_a_member() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Blast", null, null, null, null, null, null));
        AuthPrincipal member = new AuthPrincipal(USER, ORG, UserRole.MEMBER, UUID.randomUUID());

        assertThatThrownBy(() -> service.send(d.id(), member, "idem-" + UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);

        // Refused before the CAS — the campaign is still a draft nobody scheduled.
        assertThat(service.get(principal(ORG), d.id()).status()).isEqualTo("draft");
    }

    @Test
    void send_records_who_dispatched_the_campaign() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Blast", null, null, null, null, null, null));

        service.send(d.id(), principal(ORG), "idem-" + UUID.randomUUID(), null);

        assertThat(audit.assertRecorded(ORG, AuditActions.CAMPAIGN_SENT, "campaign", d.id()).getActorId())
                .isEqualTo(USER);
    }

    @Test
    void send_replay_does_not_record_a_second_dispatch() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Blast", null, null, null, null, null, null));
        service.send(d.id(), principal(ORG), "idem-1", null);

        assertThatThrownBy(() -> service.send(d.id(), principal(ORG), "idem-2", null))
                .isInstanceOf(ApiException.class);

        audit.assertRecorded(ORG, AuditActions.CAMPAIGN_SENT, "campaign", d.id());
    }

    @Test
    void detail_with_stats_returns_a_zeroed_stats_block_before_any_send() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Fresh", null, null, null, null, null, null));
        var detail = service.detailWithStats(principal(ORG), d.id());
        assertThat(detail.name()).isEqualTo("Fresh");
        assertThat(detail.stats()).isNotNull();
        assertThat(detail.stats().sent()).isZero();
        assertThat(detail.stats().opened()).isZero();
        assertThat(detail.stats().attributedPurchases()).isZero();
    }

    // ---- AI Act Art.50 provenance (ADR-0005) ----

    @Autowired com.imin.iminapi.marketing.service.CampaignAiSuggestions aiSuggestions;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRepository campaignRepo;

    private CampaignDto draft() {
        return service.create(principal(ORG),
                new CreateCampaignRequest("email", "AI draft", null, null, null, null, null, null));
    }

    private static PatchCampaignRequest patch(String subject, String preheader, String bodyMd,
                                              Boolean subjectAi, Boolean bodyAi) {
        return new PatchCampaignRequest(null, null, null, subject, preheader, bodyMd, null, subjectAi, bodyAi);
    }

    @Test
    void create_withoutFlags_isNotAiGenerated() {
        CampaignDto d = draft();

        assertThat(d.subjectAiGenerated()).isFalse();
        assertThat(d.bodyAiGenerated()).isFalse();
    }

    @Test
    void create_storesTheClientsTrueFlags() {
        CampaignDto d = service.create(principal(ORG), new CreateCampaignRequest("email", "AI", null, null,
                "S", "P", "B", null, true, true));

        var saved = campaignRepo.findById(d.id()).orElseThrow();
        assertThat(saved.isSubjectAiGenerated()).isTrue();
        assertThat(saved.isBodyAiGenerated()).isTrue();
        assertThat(service.get(principal(ORG), d.id()).subjectAiGenerated()).isTrue();
        assertThat(service.detailWithStats(principal(ORG), d.id()).bodyAiGenerated()).isTrue();
    }

    /**
     * AI provenance on patch: a client true sets a flag, a client false never clears one, and text a model
     * offered for this campaign flags its own field when saved verbatim (whitespace and line endings aside).
     */
    record AiPatchCase(String label, boolean flaggedBefore, String offeredFor, String offeredKind,
                       List<String> offered, PatchCampaignRequest patch, boolean subjectAi, boolean bodyAi) {
        @Override public String toString() { return label; }
    }

    static Stream<AiPatchCase> aiPatchCases() {
        return Stream.of(
                new AiPatchCase("client true sets the flags", false, null, null, List.of(),
                        patch("S", null, "B", true, true), true, true),
                new AiPatchCase("client false never clears a set flag", true, null, null, List.of(),
                        patch("Rewritten", "P", "Rewritten body", false, false), true, true),
                new AiPatchCase("an offered subject saved verbatim flags the subject", false, "self", "subject",
                        List.of("Doors close soon"), patch("  Doors close soon \n", null, "My own words", null, null),
                        true, false),
                new AiPatchCase("an offered body flags the body", false, "self", "body",
                        List.of("Offered pre", "Line one\nLine two"), patch("Mine", null, "Line one\r\nLine two", null, null),
                        false, true),
                new AiPatchCase("an offered preheader flags the body", false, "self", "body",
                        List.of("Offered pre"), patch(null, "Offered pre", null, null, null), false, true),
                new AiPatchCase("text offered for another campaign does not flag", false, "other", "subject",
                        List.of("Doors close soon"), patch("Doors close soon", null, null, null, null), false, false),
                new AiPatchCase("an offered subject saved as the body does not flag the body", false, "self", "subject",
                        List.of("Doors close soon"), patch(null, null, "Doors close soon", null, null), false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aiPatchCases")
    void patch_aiProvenanceFlags(AiPatchCase c) {
        CampaignDto other = draft();
        CampaignDto d = draft();
        if (c.flaggedBefore()) service.patch(principal(ORG), d.id(), patch("S", null, "B", true, true));
        if (c.offeredFor() != null) {
            aiSuggestions.record("self".equals(c.offeredFor()) ? d.id() : other.id(), c.offeredKind(), c.offered());
        }

        CampaignDto out = service.patch(principal(ORG), d.id(), c.patch());

        assertThat(out.subjectAiGenerated()).isEqualTo(c.subjectAi());
        assertThat(out.bodyAiGenerated()).isEqualTo(c.bodyAi());
    }

    @Test
    void duplicate_carriesTheFlags() {
        CampaignDto d = service.create(principal(ORG), new CreateCampaignRequest("email", "AI", null, null,
                "S", "P", "B", null, true, false));

        CampaignDto copy = service.duplicate(principal(ORG), d.id());

        assertThat(copy.subjectAiGenerated()).isTrue();
        assertThat(copy.bodyAiGenerated()).isFalse();
    }

    @Test
    void testSend_ofAnAiCampaign_carriesTheHeadersAndTheMeta() {
        CampaignDto d = service.create(principal(ORG), new CreateCampaignRequest("email", "AI", null, null,
                "Subject line", "Preheader", "Hello", null, false, true));

        service.testSend(principal(ORG), d.id(), null);

        CampaignEmailProvider.OutgoingEmail e = sentTest();
        assertThat(e.to()).isEqualTo(callerEmail);
        assertThat(e.subject()).isEqualTo("[TEST] Subject line");
        assertThat(e.ai().headers()).containsEntry("AI-Disclosure", "mode=ai-originated")
                .containsEntry("X-IMIN-AI-Generated", "body");
        assertThat(e.html()).contains("<meta name=\"imin-ai-generated\" content=\"body\"/>");
    }

    @Test
    void testSend_ofAHumanCampaign_carriesNoAiHeaders() {
        CampaignDto d = service.create(principal(ORG), new CreateCampaignRequest("email", "Mine", null, null,
                "Subject line", "Preheader", "Hello", null));

        service.testSend(principal(ORG), d.id(), null);

        assertThat(sentTest().ai().any()).isFalse();
    }
}
