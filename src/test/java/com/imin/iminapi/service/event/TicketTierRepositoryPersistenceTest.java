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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the new {@code reserved} column persists round-trip and that
 * {@link TicketTierRepository#findByIdForUpdate(UUID)} returns the row (the lock
 * itself is hard to assert without a second connection — the value here is
 * confirming the JPQL + lock annotation actually compile against the schema and
 * H2 doesn't choke on the {@code FOR UPDATE} syntax in PG-compat mode).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketTierRepositoryPersistenceTest {

    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EntityManager em;

    private UUID eventId;
    private UUID orgId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
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
        e.setSlug("inventory-test-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now());
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        eventId = events.save(e).getId();
    }

    @Test
    void reservedColumnPersists_andDefaultsToZero() {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(1000);
        t.setQuantity(100);
        // reserved deliberately not set — should default to 0
        TicketTier saved = tiers.save(t);

        Optional<TicketTier> found = tiers.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getReserved()).isZero();
    }

    @Test
    void reservedColumnRoundTrips_whenSetExplicitly() {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("VIP");
        t.setPriceMinor(5000);
        t.setQuantity(50);
        t.setReserved(7);
        t.setSold(3);
        TicketTier saved = tiers.save(t);

        Optional<TicketTier> found = tiers.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getReserved()).isEqualTo(7);
        assertThat(found.get().getSold()).isEqualTo(3);
        assertThat(found.get().getQuantity()).isEqualTo(50);
    }

    @Test
    void findByIdForUpdate_returnsRow() {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("Locked");
        t.setPriceMinor(2000);
        t.setQuantity(10);
        t.setReserved(2);
        TicketTier saved = tiers.save(t);

        Optional<TicketTier> locked = tiers.findByIdForUpdate(saved.getId());
        assertThat(locked).isPresent();
        assertThat(locked.get().getId()).isEqualTo(saved.getId());
        assertThat(locked.get().getReserved()).isEqualTo(2);
    }

    @Test
    void findByIdForUpdate_returnsEmpty_whenIdMissing() {
        assertThat(tiers.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }

    @Test
    void updateStripeIds_landsWhenPriceAndCurrencyMatch() {
        UUID id = savedTier(1500);

        int rows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur");

        assertThat(rows).isEqualTo(1);
        assertThat(storedIds(id)).containsExactly("prod_a", "price_a");
    }

    @Test
    void updateStripeIds_noOpWhenEventCurrencyMoved() {
        UUID id = savedTier(1500);
        em.createNativeQuery("UPDATE events SET currency = 'USD' WHERE id = :id")
                .setParameter("id", eventId).executeUpdate();

        int rows = tiers.updateStripeIdsIfPriceUnchanged(id, "prod_eur", "price_eur", 1500, "eur");

        assertThat(rows).isZero();
        assertThat(storedIds(id)).containsExactly(null, null);
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
        em.detach(snapshot);

        // Targeted update first, then a full save of a snapshot taken before it.
        assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur")).isEqualTo(1);
        snapshot.setName("Renamed");
        tiers.save(snapshot);
        em.flush();
        em.clear();
        assertThat(storedIds(id)).containsExactly("prod_a", "price_a");
        assertThat(storedName(id)).isEqualTo("Renamed");

        // Full save first, then the targeted update.
        TicketTier managed = tiers.findById(id).orElseThrow();
        managed.setName("Renamed again");
        managed.setStripeProductId("prod_ignored");
        tiers.save(managed);
        assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_b", "price_b", 1500, "eur")).isEqualTo(1);
        em.flush();
        em.clear();
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
        em.flush();
        em.clear();

        Set<UUID> fixture = Set.of(draftNoIds, liveNoProduct, blankPrice, dueAgain,
                disabled, free, synced, onPast, onCancelled, onDeleted, onFresh, notDue);
        // The H2 database is shared with other contexts, so only this fixture's rows are compared.
        Set<UUID> picked = tiers.findStripeSyncSweepCandidates(List.of(EventStatus.DRAFT, EventStatus.LIVE),
                        now.minus(2, ChronoUnit.MINUTES), now, PageRequest.of(0, 10_000)).stream()
                .map(r -> (UUID) r[0]).filter(fixture::contains).collect(Collectors.toSet());

        assertThat(picked).containsExactlyInAnyOrder(draftNoIds, liveNoProduct, blankPrice, dueAgain);
    }

    @Test
    void sweepCandidates_fewestAttemptsFirst_boundedByPage() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID live = eventWith(EventStatus.LIVE, now.minus(10, ChronoUnit.MINUTES), false);
        // Id order is two, one, zero, so an order by id alone would return the wrong pair.
        UUID two = tierWithId(UUID.fromString("00000000-0000-4000-8000-000000000001"), live);
        UUID one = tierWithId(UUID.fromString("00000000-0000-4000-8000-000000000002"), live);
        UUID zero = tierWithId(UUID.fromString("00000000-0000-4000-8000-000000000003"), live);
        setAttempts(two, 2);
        setAttempts(one, 1);
        // Parks every other tier for this test only; the test transaction rolls it back.
        em.createNativeQuery("UPDATE ticket_tiers SET stripe_sync_next_at = :far WHERE event_id <> :e")
                .setParameter("far", now.plus(3650, ChronoUnit.DAYS)).setParameter("e", live).executeUpdate();
        em.flush();
        em.clear();

        List<Object[]> page = tiers.findStripeSyncSweepCandidates(List.of(EventStatus.DRAFT, EventStatus.LIVE),
                now.minus(2, ChronoUnit.MINUTES), now, PageRequest.of(0, 2));

        assertThat(page).extracting(r -> (UUID) r[0]).containsExactly(zero, one);
        assertThat(page).extracting(r -> ((Number) r[1]).intValue()).containsExactly(0, 1);
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

    @Test
    void claimSweep_noOpWhenAttemptsMoved() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = savedTier(1500);
        setAttempts(id, 1);

        int rows = tiers.claimStripeSyncSweep(id, 0, now.plus(5, ChronoUnit.MINUTES), now);

        assertThat(rows).isZero();
        assertThat(storedAttempts(id)).isEqualTo(1);
        assertThat(storedNextAt(id)).isNull();
    }

    @Test
    void claimSweep_noOpWhenIdsLanded() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = savedTier(1500);
        assertThat(tiers.updateStripeIdsIfPriceUnchanged(id, "prod_a", "price_a", 1500, "eur")).isEqualTo(1);

        int rows = tiers.claimStripeSyncSweep(id, 0, now.plus(5, ChronoUnit.MINUTES), now);

        assertThat(rows).isZero();
        assertThat(storedAttempts(id)).isZero();
        assertThat(storedNextAt(id)).isNull();
    }

    @Test
    void claimSweep_noOpWhenNotYetDue() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = savedTier(1500);
        Instant later = now.plus(1, ChronoUnit.HOURS);
        setNextAt(id, later);

        int rows = tiers.claimStripeSyncSweep(id, 0, now.plus(5, ChronoUnit.MINUTES), now);

        assertThat(rows).isZero();
        assertThat(storedAttempts(id)).isZero();
        assertThat(storedNextAt(id)).isEqualTo(later);
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
        em.detach(snapshot);

        // Claim first, then a full save of a snapshot taken before it.
        Instant next = now.plus(5, ChronoUnit.MINUTES);
        assertThat(tiers.claimStripeSyncSweep(id, 0, next, now)).isEqualTo(1);
        snapshot.setName("Renamed");
        tiers.save(snapshot);
        em.flush();
        em.clear();
        assertThat(storedAttempts(id)).isEqualTo(1);
        assertThat(storedNextAt(id)).isEqualTo(next);
        assertThat(storedName(id)).isEqualTo("Renamed");

        // Full save first (with backoff fields set in memory), then the claim.
        TicketTier managed = tiers.findById(id).orElseThrow();
        managed.setName("Renamed again");
        managed.setStripeSyncAttempts(99);
        managed.setStripeSyncNextAt(null);
        tiers.save(managed);
        em.flush();
        em.clear();
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
        e.setSlug("sweep-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setCreatedBy(ownerId);
        e.setCurrency("EUR");
        UUID id = events.save(e).getId();
        em.flush();
        em.createNativeQuery("UPDATE events SET updated_at = :u WHERE id = :id")
                .setParameter("u", updatedAt).setParameter("id", id).executeUpdate();
        if (deleted) {
            em.createNativeQuery("UPDATE events SET deleted_at = :u WHERE id = :id")
                    .setParameter("u", updatedAt).setParameter("id", id).executeUpdate();
        }
        em.clear();
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
        UUID id = tiers.save(t).getId();
        em.flush();
        return id;
    }

    private UUID tierWithId(UUID id, UUID event) {
        em.createNativeQuery("INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, reserved, "
                        + "enabled, sort_order) VALUES (:id, :e, 'T', 1000, 10, 0, 0, true, 0)")
                .setParameter("id", id).setParameter("e", event).executeUpdate();
        return id;
    }

    private void setAttempts(UUID id, int attempts) {
        em.createNativeQuery("UPDATE ticket_tiers SET stripe_sync_attempts = :a WHERE id = :id")
                .setParameter("a", attempts).setParameter("id", id).executeUpdate();
    }

    private void setNextAt(UUID id, Instant at) {
        em.createNativeQuery("UPDATE ticket_tiers SET stripe_sync_next_at = :n WHERE id = :id")
                .setParameter("n", at).setParameter("id", id).executeUpdate();
    }

    private int storedAttempts(UUID id) {
        return ((Number) em.createNativeQuery("SELECT stripe_sync_attempts FROM ticket_tiers WHERE id = :id")
                .setParameter("id", id).getSingleResult()).intValue();
    }

    private Instant storedNextAt(UUID id) {
        Object v = em.createNativeQuery("SELECT stripe_sync_next_at FROM ticket_tiers WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        throw new IllegalStateException("unexpected type " + v.getClass());
    }

    private UUID savedTier(int priceMinor) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(priceMinor);
        t.setQuantity(100);
        UUID id = tiers.save(t).getId();
        em.flush();
        return id;
    }

    private void setPrice(UUID id, int priceMinor) {
        em.createNativeQuery("UPDATE ticket_tiers SET price_minor = :p WHERE id = :id")
                .setParameter("p", priceMinor).setParameter("id", id).executeUpdate();
    }

    private java.util.List<Object> storedIds(UUID id) {
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        return java.util.Arrays.asList(row);
    }

    private String storedName(UUID id) {
        return (String) em.createNativeQuery("SELECT name FROM ticket_tiers WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
}
