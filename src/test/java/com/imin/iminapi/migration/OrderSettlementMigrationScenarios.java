package com.imin.iminapi.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V174 on a database seeded at V172: EUR orders settle 1:1, others stay unstamped, ck_orders_settlement holds. */
abstract class OrderSettlementMigrationScenarios {

    /** A new, empty database per call. */
    abstract DataSource freshDatabase();

    private JdbcTemplate jdbc;
    private UUID org;
    private UUID event;
    private UUID eurLower;
    private UUID eurUpper;
    private UUID eurFree;
    private UUID usd;

    @BeforeEach
    void seedAtV172ThenMigrate() {
        DataSource ds = freshDatabase();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("172").load().migrate();
        jdbc = new JdbcTemplate(ds);

        org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        event = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, 'Org', ?, 'o@example.com', 'FR')",
                org, "settle-" + org);
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, 'u@example.com', 'u@example.com', 'OWNER')",
                user, org);
        jdbc.update("insert into events (id, org_id, slug, created_by) values (?, ?, ?, ?)", event, org, "settle-" + event, user);
        eurLower = order("eur", 1_149, 149);
        eurUpper = order("EUR", 2_298, 298);
        eurFree = order("eur", 0, 0);
        usd = order("usd", 1_149, 149);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    private UUID order(String currency, long total, long fee) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into orders (id, token, event_id, org_id, email, total_minor, currency, payment_method, "
                + "application_fee_minor) values (?, ?, ?, ?, 'b@example.com', ?, ?, 'card', ?)",
                id, "tok-" + id, event, org, total, currency, fee);
        return id;
    }

    private Map<String, Object> stamp(UUID id) {
        return jdbc.queryForMap("select settlement_currency, settlement_gross_minor, settlement_fee_minor from orders "
                + "where id = ?", id);
    }

    private int setStamp(UUID id, String currency, Long gross, Long fee) {
        return jdbc.update("update orders set settlement_currency = ?, settlement_gross_minor = ?, "
                + "settlement_fee_minor = ? where id = ?", currency, gross, fee, id);
    }

    @Test
    void eur_orders_settle_one_to_one() {
        assertThat(stamp(eurLower)).containsEntry("settlement_currency", "eur")
                .containsEntry("settlement_gross_minor", 1_149L).containsEntry("settlement_fee_minor", 149L);
        assertThat(stamp(eurUpper)).containsEntry("settlement_currency", "eur")
                .containsEntry("settlement_gross_minor", 2_298L).containsEntry("settlement_fee_minor", 298L);
        assertThat(stamp(eurFree)).containsEntry("settlement_currency", "eur")
                .containsEntry("settlement_gross_minor", 0L).containsEntry("settlement_fee_minor", 0L);
    }

    @Test
    void non_eur_orders_stay_unstamped() {
        assertThat(stamp(usd)).containsEntry("settlement_currency", null)
                .containsEntry("settlement_gross_minor", null).containsEntry("settlement_fee_minor", null);
    }

    @Test
    void a_full_stamp_is_accepted() {
        assertThat(setStamp(usd, "eur", 1_025L, 133L)).isEqualTo(1);
    }

    @Test
    void half_a_stamp_is_rejected() {
        assertThatThrownBy(() -> setStamp(usd, "eur", 1_025L, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setStamp(usd, null, 1_025L, 133L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stamp(usd)).containsEntry("settlement_currency", null);
    }

    @Test
    void fee_above_gross_is_rejected() {
        assertThatThrownBy(() -> setStamp(usd, "eur", 100L, 133L)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negative_is_rejected() {
        assertThatThrownBy(() -> setStamp(usd, "eur", -1L, 0L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setStamp(usd, "eur", 1_025L, -1L)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
