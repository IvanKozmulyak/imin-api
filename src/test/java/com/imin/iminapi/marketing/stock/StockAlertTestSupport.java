package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertSettings;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.marketing.repository.StockAlertSettingsRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test-only seeding for the stock-alert integration tests; a bean only in the test context. */
@Component
class StockAlertTestSupport {

    /** A checkout consent text version ConsentGate counts as proven. */
    static final String PROVEN = "checkout-org-named-2026-09";

    private final IminFixtures fx;
    private final OrganizationRepository orgs;
    private final TicketTierRepository tiers;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final ConsentRecordRepository consents;
    private final StockAlertRepository alerts;
    private final StockAlertSettingsRepository settings;
    private final StockFactsReader reader;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    StockAlertTestSupport(IminFixtures fx, OrganizationRepository orgs, TicketTierRepository tiers,
                          ConsumerRepository consumers, MembershipRepository memberships,
                          ConsentRecordRepository consents, StockAlertRepository alerts,
                          StockAlertSettingsRepository settings, StockFactsReader reader, JdbcTemplate jdbc,
                          Clock clock) {
        this.fx = fx;
        this.orgs = orgs;
        this.tiers = tiers;
        this.consumers = consumers;
        this.memberships = memberships;
        this.consents = consents;
        this.alerts = alerts;
        this.settings = settings;
        this.reader = reader;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record Night(Organization org, User owner, Event event) {}

    /** An org whose clock reads about noon now (so quiet hours never hold its campaigns), its owner and a live night. */
    Night awakeNight() {
        int hourNowUtc = Instant.now().atZone(ZoneOffset.UTC).getHour();
        return night(ZoneOffset.ofHours(12 - hourNowUtc).getId());
    }

    /** An org in {@code timezone} with its owner and a live, public night ten days after the clock's now. */
    Night night(String timezone) {
        Organization o = fx.org();
        o.setTimezone(timezone);
        o = orgs.save(o);
        User owner = fx.owner(o);
        Event e = fx.event(o, owner, EventStatus.LIVE, clock.instant().plus(10, ChronoUnit.DAYS));
        return new Night(o, owner, e);
    }

    TicketTier tier(Event e, String name, int priceMinor, int quantity, int sold, Instant closesAt, int sortOrder) {
        TicketTier t = fx.tier(e, priceMinor, quantity);
        t.setName(name);
        t.setSold(sold);
        t.setSaleClosesAt(closesAt);
        t.setSortOrder(sortOrder);
        return tiers.save(t);
    }

    /** {@code n} members ConsentGate and the Send Gate both let the org email. */
    List<Membership> mailable(UUID orgId, int n) {
        List<Membership> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Consumer cn = new Consumer();
            cn.setNormalizedEmail(fx.email("fan"));
            cn = consumers.save(cn);
            Membership m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(cn.getConsumerId());
            m.setConsentStatus("subscribed");
            m.setConsentBasis("explicit");
            m = memberships.save(m);
            ConsentRecord r = new ConsentRecord();
            r.setMembershipId(m.getMembershipId());
            r.setStatus("subscribed");
            r.setLawfulBasis("explicit");
            r.setSource("checkout");
            r.setProofText("proof");
            r.setTextVersion(PROVEN);
            r.setOccurredAt(Instant.now().minus(10, ChronoUnit.DAYS));
            consents.save(r);
            out.add(m);
        }
        return out;
    }

    StockAlertSettings settings(Event e, boolean lastTickets, boolean priceUp, String mode, UUID autoEnabledBy) {
        StockAlertSettings s = new StockAlertSettings();
        s.setEventId(e.getId());
        s.setOrgId(e.getOrgId());
        s.setLastTicketsOn(lastTickets);
        s.setPriceUpOn(priceUp);
        s.setPriceUpLeadHours(24);
        s.setMode(mode);
        s.setAutoEnabledBy(autoEnabledBy);
        s.setAutoEnabledAt(autoEnabledBy == null ? null : Instant.now());
        s.setUpdatedAt(Instant.now());
        return settings.save(s);
    }

    /** A ready alert with the facts read now, as the evaluator stores it. */
    StockAlert readyAlert(Event e, TicketTier t, StockAlertKind kind, int threshold) {
        StockFacts.Snapshot snap = reader.snapshot(e, clock.instant());
        StockFacts facts = StockFacts.of(snap, kind, t.getId(), threshold);
        UUID id = UUID.randomUUID();
        alerts.insertIfAbsent(id, e.getOrgId(), e.getId(), t.getId(), kind.wire(), StockFactsReader.json(facts), 1, "en",
                clock.instant());
        return alerts.findById(id).orElseThrow();
    }

    void setSold(TicketTier t, int sold) {
        jdbc.update("UPDATE ticket_tiers SET sold = ? WHERE id = ?", sold, t.getId());
    }

    /** Due long ago, so the dispatcher's claim takes it before any other test's leftovers. */
    void makeDue(UUID campaignId) {
        jdbc.update("UPDATE campaigns SET scheduled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(3650, ChronoUnit.DAYS)), campaignId);
    }

    void expireEvaluatorLock() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'stock_alert_evaluator'");
    }

    long notifications(UUID userId, String kind) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ?", Long.class,
                userId, kind);
        return n == null ? 0 : n;
    }
}
