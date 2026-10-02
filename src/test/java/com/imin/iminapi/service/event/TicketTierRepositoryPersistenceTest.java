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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

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
