package com.imin.iminapi.migration;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V83/V84/V85 exist, and — more importantly — the marker-column trick actually
 * enforces what a partial index would have, on the engine the test suite runs
 * (H2 in PostgreSQL-compat mode, which has no {@code WHERE}-clause indexes).
 * Every assertion here would pass trivially if the indexes were missing, so
 * each one inserts real rows.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class V83BuyerIdentityMigrationTest {

    @Autowired JdbcTemplate jdbc;

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_accounts (id, status) VALUES (?, 'active')", id);
        return id;
    }

    private void insertEmail(UUID accountId, String email, boolean verified, boolean primary) {
        jdbc.update("INSERT INTO buyer_account_emails "
                        + "(id, buyer_account_id, email, email_normalized, verified_at, added_via, "
                        + " verified_key, primary_marker) "
                        + "VALUES (?, ?, ?, ?, ?, 'manual', ?, ?)",
                UUID.randomUUID(), accountId, email, email,
                verified ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                verified ? email : null,
                primary ? accountId : null);
    }

    @Test
    void all_buyer_tables_exist() {
        for (String table : new String[]{"buyer_accounts", "buyer_account_emails", "buyer_identities",
                "buyer_sessions", "buyer_email_verification_codes", "buyer_password_reset_tokens",
                "buyer_verification_attempts", "buyer_saved_events", "buyer_notification_preferences",
                "marketing_optouts"}) {
            assertThatCode(() -> jdbc.queryForList("SELECT * FROM " + table + " WHERE 1=0"))
                    .as(table)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void one_verified_address_per_platform() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        insertEmail(account(), email, true, false);
        assertThatThrownBy(() -> insertEmail(account(), email, true, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unverified_duplicates_are_legal_so_nobody_can_squat_an_address() {
        // The anti-lockout property of §2.3 rule 1: an attacker adding your
        // address and never verifying it must not stop you adding it.
        String email = "squat-" + UUID.randomUUID() + "@example.com";
        insertEmail(account(), email, false, false);
        assertThatCode(() -> insertEmail(account(), email, false, false))
                .doesNotThrowAnyException();
        // …and proving control still works afterwards.
        assertThatCode(() -> insertEmail(account(), email, true, false))
                .doesNotThrowAnyException();
    }

    @Test
    void one_primary_address_per_account() {
        UUID accountId = account();
        insertEmail(accountId, "a-" + UUID.randomUUID() + "@example.com", true, true);
        assertThatThrownBy(() -> insertEmail(accountId, "b-" + UUID.randomUUID() + "@example.com", true, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void an_account_cannot_list_the_same_address_twice() {
        UUID accountId = account();
        String email = "same-" + UUID.randomUUID() + "@example.com";
        insertEmail(accountId, email, false, false);
        assertThatThrownBy(() -> insertEmail(accountId, email, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void session_token_hash_is_unique() {
        UUID accountId = account();
        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO buyer_sessions (id, buyer_account_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), accountId, hash,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO buyer_sessions (id, buyer_account_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), accountId, hash,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleting_an_account_cascades_to_its_sessions_and_addresses() {
        UUID accountId = account();
        insertEmail(accountId, "cascade-" + UUID.randomUUID() + "@example.com", true, true);
        jdbc.update("INSERT INTO buyer_sessions (id, buyer_account_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), accountId, "b".repeat(64),
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));

        jdbc.update("DELETE FROM buyer_accounts WHERE id = ?", accountId);

        assertThatCode(() -> {
            Integer emails = jdbc.queryForObject(
                    "SELECT count(*) FROM buyer_account_emails WHERE buyer_account_id = ?",
                    Integer.class, accountId);
            Integer sessions = jdbc.queryForObject(
                    "SELECT count(*) FROM buyer_sessions WHERE buyer_account_id = ?",
                    Integer.class, accountId);
            org.assertj.core.api.Assertions.assertThat(emails).isZero();
            org.assertj.core.api.Assertions.assertThat(sessions).isZero();
        }).doesNotThrowAnyException();
    }
}
