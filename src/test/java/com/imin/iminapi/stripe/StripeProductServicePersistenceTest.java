package com.imin.iminapi.stripe;

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
import com.imin.iminapi.stripe.StripeProductService.SyncOutcome;
import com.stripe.StripeClient;
import com.stripe.model.Product;
import com.stripe.param.ProductCreateParams;
import com.stripe.service.ProductService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sync write-back against a real row: a stale tier snapshot must not revert inventory. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StripeProductServicePersistenceTest {

    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EntityManager em;

    @Test
    void syncOfStaleSnapshot_keepsReservedAndSoldCommittedMeanwhile() throws Exception {
        Event event = seedEvent();
        TicketTier tier = new TicketTier();
        tier.setEventId(event.getId());
        tier.setName("GA");
        tier.setPriceMinor(1500);
        tier.setQuantity(100);
        tier = tiers.save(tier);
        em.flush();
        em.detach(tier);
        // Checkout's snapshot was taken before reserve; the reserve and a confirm commit meanwhile.
        em.createNativeQuery("UPDATE ticket_tiers SET reserved = 3, sold = 2 WHERE id = :id")
                .setParameter("id", tier.getId()).executeUpdate();

        StripeClient client = mock(StripeClient.class);
        ProductService products = mock(ProductService.class);
        when(client.products()).thenReturn(products);
        Product created = new Product();
        created.setId("prod_snap");
        created.setDefaultPrice("price_snap");
        when(products.create(any(ProductCreateParams.class))).thenReturn(created);

        SyncOutcome outcome = new StripeProductService(client, tiers).syncTier(tier, event);
        em.flush();
        em.clear();

        assertThat(outcome).isEqualTo(SyncOutcome.SYNCED);
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT reserved, sold, stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = :id")
                .setParameter("id", tier.getId()).getSingleResult();
        assertThat(((Number) row[0]).intValue()).isEqualTo(3);
        assertThat(((Number) row[1]).intValue()).isEqualTo(2);
        assertThat(row[2]).isEqualTo("prod_snap");
        assertThat(row[3]).isEqualTo("price_snap");
    }

    private Event seedEvent() {
        Organization org = new Organization();
        org.setName("Sync Org");
        org.setSlug("sync-org-" + UUID.randomUUID().toString().substring(0, 8));
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
        e.setName("Sync Night");
        e.setSlug("sync-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now());
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return events.save(e);
    }
}
