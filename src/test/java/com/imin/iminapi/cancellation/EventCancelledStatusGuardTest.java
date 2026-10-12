package com.imin.iminapi.cancellation;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier, promo and media writers save the whole event without its lock, so they can carry a LIVE read before
 * the cancel. Postgres keeps the cancel; both orderings.
 */
@IminIntegrationTest
class EventCancelledStatusGuardTest {

    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventRepository events;
    @Autowired EventCancellationService cancellations;
    @Autowired MutableClock clock;

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal principal;
    private Event event;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(10)));
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void staleFullEntitySave_afterCancel_keepsCancelled() {
        Event stale = events.findById(event.getId()).orElseThrow();
        assertThat(stale.getStatus()).isEqualTo(EventStatus.LIVE);

        cancellations.cancel(principal.orgId(), event.getId(), principal.userId(), null);
        stale.setUpdatedAt(clock.instant());
        events.save(stale);

        assertThat(status()).isEqualTo("CANCELLED");
    }

    @Test
    void saveThenCancel_cancels() {
        Event stale = events.findById(event.getId()).orElseThrow();
        stale.setUpdatedAt(clock.instant());
        events.save(stale);

        cancellations.cancel(principal.orgId(), event.getId(), principal.userId(), null);

        assertThat(status()).isEqualTo("CANCELLED");
    }

    /** An app cancel is terminal for every writer, PAST included; a hand-set CANCELLED keeps the payout escape. */
    @Test
    void appCancelledEvent_cannotBeMovedToPast_aHandCancelledOneCan() {
        cancellations.cancel(principal.orgId(), event.getId(), principal.userId(), null);
        jdbc.update("UPDATE events SET status = 'PAST' WHERE id = ?", event.getId());
        assertThat(status()).isEqualTo("CANCELLED");

        Organization other = fx.org();
        orgIds.add(other.getId());
        Event byHand = fx.event(other, fx.owner(other), EventStatus.CANCELLED, clock.instant().plus(Duration.ofDays(10)));
        jdbc.update("UPDATE events SET status = 'PAST' WHERE id = ?", byHand.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, byHand.getId()))
                .isEqualTo("PAST");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, event.getId());
    }
}
