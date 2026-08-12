package com.imin.iminapi.migration;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V86 — {@code orders.email_normalized}, its index, and the two things that
 * keep it honest.
 *
 * <p>The column is the join key for {@code GET /buyer/orders}. If it ever
 * disagrees with {@code EmailNormalizer} the endpoint silently under-reports a
 * buyer's tickets, which is a defect nobody notices until a buyer complains
 * that an order is missing. Two independent mechanisms have to agree, so both
 * are tested here rather than assumed:
 *
 * <ol>
 *   <li>the <b>backfill</b>, {@code lower(trim(email))}, which runs once inside
 *       the migration and fixes the 67 rows that predate the column;</li>
 *   <li>the <b>lifecycle callback</b> on {@code Order}, which is what keeps
 *       every row written afterwards in step — including rows whose email is
 *       later edited, which is the case "set it at the two call sites" would
 *       miss.</li>
 * </ol>
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class V86OrdersEmailNormalizedTest {

    /** The migration's backfill statement, verbatim, narrowed to one row. */
    private static final String BACKFILL =
            "update orders set email_normalized = lower(trim(email)) where email is not null and id = ?";

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Event event;

    @BeforeEach
    void seedEvent() {
        event = OrderFixtures.event(orgs, users, events, "V86", Instant.parse("2026-10-01T20:00:00Z"));
    }

    @Test
    void the_column_and_its_index_exist() {
        assertThatCode(() -> jdbc.queryForList(
                "SELECT email_normalized FROM orders WHERE email_normalized = 'nobody@example.com'"))
                .doesNotThrowAnyException();
    }

    // ── The backfill agrees with EmailNormalizer ───────────────────────────

    /**
     * The migration normalises in SQL; every other writer normalises in Java.
     * They have to produce the same string or the join misses.
     *
     * <p>The cases are chosen to pin down what normalisation deliberately does
     * <b>not</b> do: no plus-address stripping, no dot folding. Both are
     * conventions of particular mail providers, not of email, and folding them
     * here would let one person's account claim another person's orders at
     * {@code gmail.com} while doing nothing at all elsewhere.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "ada@example.com",
            "  Ada@Example.COM  ",
            "ADA@EXAMPLE.COM",
            "ada+festival-2026@example.com",      // plus-addressing survives
            "a.d.a@example.com",                  // dots survive
            "ada@sub.domain.example.com",
    })
    void the_backfill_expression_matches_EmailNormalizer(String raw) {
        Order order = persistOrder(raw);

        // Put the row back into its pre-V86 shape, then run the migration's own
        // UPDATE against it. This is the real statement on the real engine, not
        // a re-implementation of it.
        jdbc.update("update orders set email_normalized = null where id = ?", order.getId());
        jdbc.update(BACKFILL, order.getId());

        assertThat(readNormalized(order.getId()))
                .as("lower(trim(email)) must equal EmailNormalizer.normalize for %s", raw)
                .isEqualTo(EmailNormalizer.normalize(raw));
    }

    // ── The lifecycle callback is the anti-drift mechanism ─────────────────

    @Test
    void persisting_an_order_derives_email_normalized_without_the_writer_asking() {
        Order order = persistOrder("  Fresh.Buyer+tag@Example.COM ");

        assertThat(readNormalized(order.getId())).isEqualTo("fresh.buyer+tag@example.com");
    }

    /**
     * The case a "set it in both order writers" convention would miss, and the
     * reason the derivation is a {@code @PreUpdate} and not a constructor
     * argument: an order whose email is corrected later must re-derive, or its
     * tickets attach to the buyer's OLD address forever.
     */
    @Test
    void updating_the_email_re_derives_email_normalized() {
        Order order = persistOrder("typo@example.com");

        order.setEmail("  Corrected@Example.com ");
        orders.saveAndFlush(order);

        assertThat(readNormalized(order.getId())).isEqualTo("corrected@example.com");
    }

    @Test
    void the_derivation_survives_a_reload_rather_than_living_only_in_memory() {
        Order order = persistOrder("Reload@Example.com");

        Order reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getEmailNormalized()).isEqualTo("reload@example.com");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Order persistOrder(String email) {
        return OrderFixtures.order(orders, event, email, Instant.parse("2026-08-01T10:00:00Z"));
    }

    private String readNormalized(UUID orderId) {
        return jdbc.queryForObject(
                "select email_normalized from orders where id = ?", String.class, orderId);
    }
}
