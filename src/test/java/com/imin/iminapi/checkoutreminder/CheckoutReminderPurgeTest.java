package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderPurgeJob;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The privacy policy's deletion promise for reminder rows is kept, and the counts survive it. */
@IminIntegrationTest
class CheckoutReminderPurgeTest {

    private static final Instant NOW = Instant.parse("2026-11-05T02:45:00Z");

    @Autowired CheckoutReminderPurgeJob purge;
    @Autowired MutableClock clock;
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

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(NOW);
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"notSentAfterTheNight", "waitingAfterTheNight", "notSentBeforeTheNight",
            "notSentKept181Days", "sent366DaysAgo", "sent300DaysAgo", "sendUnknown366DaysAgo", "sendUnknown200DaysAgo",
            "captured14MonthsAgo"})
    void addressesAreWipedOnThePromisedSchedule(String variant) {
        Instant nightStart = switch (variant) {
            case "notSentAfterTheNight", "waitingAfterTheNight" -> NOW.minus(Duration.ofHours(30));
            default -> NOW.plus(Duration.ofDays(300));
        };
        Instant started = switch (variant) {
            case "notSentKept181Days" -> NOW.minus(Duration.ofDays(181));
            case "sent366DaysAgo", "sendUnknown366DaysAgo" -> NOW.minus(Duration.ofDays(366));
            case "sent300DaysAgo" -> NOW.minus(Duration.ofDays(300));
            case "sendUnknown200DaysAgo" -> NOW.minus(Duration.ofDays(200));
            case "captured14MonthsAgo" -> NOW.minus(Duration.ofDays(426));
            default -> NOW.minus(Duration.ofDays(2));
        };
        Event night = rows.night(org, nightStart);
        TicketTier tier = rows.tier(night, 1600, 50);
        UUID id = rows.waiting(org, night, tier, rows.email("purge"), 1, "en", false, started, started.plusSeconds(3600));
        switch (variant) {
            case "sent366DaysAgo", "sent300DaysAgo" -> rows.status(id, "SENT", null, started.plusSeconds(3600));
            case "sendUnknown366DaysAgo", "sendUnknown200DaysAgo" -> rows.status(id, "NOT_SENT", "SEND_UNKNOWN", null);
            case "waitingAfterTheNight" -> { }
            default -> rows.status(id, "NOT_SENT", "SOLD_OUT", null);
        }

        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'checkout_reminder_purge'");
        purge.run();

        if (variant.equals("captured14MonthsAgo")) {
            assertThat(reminders.findById(id)).isEmpty();
            return;
        }
        CheckoutReminder after = rows.load(id);
        boolean wiped = switch (variant) {
            case "notSentAfterTheNight", "waitingAfterTheNight", "notSentKept181Days", "sent366DaysAgo",
                 "sendUnknown366DaysAgo" -> true;
            default -> false;
        };
        assertThat(after.getEmailNormalized() == null).as("address wiped").isEqualTo(wiped);
        assertThat(after.getConsentProof() == null).as("ticked sentence wiped").isEqualTo(wiped);
        assertThat(after.getAddressWipedAt() == null).isEqualTo(!wiped);
        // A reminder that may have reached the buyer keeps its proof a year, even past the unsent cap.
        // The count keeps its status; a reminder still waiting after its night is dropped as too late.
        switch (variant) {
            case "sent366DaysAgo", "sent300DaysAgo" -> assertThat(after.getStatus().name()).isEqualTo("SENT");
            case "sendUnknown366DaysAgo", "sendUnknown200DaysAgo" ->
                    assertThat(after.getReason().name()).isEqualTo("SEND_UNKNOWN");
            case "waitingAfterTheNight" -> {
                assertThat(after.getStatus().name()).isEqualTo("NOT_SENT");
                assertThat(after.getReason().name()).isEqualTo("TOO_LATE");
            }
            default -> assertThat(after.getReason().name()).isEqualTo("SOLD_OUT");
        }
    }
}
