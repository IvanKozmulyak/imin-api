package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** An Art.17 request accepted after materialization stops the email, with the consent gate flag off as in the test yaml. */
@IminIntegrationTest
class EmailChannelSenderErasePendingTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired SegmentRepository segments;
    @Autowired DsarService dsar;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<String> sentTo = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordSends() {
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            batch.forEach(e -> sentTo.add(e.to()));
            return CampaignEmailProvider.BatchResult.accepted(batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList());
        });
    }

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    @Test
    void aMemberWhoAskedToBeErasedMidSendIsNotEmailed() {
        Organization org = awakeOrg();
        Membership leaving = member(org.getId());
        Membership staying = member(org.getId());
        Campaign c = dueCampaign(org.getId(), segmentOf(org.getId(), List.of(leaving, staying)));
        materializer.materialize(c);
        assertThat(rowOf(c, leaving).get("status")).isEqualTo("pending");

        dsar.requestErase(org.getId(), leaving.getMembershipId(), fx.principal(fx.owner(org)));
        dispatcher.runOnce();

        assertThat(sentTo).as("the run sent the campaign").contains(emailOf(staying));
        assertThat(sentTo).doesNotContain(emailOf(leaving));
        Map<String, Object> row = rowOf(c, leaving);
        assertThat(row.get("status")).isEqualTo("skipped");
        assertThat(row.get("skip_reason")).isEqualTo("erase_pending");
    }

    /** runOnce reads Instant.now(): an offset that puts the org's local time near noon right now. */
    private Organization awakeOrg() {
        Organization o = fx.org();
        o.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Membership member(UUID orgId) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("erase"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private UUID segmentOf(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Erase " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(members.stream().map(m -> "\"" + m.getMembershipId() + "\"").toList().toString());
        return segments.save(seg).getId();
    }

    private Campaign dueCampaign(UUID orgId, UUID segmentId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Mid-send erase");
        c.setStatus("scheduled");
        c.setScheduledAt(Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSegmentId(segmentId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private Map<String, Object> rowOf(Campaign c, Membership m) {
        return jdbc.queryForMap("SELECT status, skip_reason FROM campaign_recipients WHERE campaign_id = ? AND membership_id = ?",
                c.getId(), m.getMembershipId());
    }

    private String emailOf(Membership m) {
        return jdbc.queryForObject("SELECT normalized_email FROM consumers WHERE consumer_id = ?",
                String.class, m.getConsumerId());
    }
}
