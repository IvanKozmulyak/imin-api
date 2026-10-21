package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Reminder-test rows: an org that may send, a night on sale, a waiting reminder; deletes its own orgs after. */
final class ReminderRows {

    static final String LABEL = "Email me about events by %s. I agree to receive email marketing and can "
            + "unsubscribe any time, one click in every email.";
    static final String VERSION = "checkout-org-named-2026-09";

    private final IminFixtures fx;
    private final OrganizationRepository orgs;
    private final EventRepository events;
    private final CheckoutReminderRepository reminders;
    private final OrgCheckoutReminderSettingRepository settings;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final JdbcTemplate jdbc;
    final List<UUID> orgIds = new ArrayList<>();

    ReminderRows(IminFixtures fx, OrganizationRepository orgs, EventRepository events,
                 CheckoutReminderRepository reminders, OrgCheckoutReminderSettingRepository settings,
                 ConsumerRepository consumers, MembershipRepository memberships, JdbcTemplate jdbc) {
        this.fx = fx;
        this.orgs = orgs;
        this.events = events;
        this.reminders = reminders;
        this.settings = settings;
        this.consumers = consumers;
        this.memberships = memberships;
        this.jdbc = jdbc;
    }

    /** Paris-zoned, with the legal identity the footer needs, reminder switched on by its owner. */
    Org org(Instant now) {
        Organization o = fx.org();
        o.setTimezone("Europe/Paris");
        o.setLegalName("Vechirka SAS " + UUID.randomUUID().toString().substring(0, 4));
        o.setLegalContact("hello@vechirka.example");
        o.setBrandName("Vechirka");
        o = orgs.save(o);
        orgIds.add(o.getId());
        User owner = fx.owner(o);
        settings.enable(o.getId(), owner.getId(), now);
        return new Org(o, owner);
    }

    record Org(Organization org, User owner) {
        UUID id() { return org.getId(); }
    }

    /** LIVE, PUBLIC, published, in Paris. */
    Event night(Org o, Instant startsAt) {
        Event e = fx.event(o.org(), o.owner(), EventStatus.LIVE, startsAt);
        e.setTimezone("Europe/Paris");
        e.setPublishedAt(startsAt.minusSeconds(30L * 86400));
        e.setVenueName("La Halle Noire");
        e.setVenueCity("Paris");
        return events.save(e);
    }

    TicketTier tier(Event e, int priceMinor, int quantity) {
        return fx.tier(e, priceMinor, quantity);
    }

    String email(String tag) {
        return fx.email(tag).toLowerCase();
    }

    /** A waiting reminder for {@code qty} on {@code tier}, due {@code dueAt}. */
    UUID waiting(Org o, Event e, TicketTier t, String email, int qty, String locale, boolean promo,
                 Instant startedAt, Instant dueAt) {
        UUID id = UUID.randomUUID();
        reminders.upsertWaiting(id, o.id(), e.getId(), t.getId(), qty, "cs_test_" + UUID.randomUUID(), email,
                locale, promo, LABEL.formatted(o.org().getName()), VERSION, startedAt, startedAt, dueAt);
        return id;
    }

    void status(UUID id, String status, String reason, Instant sentAt) {
        jdbc.update("UPDATE checkout_reminders SET status = ?, reason = ?, sent_at = ? WHERE id = ?",
                status, reason, sentAt == null ? null : java.sql.Timestamp.from(sentAt), id);
    }

    CheckoutReminder load(UUID id) {
        return reminders.findById(id).orElseThrow();
    }

    /** A member of the org's audience at {@code email}, subscribed unless told otherwise. */
    Membership member(Org o, String email, String consentStatus) {
        Consumer c = consumers.findByNormalizedEmail(email).orElseGet(() -> {
            Consumer n = new Consumer();
            n.setNormalizedEmail(email);
            return consumers.save(n);
        });
        Membership m = new Membership();
        m.setOrgId(o.id());
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("subscribed".equals(consentStatus) ? "explicit" : null);
        return memberships.save(m);
    }

    void deleteOwn() {
        if (orgIds.isEmpty()) return;
        String in = "(" + String.join(",", Collections.nCopies(orgIds.size(), "?")) + ")";
        Object[] ids = orgIds.toArray();
        jdbc.update("DELETE FROM checkout_reminders WHERE org_id IN " + in, ids);
        jdbc.update("DELETE FROM moderation_holds WHERE org_id IN " + in, ids);
        jdbc.update("DELETE FROM suppression_entries WHERE org_id IN " + in, ids);
        jdbc.update("DELETE FROM marketing_optouts WHERE org_id IN " + in, ids);
        jdbc.update("DELETE FROM erased_addresses WHERE org_id IN " + in, ids);
        jdbc.update("DELETE FROM memberships WHERE org_id IN " + in, ids);
        com.imin.iminapi.support.OrgRows.delete(jdbc, orgIds);
    }
}
