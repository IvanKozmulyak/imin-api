package com.imin.iminapi.service;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Suggested prices from other organizers' public listings: only events that match on every predicate count. */
@IminIntegrationTest
class PricingServiceTest {

    @Autowired PricingService pricing;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired MutableClock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Organization caller;
    private String genre;

    @BeforeEach
    void setUp() {
        caller = fx.org();
        caller.setCountry("FR");
        caller.setCurrency("EUR");
        caller = orgs.save(caller);
        genre = "G-" + UUID.randomUUID();
    }

    @Test
    void three_comparable_events_give_the_median_listed_prices() {
        comparable(1000, 2000);
        comparable(1500, 2500);
        comparable(2000, 4000);

        PricingRecommendation r = pricing.recommend(caller.getId(), " " + genre + " ");

        assertThat(r.suggestedMinPrice()).isEqualTo(new BigDecimal("15.00"));
        assertThat(r.suggestedMaxPrice()).isEqualTo(new BigDecimal("25.00"));
        assertThat(r.basis().source()).isEqualTo("comparables");
        assertThat(r.basis().comparableEvents()).isEqualTo(3);
        assertThat(r.basis().genre()).isEqualTo(genre);
        assertThat(r.basis().country()).isEqualTo("FR");
    }

    enum Excluded {
        PRIVATE, DRAFT, CANCELLED, UNPUBLISHED, DELETED, OTHER_COUNTRY, OTHER_CURRENCY, ONLY_TEST_ORDERS,
        TOO_OLD, ONLY_FREE_TIERS, ONLY_DISABLED_TIERS, OTHER_GENRE
    }

    /** The excluded event is created first, so a missing predicate would make it the third comparable. */
    @ParameterizedTest
    @EnumSource(Excluded.class)
    void an_event_failing_one_predicate_is_not_a_comparable(Excluded kind) {
        excluded(kind);
        comparable(1000, 2000);
        comparable(1500, 2500);

        PricingRecommendation r = pricing.recommend(caller.getId(), genre);

        assertThat(r.basis().source()).isEqualTo("default");
        assertThat(r.basis().comparableEvents()).isNull();
    }

    /** An org with no country has no market to compare with, even if events with a blank country exist. */
    @Test
    void an_org_without_a_country_gets_the_default() {
        caller.setCountry("");
        caller = orgs.save(caller);
        for (int[] p : new int[][]{{1000, 2000}, {1500, 2500}, {2000, 4000}}) {
            Event e = comparable(p[0], p[1]);
            e.setVenueCountry("");
            events.save(e);
        }

        PricingRecommendation r = pricing.recommend(caller.getId(), genre);

        assertThat(r.basis().source()).isEqualTo("default");
        assertThat(r.basis().country()).isNull();
    }

    @Test
    void a_blank_genre_uses_the_base_default_without_assuming_one() {
        comparable(1000, 2000);
        comparable(1500, 2500);
        comparable(2000, 4000);

        PricingRecommendation r = pricing.recommend(caller.getId(), "  ");

        assertThat(r.suggestedMinPrice()).isEqualByComparingTo("15");
        assertThat(r.suggestedMaxPrice()).isEqualByComparingTo("30");
        assertThat(r.basis().source()).isEqualTo("default");
        assertThat(r.basis().comparableEvents()).isNull();
        assertThat(r.basis().genre()).isNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** A public LIVE event of another org in FR/EUR, started 10 days ago, with tiers at min and max. */
    private Event comparable(int minPrice, int maxPrice) {
        Event e = otherOrgEvent(genre, clock.instant().minus(10, ChronoUnit.DAYS));
        fx.tier(e, minPrice, 100);
        fx.tier(e, maxPrice, 100);
        return e;
    }

    private void excluded(Excluded kind) {
        Instant starts = kind == Excluded.TOO_OLD
                ? clock.instant().minus(400, ChronoUnit.DAYS)
                : clock.instant().minus(10, ChronoUnit.DAYS);
        Event e = otherOrgEvent(kind == Excluded.OTHER_GENRE ? genre + "-x" : genre, starts);
        switch (kind) {
            case PRIVATE -> e.setVisibility(EventVisibility.PRIVATE);
            case DRAFT -> e.setStatus(EventStatus.DRAFT);
            case CANCELLED -> e.setStatus(EventStatus.CANCELLED);
            case UNPUBLISHED -> e.setPublishedAt(null);
            case OTHER_COUNTRY -> e.setVenueCountry("DE");
            case OTHER_CURRENCY -> e.setCurrency("USD");
            default -> { }
        }
        e = events.save(e);
        if (kind == Excluded.DELETED) {
            // deleted_at is insert-only on the entity; soft deletes are bulk writes.
            jdbc.update("update events set deleted_at = now() where id = ?", e.getId());
        }
        int price = kind == Excluded.ONLY_FREE_TIERS ? 0 : 1000;
        TicketTier a = fx.tier(e, price, 100);
        TicketTier b = fx.tier(e, kind == Excluded.ONLY_FREE_TIERS ? 0 : 2000, 100);
        if (kind == Excluded.ONLY_DISABLED_TIERS) {
            a.setEnabled(false);
            b.setEnabled(false);
            tiers.save(a);
            tiers.save(b);
        }
        if (kind == Excluded.ONLY_TEST_ORDERS) {
            // Test mode is set before the insert, as checkout stamps it.
            Order o = new Order();
            o.setToken("ORD_" + UUID.randomUUID());
            o.setEventId(e.getId());
            o.setOrgId(e.getOrgId());
            o.setEmail(fx.email("buyer"));
            o.setTotalMinor(1000L);
            o.setCurrency("EUR");
            o.setPaymentMethod("stripe");
            o.setTestMode(true);
            orders.save(o);
        }
    }

    private Event otherOrgEvent(String g, Instant startsAt) {
        Organization other = fx.org();
        Event e = fx.event(other, fx.owner(other), EventStatus.LIVE, startsAt);
        e.setGenre(g);
        e.setVenueCountry("FR");
        e.setPublishedAt(startsAt.minus(30, ChronoUnit.DAYS));
        return events.save(e);
    }
}
