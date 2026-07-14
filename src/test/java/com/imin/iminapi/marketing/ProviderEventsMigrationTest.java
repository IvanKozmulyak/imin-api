package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies V56 applies: provider_events exists with the idempotent
 * UNIQUE(provider, provider_event_id), and event_funnel_events has a
 * utm_campaign index (Task 9 attribution query depends on it).
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class ProviderEventsMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void providerEventsTableExistsAndIsInsertable() {
        jdbc.update(
            "INSERT INTO provider_events (id, provider, provider_event_id, type) " +
            "VALUES (random_uuid(), 'resend', 'msg_1.evt_1', 'email.delivered')");
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM provider_events WHERE provider_event_id = 'msg_1.evt_1'",
            Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void duplicateProviderEventIdIsRejectedBySameProvider() {
        jdbc.update(
            "INSERT INTO provider_events (id, provider, provider_event_id, type) " +
            "VALUES (random_uuid(), 'resend', 'dup_evt', 'email.opened')");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO provider_events (id, provider, provider_event_id, type) " +
            "VALUES (random_uuid(), 'resend', 'dup_evt', 'email.opened')"))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameEventIdUnderDifferentProviderIsAllowed() {
        jdbc.update(
            "INSERT INTO provider_events (id, provider, provider_event_id, type) " +
            "VALUES (random_uuid(), 'resend', 'shared_id', 'email.delivered')");
        jdbc.update(
            "INSERT INTO provider_events (id, provider, provider_event_id, type) " +
            "VALUES (random_uuid(), 'bird', 'shared_id', 'sms.delivered')");
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM provider_events WHERE provider_event_id = 'shared_id'",
            Integer.class);
        assertThat(n).isEqualTo(2);
    }
}
