package com.imin.iminapi.migration;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V94 — {@code orders.idempotency_key} and the shape of {@code uq_orders_idem}.
 *
 * <p>The constraint is the mechanism, not a backstop: it is what settles two
 * simultaneous free checkouts, because at that moment neither request can see
 * the other's row and no amount of application-level looking-up can decide it.
 * So its <b>shape</b> is a behaviour worth pinning, not an implementation
 * detail — and the shape is a security decision, not only a correctness one.
 *
 * <p>The key is minted by an untrusted client, and what a replay hands back is
 * {@code orders.token}: the bearer credential for {@code /order/{token}} and
 * every QR code on it. Scoped to (event, email) as it is here, a collision needs
 * the victim's event AND their exact address AND their key. Globally unique it
 * would instead mean anyone could squat guessable keys and be handed a
 * stranger's tickets — so {@link #theSameKeyFromADifferentAddressIsNotADuplicate}
 * and {@link #theSameKeyOnADifferentEventIsNotADuplicate} are the two tests that
 * would fail if someone ever "tightened" this to a single-column index.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class V94OrdersIdempotencyKeyTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Event event;

    @BeforeEach
    void seedEvent() {
        event = OrderFixtures.event(orgs, users, events, "V94", Instant.parse("2026-11-01T20:00:00Z"));
    }

    @Test
    void the_column_exists() {
        assertThatCode(() -> jdbc.queryForList(
                "SELECT idempotency_key FROM orders WHERE idempotency_key = 'nothing'"))
                .doesNotThrowAnyException();
    }

    // ── What the index rejects ─────────────────────────────────────────────

    @Test
    void theSameKeyTwiceForTheSameBuyerOnTheSameEventIsRejected() {
        persist(event, "buyer@example.com", "key-1");

        assertThatThrownBy(() -> persist(event, "buyer@example.com", "key-1"))
                .isInstanceOf(DataAccessException.class);
    }

    // ── What it deliberately allows ────────────────────────────────────────

    /**
     * The squatting case. If this ever starts failing, the index has been made
     * global and a buyer whose client minted a key somebody else already used is
     * about to be handed that person's order.
     */
    @Test
    void theSameKeyFromADifferentAddressIsNotADuplicate() {
        persist(event, "buyer@example.com", "guessable");

        assertThatCode(() -> persist(event, "someone.else@example.com", "guessable"))
                .doesNotThrowAnyException();
    }

    @Test
    void theSameKeyOnADifferentEventIsNotADuplicate() {
        Event other = OrderFixtures.event(orgs, users, events, "V94b", Instant.parse("2026-12-01T20:00:00Z"));
        persist(event, "buyer@example.com", "shared");

        assertThatCode(() -> persist(other, "buyer@example.com", "shared"))
                .doesNotThrowAnyException();
    }

    /**
     * Every order written before V94, and every order written by a client that
     * sends no header — which is every web buyer today — carries NULL here.
     * PostgreSQL and H2 both treat NULLs as distinct in a unique index, which is
     * the only reason a plain (non-partial) index is safe to add to a live
     * table. If that stopped holding, the second unkeyed order on an event would
     * start failing for a buyer who had already bought one.
     */
    @Test
    void nullKeysAreDistinctSoUnkeyedOrdersNeverCollide() {
        persist(event, "buyer@example.com", null);
        persist(event, "buyer@example.com", null);

        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(2);
    }

    // ── The column is as wide as the header validator claims ───────────────

    /**
     * {@code IdempotencyKey.MAX_LENGTH} rejects anything longer than this at the
     * edge. The two numbers have to agree: if the column were narrower, a key the
     * validator accepted would overflow at the INSERT and become a 500 — the
     * opposite of the answer the validator exists to give.
     */
    @Test
    void aKeyAtTheValidatorsLimitFitsTheColumn() {
        assertThatCode(() -> persist(event, "buyer@example.com",
                "k".repeat(com.imin.iminapi.stripe.IdempotencyKey.MAX_LENGTH)))
                .doesNotThrowAnyException();
    }

    @Test
    void aKeyPastTheValidatorsLimitDoesNotFitTheColumn() {
        assertThatThrownBy(() -> persist(event, "buyer@example.com",
                "k".repeat(com.imin.iminapi.stripe.IdempotencyKey.MAX_LENGTH + 1)))
                .isInstanceOf(DataAccessException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void persist(Event on, String email, String key) {
        Order order = new Order();
        order.setToken("ORD_" + UUID.randomUUID());
        order.setEventId(on.getId());
        order.setOrgId(on.getOrgId());
        order.setEmail(email);
        order.setTotalMinor(0L);
        order.setCurrency("EUR");
        order.setPaymentMethod("free");
        order.setIdempotencyKey(key);
        orders.saveAndFlush(order);
    }
}
