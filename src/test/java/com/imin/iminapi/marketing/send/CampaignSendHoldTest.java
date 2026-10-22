package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** A pause or hold placed while a drive runs stops it before its next batch, and lifting it lets the rest go. */
@IminIntegrationTest
class CampaignSendHoldTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"breakerPause", "orgWideHold", "holdOnItsEvent"})
    void aPauseOrHoldStopsTheDriveBeforeTheNextBatch(String hold) {
        Organization org = fx.org();
        org.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        org = orgs.save(org);
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        UUID eventId = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS)).getId();
        String tag = "hold-" + UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setEventId(eventId);
        c.setChannel("email");
        c.setName("Held blast");
        c.setStatus("scheduled");
        c.setScheduledAt(Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        for (int i = 0; i < 250; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setEmail(tag + "-" + i + "-" + fx.email("fan"));
            r.setStatus("pending");
            recipients.save(r);
        }
        UUID orgId = org.getId();
        AtomicInteger ourBatches = new AtomicInteger();
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (batch.stream().anyMatch(e -> e.to().startsWith(tag + "-")) && ourBatches.incrementAndGet() == 1) {
                // Placed while the first batch is at the provider.
                switch (hold) {
                    case "breakerPause" -> orgs.pauseMarketingIfNotPaused(orgId, "complaint_rate", Instant.now());
                    case "orgWideHold" -> hold(orgId, null, owner.getId());
                    default -> hold(orgId, eventId, owner.getId());
                }
            }
            return CampaignEmailProvider.BatchResult.accepted(batch.stream().map(e -> msgId(e.to())).toList());
        });

        dispatcher.runOnce();

        assertThat(ourBatches.get()).isEqualTo(1);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(100L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(150L);
        assertThat(campaigns.findStatusById(c.getId())).contains("sending");

        if ("breakerPause".equals(hold)) orgs.resumeMarketing(orgId, Instant.now());
        else jdbc.update("UPDATE moderation_holds SET lifted_at = now(), lifted_by = placed_by WHERE org_id = ? AND lifted_at IS NULL", orgId);
        // The stale reclaim picks the campaign up again on a later pass.
        jdbc.update("UPDATE campaigns SET updated_at = now() - interval '10 minutes' WHERE id = ?", c.getId());
        dispatcher.runOnce();

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(250L);
        assertThat(campaigns.findStatusById(c.getId())).contains("sent");
    }

    private void hold(UUID orgId, UUID eventId, UUID placedBy) {
        jdbc.update("""
                INSERT INTO moderation_holds (id, org_id, event_id, suspend_sales, hold_payouts, ground, facts,
                                              placed_by, placed_at)
                VALUES (?, ?, ?, TRUE, FALSE, 'fraud', 'test hold', ?, now())
                """, UUID.randomUUID(), orgId, eventId, placedBy);
    }

    /** A provider id derived from the address (the column holds 64 characters), so a shifted id shows. */
    private static String msgId(String to) {
        return "msg-" + UUID.nameUUIDFromBytes(to.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
