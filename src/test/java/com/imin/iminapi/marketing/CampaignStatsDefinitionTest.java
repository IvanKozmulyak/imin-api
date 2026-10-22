package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.marketing.dto.CampaignDetailDto;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.dto.RecipientCounts;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.marketing.send.RecipientMaterializer;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** The campaign page, the campaign list and the recipient log count the same rows the same way. */
@IminIntegrationTest
class CampaignStatsDefinitionTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired SegmentRepository segments;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    @Test
    void everyScreenCountsTheSameRows() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        AuthPrincipal p = fx.principal(fx.owner(org));
        Campaign c = campaign(org.getId(), "sent");
        Instant late = Instant.now();
        row(c, "sent", null, "no_provider_id", null);
        row(c, "delivered", null, null, late);
        row(c, "unsubscribed", null, null, late);          // delivered, then opted out
        row(c, "complained", null, null, late);
        row(c, "bounced", null, "hard_bounce", late);      // its delivered webhook arrived late
        row(c, "bounced", null, "soft_bounce", null);
        row(c, "failed", null, "provider_rejected", null);
        row(c, "failed", null, "send_failed", null);
        row(c, "pending", null, null, null);
        row(c, "skipped", "marketing_unsubscribed", null, null);
        row(c, "skipped", "frequency_capped", null, null);

        // A campaign whose every row failed or was skipped: nothing left, but its list row still carries the counts.
        Campaign nothingLeft = campaign(org.getId(), "failed");
        row(nothingLeft, "failed", null, "provider_rejected", null);
        row(nothingLeft, "skipped", "frequency_capped", null, null);

        CampaignDetailDto detail = service.detailWithStats(p, c.getId());
        List<CampaignSummary> page = service.list(p, null, null, 0, 50);
        CampaignSummary listed = page.stream().filter(s -> s.id().equals(c.getId())).findFirst().orElseThrow();
        CampaignSummary listedNothingLeft = page.stream()
                .filter(s -> s.id().equals(nothingLeft.getId())).findFirst().orElseThrow();
        RecipientCounts log = service.listRecipients(c.getId(), p, null, null, 0, 50).counts();

        var stats = detail.stats();
        assertThat(stats.sent()).isEqualTo(6);
        assertThat(stats.delivered()).isEqualTo(3);
        assertThat(stats.bounced()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(2);
        assertThat(stats.queued()).isEqualTo(1);
        assertThat(stats.skipped()).isEqualTo(2);
        // sent = reached the inbox + bounced + still waiting for a delivery confirmation (the one sent row)
        assertThat(stats.sent()).isEqualTo(stats.delivered() + stats.bounced() + 1);
        assertThat(listed.sentCount()).isEqualTo(stats.sent());
        assertThat(listed.failedCount()).isEqualTo(stats.failed());
        assertThat(listed.skippedCount()).isEqualTo(stats.skipped());
        assertThat(listed.queuedCount()).isEqualTo(stats.queued());
        assertThat(listedNothingLeft.sentCount()).isNull();
        assertThat(listedNothingLeft.failedCount()).isEqualTo(1);
        assertThat(listedNothingLeft.skippedCount()).isEqualTo(1);
        assertThat(listedNothingLeft.queuedCount()).isZero();
        assertThat(log.sent()).isEqualTo(stats.sent());
        assertThat(log.delivered()).isEqualTo(stats.delivered());
        assertThat(log.failed()).isEqualTo(stats.failed());
        assertThat(log.skipped()).isEqualTo(stats.skipped());
        assertThat(log.queued()).isEqualTo(stats.queued());
        assertThat(detail.skipReasons()).containsOnly(
                Map.entry("marketing_unsubscribed", 1L), Map.entry("frequency_capped", 1L));
    }

    @Test
    void theExclusionAuditCountsPeopleSkippedDuringTheSend() {
        Organization org = fx.org();
        org.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        org = orgs.save(org);
        orgIds.add(org.getId());
        AuthPrincipal p = fx.principal(fx.owner(org));
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv ->
                CampaignEmailProvider.BatchResult.accepted(((List<?>) inv.getArgument(1)).stream().map(x -> "msg-" + UUID.randomUUID()).toList()));
        Membership leaving = member(org.getId());
        Membership staying = member(org.getId());
        Campaign c = dueCampaign(org.getId(), List.of(leaving, staying));
        materializer.materialize(c);
        Campaign frozen = campaigns.findById(c.getId()).orElseThrow();
        assertThat(frozen.getExcludedCount()).isZero();

        leaving.setConsentStatus("unsubscribed");
        memberships.save(leaving);
        dispatcher.runOnce();

        CampaignDetailDto detail = service.detailWithStats(p, c.getId());
        assertThat(detail.skipReasons()).containsOnly(Map.entry("marketing_unsubscribed", 1L));
        assertThat(detail.stats().skipped()).isEqualTo(1);
        assertThat(detail.stats().sent()).isEqualTo(1);
        assertThat(detail.excludedCount()).isZero();   // the snapshot taken at materialization is not rewritten
    }

    private Campaign campaign(UUID orgId, String status) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Stats definition");
        c.setStatus(status);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private void row(Campaign c, String status, String skipReason, String errorCode, Instant deliveredAt) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setEmail(fx.email("def"));
        r.setStatus(status);
        r.setSkipReason(skipReason);
        r.setErrorCode(errorCode);
        r.setDeliveredAt(deliveredAt);
        recipients.save(r);
    }

    private Membership member(UUID orgId) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("def"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private Campaign dueCampaign(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Def " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(members.stream().map(m -> "\"" + m.getMembershipId() + "\"").toList().toString());
        UUID segmentId = segments.save(seg).getId();
        Campaign c = campaign(orgId, "scheduled");
        c.setScheduledAt(Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSegmentId(segmentId);
        return campaigns.save(c);
    }
}
