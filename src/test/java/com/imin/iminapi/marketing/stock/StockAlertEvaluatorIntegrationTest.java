package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;
import com.imin.iminapi.marketing.model.StockAlertSettings;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.model.ModerationHold;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The evaluator pass through the scheduled bean: what it stores, expires, skips and schedules. */
@IminIntegrationTest
class StockAlertEvaluatorIntegrationTest {

    @Autowired StockAlertEvaluator evaluator;
    @Autowired StockAlertTestSupport support;
    @Autowired StockAlertProperties props;
    @Autowired StockAlertRepository alerts;
    @Autowired CampaignRepository campaigns;
    @Autowired ModerationHoldRepository holds;
    @Autowired PropertyFlips flips;
    @Autowired MutableClock clock;
    @Autowired AuditRows audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired AudiencePlanProperties planProps;

    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void enable() {
        flips.set(props, "enabled", true);
    }

    @AfterEach
    void deleteOwnRows() {
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    /** One pass through the bean; the lock row is expired first so an earlier pass cannot make it a no-op. */
    private void pass() {
        support.expireEvaluatorLock();
        evaluator.run();
    }

    private StockAlertTestSupport.Night night() {
        StockAlertTestSupport.Night n = support.awakeNight();
        orgIds.add(n.org().getId());
        return n;
    }

    private List<StockAlert> alertsOf(StockAlertTestSupport.Night n) {
        return alerts.findByEventIdOrderByCreatedAtDesc(n.event().getId());
    }

    @Test
    void onePerTierKindAndOnlyWhenReachable() {
        StockAlertTestSupport.Night n = night();
        // 15 of 100 left: within the auto threshold of 20.
        TicketTier tier = support.tier(n.event(), "GA", 2000, 100, 85, null, 0);
        support.settings(n.event(), true, false, StockAlertSettings.MODE_ASK, null);

        pass();
        assertThat(alertsOf(n)).as("nobody can get it yet, so nothing is stored").isEmpty();

        support.mailable(n.org().getId(), 2);
        pass();
        pass();

        List<StockAlert> stored = alertsOf(n);
        assertThat(stored).hasSize(1);
        StockAlert a = stored.get(0);
        assertThat(a.getTierId()).isEqualTo(tier.getId());
        assertThat(a.getKind()).isEqualTo(StockAlertKind.LAST_TICKETS.wire());
        assertThat(a.getStatus()).isEqualTo(StockAlert.READY);
        assertThat(a.getReachAtPrepare()).isEqualTo(2);
        assertThat(StockFactsReader.parse(a.getFactsJson()).orElseThrow().remaining()).isEqualTo(15);
        assertThat(support.notifications(n.owner().getId(), StockAlertNotifier.KIND_READY)).isEqualTo(1);

        support.setSold(tier, 100);
        pass();
        StockAlert expired = alerts.findById(a.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(StockAlert.EXPIRED);
        assertThat(expired.getExpiredReason()).isEqualTo(StockAlertReason.SOLD_OUT);
    }

    @Test
    void hiddenSuspendedAndSwitchedOffEventsGetNoAlert() {
        StockAlertTestSupport.Night hidden = night();
        StockAlertTestSupport.Night suspended = night();
        StockAlertTestSupport.Night switchedOff = night();
        StockAlertTestSupport.Night control = night();
        for (StockAlertTestSupport.Night n : List.of(hidden, suspended, switchedOff, control)) {
            support.tier(n.event(), "GA", 2000, 100, 85, null, 0);
            support.mailable(n.org().getId(), 1);
            support.settings(n.event(), n != switchedOff, false, StockAlertSettings.MODE_ASK, null);
        }
        jdbc.update("UPDATE events SET visibility = 'PRIVATE' WHERE id = ?", hidden.event().getId());
        ModerationHold h = new ModerationHold();
        h.setId(UUID.randomUUID());
        h.setOrgId(suspended.org().getId());
        h.setEventId(suspended.event().getId());
        h.setSuspendSales(true);
        h.setHoldPayouts(false);
        h.setGround("safety");
        h.setFacts("fixture");
        h.setPlacedBy(UUID.randomUUID());
        h.setPlacedAt(Instant.now());
        holds.saveAndFlush(h);

        pass();

        assertThat(alertsOf(control)).as("the pass ran").hasSize(1);
        assertThat(alertsOf(hidden)).isEmpty();
        assertThat(alertsOf(suspended)).isEmpty();
        assertThat(alertsOf(switchedOff)).isEmpty();
    }

    @Test
    void autoModeSchedulesInsideAllowedHours() {
        // 22:30Z on 6 Nov is 23:30 in Paris: quiet until 09:00 Paris (08:00Z) on 7 Nov.
        clock.setInstant(Instant.parse("2026-11-06T22:30:00Z"));
        StockAlertTestSupport.Night n = support.night("Europe/Paris");
        orgIds.add(n.org().getId());
        // Early bird stops at 10:00 Paris (09:00Z): the 09:00 slot is under 2 h before it, so it waits for a click.
        TicketTier early = support.tier(n.event(), "Early bird", 1000, 100, 10,
                Instant.parse("2026-11-07T09:00:00Z"), 0);
        TicketTier regular = support.tier(n.event(), "Regular", 2000, 100, 85, null, 1);
        support.mailable(n.org().getId(), 2);
        support.settings(n.event(), true, true, StockAlertSettings.MODE_AUTO, n.owner().getId());

        pass();

        StockAlert lastTickets = alertsOf(n).stream().filter(a -> a.getTierId().equals(regular.getId())).findFirst()
                .orElseThrow();
        assertThat(lastTickets.getStatus()).isEqualTo(StockAlert.DRAFTED);
        Campaign c = campaigns.findByIdAndOrgId(lastTickets.getCampaignId(), n.org().getId()).orElseThrow();
        assertThat(c.getStatus()).isEqualTo("scheduled");
        assertThat(c.getScheduledAt()).isEqualTo(Instant.parse("2026-11-07T08:00:00Z"));
        assertThat(c.getScheduledAt()).isAfterOrEqualTo(clock.instant().plus(30, ChronoUnit.MINUTES));
        assertThat(support.notifications(n.owner().getId(), StockAlertNotifier.KIND_SCHEDULED)).isEqualTo(1);
        assertThat(audit.assertRecorded(n.org().getId(), AuditActions.STOCK_ALERT_AUTO_SCHEDULED, "campaign", c.getId())
                .getActorId()).isEqualTo(n.owner().getId());

        StockAlert priceUp = alertsOf(n).stream().filter(a -> a.getTierId().equals(early.getId())).findFirst()
                .orElseThrow();
        assertThat(priceUp.getKind()).isEqualTo(StockAlertKind.PRICE_UP.wire());
        assertThat(priceUp.getStatus()).isEqualTo(StockAlert.READY);
        assertThat(priceUp.getCampaignId()).isNull();
        assertThat(support.notifications(n.owner().getId(), StockAlertNotifier.KIND_READY)).isEqualTo(1);
    }

    private StockAlertTestSupport.Night autoNight() {
        StockAlertTestSupport.Night n = night();
        support.tier(n.event(), "GA", 2000, 100, 85, null, 0);
        support.mailable(n.org().getId(), 2);
        support.settings(n.event(), true, false, StockAlertSettings.MODE_AUTO, n.owner().getId());
        return n;
    }

    /** The owner's bell entries as [kind, link]. */
    private List<List<String>> bellOf(StockAlertTestSupport.Night n) {
        return jdbc.query("SELECT kind, link FROM notifications WHERE user_id = ?",
                (rs, i) -> List.of(rs.getString(1), rs.getString(2)), n.owner().getId());
    }

    @Test
    void autoMode_aDraftThatCannotBeMade_leavesTheAlertReadyAndAsksTheOwner() {
        StockAlertTestSupport.Night n = autoNight();

        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "campaigns", "org_id", n.org().getId())) {
            pass();
        }

        StockAlert a = alertsOf(n).get(0);
        assertThat(a.getStatus()).isEqualTo(StockAlert.READY);
        assertThat(a.getCampaignId()).isNull();
        assertThat(bellOf(n)).containsExactly(
                List.of(StockAlertNotifier.KIND_READY, "/events/" + n.event().getId() + "/promote#stock-alerts"));
    }

    @Test
    void autoMode_aDraftThatCannotBeScheduled_pointsTheOwnerAtTheDraft() {
        // Without a legal name and contact on the org, scheduling refuses after the draft was made.
        flips.set(planProps, "legalIdentityAllCampaigns", true);
        StockAlertTestSupport.Night n = autoNight();

        pass();

        StockAlert a = alertsOf(n).get(0);
        assertThat(a.getStatus()).isEqualTo(StockAlert.DRAFTED);
        assertThat(campaigns.findByIdAndOrgId(a.getCampaignId(), n.org().getId()).orElseThrow().getStatus())
                .isEqualTo("draft");
        assertThat(bellOf(n)).containsExactly(
                List.of(StockAlertNotifier.KIND_READY, "/marketing/campaigns/" + a.getCampaignId() + "/edit?step=review"));
        assertThat(support.notifications(n.owner().getId(), StockAlertNotifier.KIND_SCHEDULED)).isZero();
    }
}
