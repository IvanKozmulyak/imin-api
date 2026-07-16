package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.RecipientDto;
import com.imin.iminapi.marketing.dto.RecipientPage;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of {@code GET /api/v1/marketing/campaigns/{id}/recipients} against REAL repositories —
 * the counts and totals here must be genuine SQL aggregates, so mocking the repo would test nothing.
 *
 * <p>Covers: total across pages, chip counts vs. real aggregates (including a row that is both
 * delivered AND opened), engagement filtering as an axis orthogonal to status, a null-membership
 * row degrading to a null name, and org scoping.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignRecipientLogTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @MockitoBean AuditLogger auditLogger;

    private AuthPrincipal principal(UUID org) {
        return new AuthPrincipal(UUID.randomUUID(), org, UserRole.OWNER, UUID.randomUUID());
    }

    /** created_at / updated_at are NOT NULL with no @PrePersist default (Phase-1 convention). */
    private Campaign campaign(UUID orgId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("log-test");
        c.setStatus("sent");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    /**
     * consumer_id / membership_id are @GeneratedValue(UUID) — let JPA assign them, otherwise
     * Spring Data treats the entity as detached and issues a merge/UPDATE against a missing row.
     */
    private Membership membership(UUID orgId, String displayName) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("log-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setDisplayName(displayName);
        return memberships.save(m);
    }

    private CampaignRecipient recipient(UUID campaignId, UUID membershipId, String status,
                                        Instant openedAt, Instant clickedAt) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setMembershipId(membershipId);
        r.setEmail("addr-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        r.setStatus(status);
        r.setOpenedAt(openedAt);
        r.setClickedAt(clickedAt);
        if ("delivered".equals(status)) r.setDeliveredAt(Instant.now());
        return recipients.save(r);
    }

    // ---- 1. total is a real aggregate, stable and page-independent ----

    @Test
    void total_countsTheWholeLog_notTheLoadedPage() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        for (int i = 0; i < 7; i++) recipient(c.getId(), null, "delivered", null, null);

        RecipientPage first = service.listRecipients(c.getId(), principal(org), null, null, 0, 3);
        assertThat(first.items()).hasSize(3);
        assertThat(first.total()).isEqualTo(7L);   // NOT 3 — the whole log, not the page

        RecipientPage last = service.listRecipients(c.getId(), principal(org), null, null, 2, 3);
        assertThat(last.items()).hasSize(1);
        assertThat(last.total()).isEqualTo(7L);    // identical on every page
        assertThat(last.page()).isEqualTo(2);
        assertThat(last.size()).isEqualTo(3);
    }

    @Test
    void paging_isDeterministic_everyRowSeenExactlyOnce() {
        // Without an ORDER BY, Postgres may return offset pages in any order — rows would silently
        // repeat or vanish while `total` still looked authoritative. The service pins an id sort.
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        for (int i = 0; i < 10; i++) recipient(c.getId(), null, "sent", null, null);

        List<UUID> seen = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            seen.addAll(service.listRecipients(c.getId(), principal(org), null, null, p, 2)
                    .items().stream().map(RecipientDto::id).toList());
        }
        assertThat(seen).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void total_reflectsTheActiveFilter_notTheWholeLog() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        for (int i = 0; i < 5; i++) recipient(c.getId(), null, "delivered", null, null);
        for (int i = 0; i < 2; i++) recipient(c.getId(), null, "skipped", null, null);

        RecipientPage skipped = service.listRecipients(c.getId(), principal(org), "skipped", null, 0, 50);
        assertThat(skipped.total()).isEqualTo(2L);
        // ...while the chip counts stay whole-log regardless of the filter.
        assertThat(skipped.counts().total()).isEqualTo(7L);
    }

    // ---- 2. chip counts match real aggregates; engagement is orthogonal to status ----

    @Test
    void counts_matchRealAggregates_andARowCanBeDeliveredAndOpened() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        Instant t = Instant.now();

        // The load-bearing row: delivered AND opened AND clicked at once. If engagement were
        // folded into the status enum this row could only be counted once — it must count in
        // `total`, in `opened` and in `clicked` simultaneously.
        recipient(c.getId(), null, "delivered", t, t);
        recipient(c.getId(), null, "delivered", t, null);   // delivered + opened only
        recipient(c.getId(), null, "delivered", null, null);
        recipient(c.getId(), null, "skipped", null, null);
        recipient(c.getId(), null, "bounced", null, null);
        recipient(c.getId(), null, "failed", null, null);
        recipient(c.getId(), null, "complained", null, null);
        recipient(c.getId(), null, "unsubscribed", null, null);

        var counts = service.listRecipients(c.getId(), principal(org), null, null, 0, 50).counts();

        assertThat(counts.total()).isEqualTo(8L);
        assertThat(counts.opened()).isEqualTo(2L);        // both timestamped rows, whatever their status
        assertThat(counts.clicked()).isEqualTo(1L);
        assertThat(counts.skipped()).isEqualTo(1L);
        assertThat(counts.bounced()).isEqualTo(1L);
        assertThat(counts.failed()).isEqualTo(1L);
        assertThat(counts.complained()).isEqualTo(1L);
        assertThat(counts.unsubscribed()).isEqualTo(1L);

        // Cross-check every bucket against an independently-issued aggregate.
        assertThat(counts.total()).isEqualTo(recipients.countByCampaignId(c.getId()));
        assertThat(counts.opened()).isEqualTo(recipients.countByCampaignIdAndOpenedAtNotNull(c.getId()));
        assertThat(counts.clicked()).isEqualTo(recipients.countByCampaignIdAndClickedAtNotNull(c.getId()));
        assertThat(counts.skipped()).isEqualTo(recipients.countByCampaignIdAndStatus(c.getId(), "skipped"));
        assertThat(counts.bounced()).isEqualTo(recipients.countByCampaignIdAndStatus(c.getId(), "bounced"));

        // The buckets deliberately do NOT sum to total — engagement overlaps lifecycle.
        assertThat(counts.opened() + counts.clicked()).isGreaterThan(0L);
    }

    @Test
    void counts_areZeroed_onACampaignWithNoRecipients() {
        // sum(case...) is NULL over an empty table; coalesce must keep it off the primitive fields.
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);

        RecipientPage page = service.listRecipients(c.getId(), principal(org), null, null, 0, 50);
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.counts().total()).isZero();
        assertThat(page.counts().opened()).isZero();
        assertThat(page.counts().unsubscribed()).isZero();
    }

    // ---- 3. engagement filtering ----

    @Test
    void engagementFilter_pagesTheOpenedAndClickedSubsets() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        Instant t = Instant.now();
        recipient(c.getId(), null, "delivered", t, t);      // opened + clicked
        recipient(c.getId(), null, "delivered", t, null);   // opened only
        recipient(c.getId(), null, "delivered", null, null);
        recipient(c.getId(), null, "sent", null, null);

        RecipientPage opened = service.listRecipients(c.getId(), principal(org), null, "opened", 0, 50);
        assertThat(opened.items()).hasSize(2);
        assertThat(opened.total()).isEqualTo(2L);
        assertThat(opened.items()).allSatisfy(r -> assertThat(r.openedAt()).isNotNull());

        RecipientPage clicked = service.listRecipients(c.getId(), principal(org), null, "clicked", 0, 50);
        assertThat(clicked.items()).hasSize(1);
        assertThat(clicked.total()).isEqualTo(1L);
        assertThat(clicked.items().get(0).clickedAt()).isNotNull();
        // Engagement did not become a status — the row is still `delivered`.
        assertThat(clicked.items().get(0).status()).isEqualTo("delivered");
    }

    @Test
    void engagementFilter_combinesWithStatus_asAnOrthogonalAxis() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        Instant t = Instant.now();
        recipient(c.getId(), null, "delivered", t, null);
        recipient(c.getId(), null, "bounced", t, null);     // opened, but a different lifecycle
        recipient(c.getId(), null, "delivered", null, null);

        RecipientPage deliveredAndOpened =
                service.listRecipients(c.getId(), principal(org), "delivered", "opened", 0, 50);
        assertThat(deliveredAndOpened.total()).isEqualTo(1L);
        assertThat(deliveredAndOpened.items()).hasSize(1);
        assertThat(deliveredAndOpened.items().get(0).status()).isEqualTo("delivered");
        assertThat(deliveredAndOpened.items().get(0).openedAt()).isNotNull();
    }

    @Test
    void statusFilter_acceptsACommaSeparatedList_forTheIssuesChip() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        recipient(c.getId(), null, "bounced", null, null);
        recipient(c.getId(), null, "failed", null, null);
        recipient(c.getId(), null, "complained", null, null);
        recipient(c.getId(), null, "delivered", null, null);

        RecipientPage issues = service.listRecipients(
                c.getId(), principal(org), "bounced,failed,complained", null, 0, 50);
        assertThat(issues.total()).isEqualTo(3L);
        assertThat(issues.items()).hasSize(3);
        assertThat(issues.items()).noneSatisfy(r -> assertThat(r.status()).isEqualTo("delivered"));
    }

    @Test
    void engagementFilter_rejectsAnUnknownValue_ratherThanSilentlyIgnoringIt() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        assertThatThrownBy(() -> service.listRecipients(c.getId(), principal(org), null, "bogus", 0, 50))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("engagement");
    }

    // ---- 4. recipient name ----

    @Test
    void name_isTheJoinedMembershipDisplayName() {
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        Membership m = membership(org, "Ada Lovelace");
        recipient(c.getId(), m.getMembershipId(), "delivered", null, null);

        RecipientPage page = service.listRecipients(c.getId(), principal(org), null, null, 0, 50);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Ada Lovelace");
    }

    @Test
    void name_degradesToNull_whenTheMembershipWasDeleted() {
        // V53's FK is ON DELETE SET NULL, so a DSAR erase leaves membership_id NULL. The row must
        // still render — with a null name, not a placeholder and not an exception.
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        Membership m = membership(org, "Grace Hopper");
        recipient(c.getId(), m.getMembershipId(), "delivered", null, null);
        recipient(c.getId(), null, "delivered", null, null);   // membership already gone

        RecipientPage page = service.listRecipients(c.getId(), principal(org), null, null, 0, 50);
        assertThat(page.items()).hasSize(2);
        assertThat(page.total()).isEqualTo(2L);   // the orphan row still counts
        assertThat(page.items()).extracting(RecipientDto::name)
                .containsExactlyInAnyOrder("Grace Hopper", null);
    }

    @Test
    void aPageOfEntirelyNullMembershipRows_stillRenders() {
        // Regression: when NO row on the page has a membership, the name lookup short-circuits to
        // an empty map. Map.of() throws NPE on a null key (unlike HashMap), so leaning on
        // `names.get(null)` returning null blew the whole page up with a 500 — precisely the
        // "a deleted membership must never break the row or the page" case.
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        for (int i = 0; i < 3; i++) recipient(c.getId(), null, "delivered", null, null);

        RecipientPage page = service.listRecipients(c.getId(), principal(org), null, null, 0, 50);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items()).extracting(RecipientDto::name).containsOnlyNulls();
        assertThat(page.total()).isEqualTo(3L);
    }

    @Test
    void name_isNull_whenTheMembershipHasNoDisplayName() {
        // Return null rather than a placeholder — the FE decides how to render absence.
        UUID org = UUID.randomUUID();
        Campaign c = campaign(org);
        Membership m = membership(org, null);
        recipient(c.getId(), m.getMembershipId(), "delivered", null, null);

        RecipientPage page = service.listRecipients(c.getId(), principal(org), null, null, 0, 50);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isNull();
        assertThat(page.items().get(0).email()).isNotNull();   // the row is intact
    }

    // ---- 5. org scoping ----

    @Test
    void anotherOrgsCampaign_is404_notALeakedLog() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Campaign c = campaign(org);
        recipient(c.getId(), null, "delivered", null, null);

        assertThatThrownBy(() -> service.listRecipients(c.getId(), principal(otherOrg), null, null, 0, 50))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void nameLookup_isOrgScoped_soAForeignMembershipNeverLeaksItsName() {
        // Defence in depth: even if a recipient row pointed at another org's membership, the
        // org-scoped batch lookup must miss and the name degrade to null.
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Campaign c = campaign(org);
        Membership foreign = membership(otherOrg, "Someone Else");
        recipient(c.getId(), foreign.getMembershipId(), "delivered", null, null);

        RecipientPage page = service.listRecipients(c.getId(), principal(org), null, null, 0, 50);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isNull();
    }
}
