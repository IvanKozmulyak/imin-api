package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** One dispatcher tick through the real send unit, materializer and sender. */
@IminIntegrationTest
class CampaignDispatcherTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void stubProvider() {
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());
    }

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** runOnce reads Instant.now(): an offset that puts the org's local time near noon right now. */
    private Organization awakeOrg() {
        Organization o = fx.org();
        o.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Campaign scheduled(Instant at) {
        return scheduled(at, "pending");
    }

    /** A campaign with one recipient row, so the materializer no-ops and the sender drives it. */
    private Campaign scheduled(Instant at, String rowStatus) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(awakeOrg().getId());
        c.setChannel("email");
        c.setName("Due");
        c.setStatus("scheduled");
        c.setScheduledAt(at);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setEmail(fx.email("disp"));
        r.setStatus(rowStatus);
        recipients.save(r);
        return c;
    }

    private Campaign reload(Campaign c) {
        return campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
    }

    /** A drained campaign ends sent, also when every recipient was skipped at materialisation and none failed. */
    @ParameterizedTest(name = "recipient {0}")
    @CsvSource({"pending, 1", "skipped, 0"})
    void claimsDueScheduledCampaignAndDrivesToSent(String rowStatus, long sent) {
        Campaign c = scheduled(Instant.now().minus(3650, ChronoUnit.DAYS), rowStatus);

        dispatcher.runOnce();

        Campaign after = reload(c);
        assertThat(after.getStatus()).isEqualTo("sent");
        assertThat(after.getSentAt()).isNotNull();
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(sent);
    }

    @Test
    void ignoresFutureScheduledCampaign() {
        Campaign due = scheduled(Instant.now().minus(3650, ChronoUnit.DAYS));
        Campaign future = scheduled(Instant.now().plus(1, ChronoUnit.HOURS));

        dispatcher.runOnce();

        assertThat(reload(future).getStatus()).isEqualTo("scheduled");
        assertThat(recipients.countByCampaignIdAndStatus(future.getId(), "pending")).isEqualTo(1L);
        assertThat(reload(due).getStatus()).isEqualTo("sent");
    }

    @Test
    void midDriveCrash_failsTheCampaignAndNeverStampsItSent() {
        Campaign c = scheduled(Instant.now().minus(3650, ChronoUnit.DAYS));

        // The batch's 'sent' flip fails inside the sender's own transaction, after processOne flipped to sending.
        try (var fault = PgFaults.failWrites(jdbc, "campaign_recipients", "campaign_id", c.getId())) {
            dispatcher.runOnce();
        }

        Campaign after = reload(c);
        assertThat(after.getStatus()).isEqualTo("failed");
        assertThat(after.getAttempts()).isGreaterThanOrEqualTo((short) 1);
        assertThat(after.getSentAt()).isNull();
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(1L);
    }
}
