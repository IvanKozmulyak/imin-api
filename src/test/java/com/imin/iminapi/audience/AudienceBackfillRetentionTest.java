package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.AudienceBackfillJob;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Orders are kept for accounting; the backfill must not rebuild what the 3-year purge removed from them. */
@IminIntegrationTest
class AudienceBackfillRetentionTest {

    /** Past the 1095-day window in any org zone. */
    static final Duration FOUR_YEARS = Duration.ofDays(4 * 365);

    @Autowired IminFixtures fx;
    @Autowired OrderRepository orderRepo;
    @Autowired AudienceBackfillJob backfillJob;
    @Autowired JdbcTemplate jdbc;

    UUID orgId;
    Event event;
    final List<String> emails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Organization o = fx.org();
        orgId = o.getId();
        event = fx.event(o, fx.owner(o), EventStatus.LIVE, Instant.now().plus(Duration.ofDays(30)));
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from memberships where org_id = ?", orgId);
            for (String email : emails) jdbc.update("delete from consumers where normalized_email = ?", email);
            jdbc.update("update shedlock set lock_until = locked_at where name = 'audience_backfill'");
        } finally {
            OrgRows.delete(jdbc, List.of(orgId));
        }
    }

    @Test
    void addressWhoseNewestOrderIsPastRetention_isNotRebuilt_aRecentOneIs() {
        String archived = email("archived");
        order(archived, Instant.now().minus(FOUR_YEARS), false);
        String recent = email("recent");
        order(recent, Instant.now(), false);

        runBackfill();

        assertThat(memberships(archived)).isZero();
        assertThat(consumers(archived)).isZero();
        assertThat(memberships(recent)).isEqualTo(1);
    }

    @Test
    void archivedCheckoutTick_isNotRestored_whenANewOrderBringsTheMemberBack() {
        String email = email("returning");
        UUID oldOrder = order(email, Instant.now().minus(FOUR_YEARS), true);
        order(email, Instant.now(), false);

        runBackfill();

        assertThat(memberships(email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select m.consent_status from memberships m join consumers c"
                + " on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                String.class, orgId, email)).isEqualTo("never");
        assertThat(jdbc.queryForObject("select count(*) from consent_records where order_id = ? and source = 'checkout'",
                Integer.class, oldOrder)).isZero();
    }

    private void runBackfill() {
        jdbc.update("update shedlock set lock_until = locked_at where name = 'audience_backfill'");
        backfillJob.run();
    }

    private String email(String tag) {
        String e = fx.email(tag);
        emails.add(e);
        return e;
    }

    private UUID order(String email, Instant createdAt, boolean ticked) {
        Order o = fx.order(event, email);
        if (ticked) {
            o.setMarketingOptIn(true);
            o.setMarketingOptInProof("Email me about events by this organizer.");
            o = orderRepo.save(o);
        }
        jdbc.update("update orders set created_at = ? where id = ?", Timestamp.from(createdAt), o.getId());
        return o.getId();
    }

    private int memberships(String email) {
        return jdbc.queryForObject("select count(*) from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", Integer.class, orgId, email);
    }

    private int consumers(String email) {
        return jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class, email);
    }
}
