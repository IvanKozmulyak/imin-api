package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tier's Stripe-id write-back and the sync sweep's selection and claim, against Postgres.
 * The database is shared, so candidate queries are read for this test's own tier ids only.
 */
@IminIntegrationTest
class TicketTierRepositoryPersistenceTest {

    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID eventId;
    private UUID orgId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID());
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = orgs.save(org);
        orgId = org.getId();

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
        ownerId = owner.getId();

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Inventory Test Event");
        e.setSlug("inventory-test-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now());
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        eventId = events.save(e).getId();
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, List.of(orgId));
    }

    /** Stripe is sent the lower-case currency; the write lands only while price and stored currency still match. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "lower-case stored currency matches, eur, 1500, 1",
            "upper-case stored currency matches, EUR, 1500, 1",
            "event currency moved,               USD, 1500, 0",
            "price moved,                        EUR, 2000, 0"})
    void updateStripeIds_landsOnlyWhilePriceAndCurrencyMatch(String scenario, String storedCurrency,
                                                             int storedPrice, int expectedRows) {
        UUID id = savedTier(1500);
        jdbc.update("UPDATE events SET currency = ? WHERE id = ?", storedCurrency, eventId);
        setPrice(id, storedPrice);

        int rows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur");

        assertThat(rows).isEqualTo(expectedRows);
        if (expectedRows == 1) {
            assertThat(storedIds(id)).containsExactly("prod_a", "price_a");
        } else {
            assertThat(storedIds(id)).containsExactly(null, null);
        }
    }

    @Test
    void oldPriceSyncThenNewPriceSync_newIdsKept() {
        UUID id = savedTier(1000);
        setPrice(id, 2000);

        int oldRows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_old", "price_old", 1000, "eur");
        int newRows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_new", "price_new", 2000, "eur");

        assertThat(oldRows).isZero();
        assertThat(newRows).isEqualTo(1);
        assertThat(storedIds(id)).containsExactly("prod_new", "price_new");
    }

    @Test
    void newPriceSyncThenOldPriceSync_newIdsKept() {
        UUID id = savedTier(1000);
        setPrice(id, 2000);

        int newRows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_new", "price_new", 2000, "eur");
        int oldRows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_old", "price_old", 1000, "eur");

        assertThat(newRows).isEqualTo(1);
        assertThat(oldRows).isZero();
        assertThat(storedIds(id)).containsExactly("prod_new", "price_new");
    }

    @Test
    void staleFullSaveAfterTargetedUpdate_keepsIds_andTargetedUpdateAfterFullSaveLands() {
        UUID id = savedTier(1500);
        TicketTier snapshot = tiers.findById(id).orElseThrow();

        // Targeted update first, then a full save of a snapshot taken before it.
        assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur")).isEqualTo(1);
        snapshot.setName("Renamed");
        tiers.save(snapshot);
        assertThat(storedIds(id)).containsExactly("prod_a", "price_a");
        assertThat(storedName(id)).isEqualTo("Renamed");

        // Full save first, then the targeted update.
        TicketTier loaded = tiers.findById(id).orElseThrow();
        loaded.setName("Renamed again");
        loaded.setStripeProductId("prod_ignored");
        tiers.save(loaded);
        assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_b", "price_b", 1500, "eur")).isEqualTo(1);
        assertThat(storedIds(id)).containsExactly("prod_b", "price_b");
        assertThat(storedName(id)).isEqualTo("Renamed again");
    }

    // ── sweep selection and claim ──────────────────────────────────────────────

    @Test
    void sweepCandidates_onlyUnsyncedEnabledPaidTiersOfSettledDraftOrLiveEvents() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant settled = now.minus(10, ChronoUnit.MINUTES);
        UUID live = eventWith(EventStatus.LIVE, settled, false);
        UUID draft = eventWith(EventStatus.DRAFT, settled, false);
        UUID past = eventWith(EventStatus.PAST, settled, false);
        UUID cancelled = eventWith(EventStatus.CANCELLED, settled, false);
        UUID deleted = eventWith(EventStatus.LIVE, settled, true);
        UUID fresh = eventWith(EventStatus.LIVE, now.minus(30, ChronoUnit.SECONDS), false);

        UUID draftNoIds = tierOn(draft, 1000, true, null, null);
        UUID liveNoProduct = tierOn(live, 1000, true, null, "price_x");
        UUID blankPrice = tierOn(live, 1000, true, "prod_x", "");
        UUID dueAgain = tierOn(live, 1000, true, null, null);
        setNextAt(dueAgain, now.minus(1, ChronoUnit.MINUTES));

        UUID disabled = tierOn(live, 1000, false, null, null);
        UUID free = tierOn(live, 0, true, null, null);
        UUID synced = tierOn(live, 1000, true, "prod_ok", "price_ok");
        UUID onPast = tierOn(past, 1000, true, null, null);
        UUID onCancelled = tierOn(cancelled, 1000, true, null, null);
        UUID onDeleted = tierOn(deleted, 1000, true, null, null);
        UUID onFresh = tierOn(fresh, 1000, true, null, null);
        UUID notDue = tierOn(live, 1000, true, null, null);
        setNextAt(notDue, now.plus(1, ChronoUnit.HOURS));

        Set<UUID> fixture = Set.of(draftNoIds, liveNoProduct, blankPrice, dueAgain,
                disabled, free, synced, onPast, onCancelled, onDeleted, onFresh, notDue);
        Set<UUID> picked = tiers.findStripeSyncSweepCandidates(List.of(EventStatus.DRAFT, EventStatus.LIVE),
                        now.minus(2, ChronoUnit.MINUTES), now, PageRequest.of(0, 10_000)).stream()
                .map(r -> (UUID) r[0]).filter(fixture::contains).collect(Collectors.toSet());

        assertThat(picked).containsExactlyInAnyOrder(draftNoIds, liveNoProduct, blankPrice, dueAgain);
    }

    @Test
    void sweepCandidates_fewestAttemptsFirst_boundedByPage() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID live = eventWith(EventStatus.LIVE, now.minus(10, ChronoUnit.MINUTES), false);
        // Id order is two, one, zero (Postgres compares uuids as unsigned bytes, i.e. as hex strings),
        // so an order by id alone would return them reversed.
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(3)
                .sorted(Comparator.comparing(UUID::toString)).toList();
        UUID two = tierWithId(ids.get(0), live);
        UUID one = tierWithId(ids.get(1), live);
        UUID zero = tierWithId(ids.get(2), live);
        setAttempts(two, 2);
        setAttempts(one, 1);
        Set<UUID> own = Set.of(zero, one, two);

        List<Object[]> page = tiers.findStripeSyncSweepCandidates(List.of(EventStatus.DRAFT, EventStatus.LIVE),
                now.minus(2, ChronoUnit.MINUTES), now, PageRequest.of(0, 10_000)).stream()
                .filter(r -> own.contains((UUID) r[0])).toList();

        assertThat(page).extracting(r -> (UUID) r[0]).containsExactly(zero, one, two);
        assertThat(page).extracting(r -> ((Number) r[1]).intValue()).containsExactly(0, 1, 2);
        assertThat(tiers.findStripeSyncSweepCandidates(List.of(EventStatus.DRAFT, EventStatus.LIVE),
                now.minus(2, ChronoUnit.MINUTES), now, PageRequest.of(0, 1))).hasSize(1);
    }

    @Test
    void claimSweep_landsWhenDueAndAttemptsUnchanged() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = savedTier(1500);
        setAttempts(id, 2);
        setNextAt(id, now.minus(1, ChronoUnit.MINUTES));
        Instant next = now.plus(20, ChronoUnit.MINUTES);

        int rows = tiers.claimStripeSyncSweep(id, 2, next, now);

        assertThat(rows).isEqualTo(1);
        assertThat(storedAttempts(id)).isEqualTo(3);
        assertThat(storedNextAt(id)).isEqualTo(next);
    }

    enum ClaimMiss { ATTEMPTS_MOVED, IDS_LANDED, NOT_YET_DUE }

    /** Each race the compare-and-set claim must lose: the row keeps whatever it held. */
    @ParameterizedTest
    @EnumSource(ClaimMiss.class)
    void claimSweep_noOpWhenTheRowMoved(ClaimMiss miss) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = savedTier(1500);
        Instant later = now.plus(1, ChronoUnit.HOURS);
        int attemptsBefore = 0;
        Instant nextAtBefore = null;
        switch (miss) {
            case ATTEMPTS_MOVED -> {
                setAttempts(id, 1);
                attemptsBefore = 1;
            }
            case IDS_LANDED -> assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur"))
                    .isEqualTo(1);
            case NOT_YET_DUE -> {
                setNextAt(id, later);
                nextAtBefore = later;
            }
        }

        int rows = tiers.claimStripeSyncSweep(id, 0, now.plus(5, ChronoUnit.MINUTES), now);

        assertThat(rows).isZero();
        assertThat(storedAttempts(id)).isEqualTo(attemptsBefore);
        assertThat(storedNextAt(id)).isEqualTo(nextAtBefore);
    }

    @Test
    void updateStripeIds_resetsSweepBackoff() {
        UUID id = savedTier(1500);
        setAttempts(id, 3);
        setNextAt(id, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur")).isEqualTo(1);

        assertThat(storedAttempts(id)).isZero();
        assertThat(storedNextAt(id)).isNull();
    }

    @Test
    void fullSave_neverWritesSweepBackoff_bothOrderings() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = savedTier(1500);
        TicketTier snapshot = tiers.findById(id).orElseThrow();

        // Claim first, then a full save of a snapshot taken before it.
        Instant next = now.plus(5, ChronoUnit.MINUTES);
        assertThat(tiers.claimStripeSyncSweep(id, 0, next, now)).isEqualTo(1);
        snapshot.setName("Renamed");
        tiers.save(snapshot);
        assertThat(storedAttempts(id)).isEqualTo(1);
        assertThat(storedNextAt(id)).isEqualTo(next);
        assertThat(storedName(id)).isEqualTo("Renamed");

        // Full save first (with backoff fields set in memory), then the claim.
        TicketTier loaded = tiers.findById(id).orElseThrow();
        loaded.setName("Renamed again");
        loaded.setStripeSyncAttempts(99);
        loaded.setStripeSyncNextAt(null);
        tiers.save(loaded);
        assertThat(storedAttempts(id)).isEqualTo(1);
        assertThat(storedNextAt(id)).isEqualTo(next);
        Instant later = next.plus(1, ChronoUnit.MINUTES);
        Instant afterThat = later.plus(10, ChronoUnit.MINUTES);
        assertThat(tiers.claimStripeSyncSweep(id, 1, afterThat, later)).isEqualTo(1);
        assertThat(storedAttempts(id)).isEqualTo(2);
        assertThat(storedNextAt(id)).isEqualTo(afterThat);
        assertThat(storedName(id)).isEqualTo("Renamed again");
    }

    private UUID eventWith(EventStatus status, Instant updatedAt, boolean deleted) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Sweep " + status);
        e.setSlug("sweep-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setCreatedBy(ownerId);
        e.setCurrency("EUR");
        UUID id = events.save(e).getId();
        jdbc.update("UPDATE events SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), id);
        if (deleted) {
            jdbc.update("UPDATE events SET deleted_at = ? WHERE id = ?", Timestamp.from(updatedAt), id);
        }
        return id;
    }

    private UUID tierOn(UUID event, int priceMinor, boolean enabled, String productId, String priceId) {
        TicketTier t = new TicketTier();
        t.setEventId(event);
        t.setName("T");
        t.setPriceMinor(priceMinor);
        t.setQuantity(10);
        t.setEnabled(enabled);
        t.setStripeProductId(productId);
        t.setStripePriceId(priceId);
        return tiers.save(t).getId();
    }

    private UUID tierWithId(UUID id, UUID event) {
        jdbc.update("INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, reserved, "
                + "enabled, sort_order) VALUES (?, ?, 'T', 1000, 10, 0, 0, true, 0)", id, event);
        return id;
    }

    private void setAttempts(UUID id, int attempts) {
        jdbc.update("UPDATE ticket_tiers SET stripe_sync_attempts = ? WHERE id = ?", attempts, id);
    }

    private void setNextAt(UUID id, Instant at) {
        jdbc.update("UPDATE ticket_tiers SET stripe_sync_next_at = ? WHERE id = ?", Timestamp.from(at), id);
    }

    private int storedAttempts(UUID id) {
        return jdbc.queryForObject("SELECT stripe_sync_attempts FROM ticket_tiers WHERE id = ?", Integer.class, id);
    }

    private Instant storedNextAt(UUID id) {
        Timestamp ts = jdbc.queryForObject("SELECT stripe_sync_next_at FROM ticket_tiers WHERE id = ?",
                Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    private UUID savedTier(int priceMinor) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(priceMinor);
        t.setQuantity(100);
        return tiers.save(t).getId();
    }

    private void setPrice(UUID id, int priceMinor) {
        jdbc.update("UPDATE ticket_tiers SET price_minor = ? WHERE id = ?", priceMinor, id);
    }

    private List<Object> storedIds(UUID id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = ?", id);
        return new ArrayList<>(Arrays.asList(row.get("stripe_product_id"), row.get("stripe_price_id")));
    }

    private String storedName(UUID id) {
        return jdbc.queryForObject("SELECT name FROM ticket_tiers WHERE id = ?", String.class, id);
    }
}
