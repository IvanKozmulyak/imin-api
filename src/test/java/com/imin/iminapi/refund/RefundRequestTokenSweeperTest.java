package com.imin.iminapi.refund;

import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The daily purge, through the bean: the bulk delete needs the bean's transaction to run at all. */
@IminIntegrationTest
class RefundRequestTokenSweeperTest {

    @Autowired RefundRequestTokenSweeper sweeper;
    @Autowired RefundRequestTokenRepository tokens;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void sweep_deletesTokensExpiredBeyondTheSevenDayRetention_andKeepsTheRest() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        Order order = fx.order(fx.event(org, owner, EventStatus.LIVE, null), fx.email("buyer"));
        // The sweeper reads Instant.now(), not the injected clock, so the fixtures are dated by it too.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // One minute either side of the 7-day retention cutoff.
        RefundRequestToken stale = token(order, now.minus(Duration.ofDays(7)).minus(Duration.ofMinutes(1)));
        RefundRequestToken withinRetention = token(order, now.minus(Duration.ofDays(7)).plus(Duration.ofMinutes(1)));
        // lockAtLeastFor keeps the lock after a run; expire it so this tick is not skipped.
        jdbc.update("UPDATE shedlock SET lock_until = ? WHERE name = ?",
                Timestamp.from(now.minus(Duration.ofDays(1))), "RefundRequestTokenSweeper.sweep");

        sweeper.sweep();

        assertThat(tokens.findById(stale.getId())).as("expired 7 days and a minute ago, past retention").isEmpty();
        assertThat(tokens.findById(withinRetention.getId())).as("expired a minute short of 7 days ago, inside retention").isPresent();
    }

    private RefundRequestToken token(Order order, Instant expiresAt) {
        RefundRequestToken t = new RefundRequestToken();
        t.setTokenHash(RefundRequestService.sha256Hex("rt-" + UUID.randomUUID()));
        t.setOrderId(order.getId());
        t.setEmailNormalized(order.getEmail());
        t.setExpiresAt(expiresAt);
        return tokens.save(t);
    }
}
