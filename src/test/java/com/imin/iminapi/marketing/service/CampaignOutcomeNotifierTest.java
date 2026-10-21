package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.marketing.send.CampaignSendUnit;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** The creator hears once, in their language, when a campaign ends; the "A campaign ends" toggle and a gone creator are honoured. */
@IminIntegrationTest
class CampaignOutcomeNotifierTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignSendUnit sendUnit;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired NotificationPreferencesRepository prefs;
    @Autowired AudiencePlanProperties planProps;
    @Autowired PropertyFlips flips;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @BeforeEach
    void providerAccepts() {
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());
    }

    @AfterEach
    void cleanUp() {
        CampaignRows.delete(jdbc, orgIds);
        for (UUID id : userIds) jdbc.update("DELETE FROM notifications WHERE user_id = ?", id);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"segment_missing", "nothing_sent", "legal_identity_missing"})
    void aFailedCampaignNotifiesItsCreatorOnceInTheirLanguage(String reason) {
        flips.set(planProps, "sendsEnabled", true);
        Organization org = awakeOrg(!"legal_identity_missing".equals(reason));
        User creator = owner(org, "fr");
        Campaign c = campaign(org, creator.getId(), "legal_identity_missing".equals(reason) ? "audience_plan" : "manual");
        switch (reason) {
            case "segment_missing" -> {
                UUID gone = UUID.randomUUID();
                jdbc.update("INSERT INTO segments (id, org_id, name, kind, snapshot_ids) VALUES (?, ?, ?, 'static', '[]')",
                        gone, org.getId(), "Gone " + gone);
                c.setSegmentId(gone);
                campaigns.save(c);
                jdbc.update("DELETE FROM segments WHERE id = ?", gone);
                dispatcher.runOnce();
            }
            case "nothing_sent" -> {
                pendingRows(c, 2);
                when(provider.sendBatch(anyList())).thenThrow(
                        new CampaignEmailProvider.TerminalBatchFailure("Sender not verified", 422, null));
                dispatcher.runOnce();
            }
            default -> {
                pendingRows(c, 1);
                sendUnit.processOne(c);   // the claim never hands this campaign out, so drive it as a raced claim would
            }
        }

        assertThat(campaigns.findById(c.getId()).orElseThrow().getStatus()).isEqualTo("failed");
        List<Map<String, Object>> rows = notificationsOf(creator);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("campaign.failed");
        assertThat((String) rows.get(0).get("title")).startsWith("Campagne en échec");
        assertThat(rows.get(0).get("link")).isEqualTo("/marketing/campaigns/" + c.getId());
    }

    @Test
    void aSentCampaignNotifiesWithWhatLeft() {
        Organization org = awakeOrg(true);
        User creator = owner(org, "en");
        Campaign c = campaign(org, creator.getId(), "manual");
        pendingRows(c, 2);

        dispatcher.runOnce();

        List<Map<String, Object>> rows = notificationsOf(creator);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("campaign.sent");
        assertThat(rows.get(0).get("body")).isEqualTo("2 emails sent");
        assertThat(rows.get(0).get("link")).isEqualTo("/marketing/campaigns/" + c.getId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"preferenceOff", "creatorDisabled", "createdByNull"})
    void theRecipientFollowsThePreferenceAndFallsBackToTheOwner(String case_) {
        Organization org = awakeOrg(true);
        User first = owner(org, "en");
        User second = owner(org, "en");
        Campaign c = campaign(org, "createdByNull".equals(case_) ? null : first.getId(), "manual");
        if ("preferenceOff".equals(case_)) {
            NotificationPreferences p = new NotificationPreferences();
            p.setUserId(first.getId());
            p.setCampaignEnded(false);
            prefs.save(p);
        }
        if ("creatorDisabled".equals(case_)) {
            first.setDisabledAt(Instant.now());
            users.save(first);
        }
        pendingRows(c, 1);

        dispatcher.runOnce();

        switch (case_) {
            case "preferenceOff" -> {
                assertThat(notificationsOf(first)).isEmpty();
                assertThat(notificationsOf(second)).isEmpty();
            }
            case "creatorDisabled" -> {
                assertThat(notificationsOf(first)).isEmpty();
                assertThat(notificationsOf(second)).hasSize(1);
            }
            default -> {
                assertThat(notificationsOf(first)).hasSize(1);
                assertThat(notificationsOf(second)).isEmpty();
            }
        }
    }

    @ParameterizedTest(name = "attempts {0}, origin {1}, plan sends {2}")
    @CsvSource({"0,manual,false,true", "2,manual,false,false", "0,audience_plan,false,false", "0,audience_plan,true,true"})
    void theFailureAdviceOnlyOffersARetryTheServerWouldAccept(int attemptsBefore, String origin, boolean plansSend,
                                                              boolean retryable) {
        flips.set(planProps, "sendsEnabled", plansSend);
        Organization org = awakeOrg(true);
        User creator = owner(org, "en");
        Campaign c = campaign(org, creator.getId(), origin);
        jdbc.update("UPDATE campaigns SET attempts = ? WHERE id = ?", attemptsBefore, c.getId());

        sendUnit.markFailed(c, "send_failed");

        String body = (String) notificationsOf(creator).get(0).get("body");
        assertThat(body).isEqualTo(retryable
                ? "Sending stopped on an error. You can retry it."
                : "Sending stopped on an error. It can't be retried — duplicate it to try again.");
    }

    @Test
    void aCampaignCanceledBeforeTheFailureLandsIsNotReported() {
        Organization org = awakeOrg(true);
        User creator = owner(org, "en");
        Campaign c = campaign(org, creator.getId(), "manual");
        jdbc.update("UPDATE campaigns SET status = 'canceled' WHERE id = ?", c.getId());

        sendUnit.markFailed(c, "send_failed");

        assertThat(campaigns.findById(c.getId()).orElseThrow().getStatus()).isEqualTo("canceled");
        assertThat(notificationsOf(creator)).isEmpty();
    }

    // ---- fixtures ----

    private Organization awakeOrg(boolean legalIdentity) {
        Organization o = fx.org();
        o.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        if (legalIdentity) {
            o.setLegalName("Notifier SAS");
            o.setLegalContact(fx.email("legal"));
        }
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private User owner(Organization org, String locale) {
        User u = fx.owner(org);
        u.setLocale(locale);
        u = users.save(u);
        userIds.add(u.getId());
        return u;
    }

    private Campaign campaign(Organization org, UUID createdBy, String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("Outcome " + UUID.randomUUID().toString().substring(0, 6));
        c.setStatus("scheduled");
        c.setOrigin(origin);
        c.setCreatedBy(createdBy);
        c.setScheduledAt(Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private void pendingRows(Campaign c, int n) {
        for (int i = 0; i < n; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setEmail(fx.email("outcome"));
            r.setStatus("pending");
            recipients.save(r);
        }
    }

    private List<Map<String, Object>> notificationsOf(User u) {
        return jdbc.queryForList("SELECT kind, title, body, link FROM notifications WHERE user_id = ?", u.getId());
    }
}
