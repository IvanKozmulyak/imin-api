package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The unsubscribe link in a reminder stops this organizer's marketing to this address, and only that. */
@IminIntegrationTest
class CheckoutReminderUnsubscribeTest {

    private static final String URL = "/api/v1/public/unsubscribe/";

    @Autowired MockMvc mvc;
    @Autowired UnsubscribeTokenService tokens;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private ReminderRows rows;
    private ReminderRows.Org org;
    private ReminderRows.Org otherOrg;
    private String email;
    private UUID reminderId;
    private Membership member;
    private Membership otherMember;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(now);
        otherOrg = rows.org(now);
        Event night = rows.night(org, now.plus(Duration.ofDays(5)));
        TicketTier tier = rows.tier(night, 1600, 50);
        email = rows.email("unsub");
        reminderId = rows.waiting(org, night, tier, email, 2, "en", false, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1)));
        rows.status(reminderId, "SENT", null, now.minus(Duration.ofHours(1)));
        member = rows.member(org, email, "subscribed");
        otherMember = rows.member(otherOrg, email, "subscribed");
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"inTheOrganizersAudience", "notInTheAudience"})
    void oneClickFromAReminderStopsThisOrganizersMarketing(String variant) throws Exception {
        boolean inAudience = variant.equals("inTheOrganizersAudience");
        if (!inAudience) jdbc.update("DELETE FROM memberships WHERE membership_id = ?", member.getMembershipId());

        mvc.perform(post(URL + tokens.signReminder(reminderId)))
                .andExpect(status().isOk())
                .andExpect(content().string("unsubscribed"));

        assertThat(optOuts(org.id())).isEqualTo(1);
        if (inAudience) assertThat(consentStatus(member)).isEqualTo("unsubscribed");
        assertThat(rows.load(reminderId).getUnsubscribedAt()).isNotNull();
        // Another organizer the same person follows is untouched.
        assertThat(optOuts(otherOrg.id())).isZero();
        assertThat(consentStatus(otherMember)).isEqualTo("subscribed");
    }

    @Test
    void anOptOutThatCouldNotBeRecordedAnswers500SoTheClientRetries() throws Exception {
        // No membership: the sticky row is the only record of this unsubscribe.
        jdbc.update("DELETE FROM memberships WHERE membership_id = ?", member.getMembershipId());
        String token = tokens.signReminder(reminderId);

        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "marketing_optouts", "org_id", org.id())) {
            mvc.perform(post(URL + token)).andExpect(status().isInternalServerError());
        }

        assertThat(optOuts(org.id())).isZero();
        assertThat(rows.load(reminderId).getUnsubscribedAt()).isNull();

        mvc.perform(post(URL + token))
                .andExpect(status().isOk())
                .andExpect(content().string("unsubscribed"));
        assertThat(optOuts(org.id())).isEqualTo(1);
        assertThat(rows.load(reminderId).getUnsubscribedAt()).isNotNull();
    }

    @Test
    void theLinkRedirectsToTheBuyerPageAndWritesNothing() throws Exception {
        mvc.perform(get(URL + tokens.signReminder(reminderId)))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("/unsubscribe/")));

        assertThat(optOuts(org.id())).isZero();
        assertThat(consentStatus(member)).isEqualTo("subscribed");
        assertThat(rows.load(reminderId).getUnsubscribedAt()).isNull();
    }

    @Test
    void theBuyerPageReadsAReminderLinkAndItsStateAfterTheOptOut() throws Exception {
        String token = tokens.signReminder(reminderId);
        mvc.perform(get(URL + token + "/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("subscribed"))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.organizerName").isNotEmpty())
                .andExpect(jsonPath("$.locale").value("en"));

        mvc.perform(post(URL + token)).andExpect(status().isOk());

        mvc.perform(get(URL + token + "/details"))
                .andExpect(jsonPath("$.state").value("unsubscribed"));
    }

    @Test
    void campaignAndReminderTokensAreNotInterchangeable() {
        String reminderToken = tokens.signReminder(reminderId);
        String campaignToken = tokens.sign(org.id(), member.getMembershipId(), UUID.randomUUID(), "email");

        assertThat(tokens.verifyReminder(reminderToken)).contains(reminderId);
        assertThat(tokens.verify(reminderToken)).isEmpty();
        assertThat(tokens.verifyNotify(reminderToken)).isEmpty();
        assertThat(tokens.verifyReminder(campaignToken)).isEmpty();
        assertThat(tokens.verifyReminder(tokens.signNotify(reminderId))).isEmpty();
    }

    @Test
    void aWipedReminderAnswersWithNothingToRecord() throws Exception {
        reminders.wipeForOrg(org.id(), email, Instant.now());

        mvc.perform(post(URL + tokens.signReminder(reminderId)))
                .andExpect(status().isOk())
                .andExpect(content().string("unsubscribed"));

        assertThat(optOuts(org.id())).isZero();
        assertThat(consentStatus(member)).isEqualTo("subscribed");
    }

    private int optOuts(UUID orgId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM marketing_optouts WHERE email_normalized = ? AND org_id = ?"
                + " AND channel = 'email'", Integer.class, email, orgId);
        return n == null ? 0 : n;
    }

    private String consentStatus(Membership m) {
        return jdbc.queryForObject("SELECT consent_status FROM memberships WHERE membership_id = ?", String.class,
                m.getMembershipId());
    }
}
