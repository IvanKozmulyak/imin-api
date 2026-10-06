package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.stripe.StripeClient;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.param.ProductCreateParams;
import com.stripe.service.PriceService;
import com.stripe.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The tier-id write runs inside the organizer's event patch; nothing else that patch changes may be lost. */
@IminIntegrationTest
class EventPatchStripeSyncPersistenceTest {

    @Autowired StripeClient stripeClient;
    @Autowired IminFixtures fx;

    @Autowired EventService eventService;
    @Autowired TicketTierRepository tiers;
    @Autowired JdbcTemplate jdbc;

    private UUID eventId;
    private UUID existingTierId;
    private AuthPrincipal principal;

    private ProductService products;

    @BeforeEach
    void setUp() throws Exception {
        products = mock(ProductService.class);
        PriceService prices = mock(PriceService.class);
        when(stripeClient.products()).thenReturn(products);
        when(stripeClient.prices()).thenReturn(prices);
        Product created = new Product();
        created.setId("prod_embedded");
        created.setDefaultPrice("price_embedded");
        when(products.create(any(ProductCreateParams.class))).thenReturn(created);
        Price existing = new Price();
        existing.setUnitAmount(1000L);
        existing.setCurrency("eur");
        when(prices.retrieve("price_existing")).thenReturn(existing);

        Organization org = fx.org();
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        eventId = e.getId();

        existingTierId = fx.tier(e, 1000, 50).getId();
        // The id columns are updatable=false, so set them the way the sync does: a targeted UPDATE.
        jdbc.update("UPDATE ticket_tiers SET stripe_product_id = 'prod_existing', stripe_price_id = 'price_existing' "
                + "WHERE id = ?", existingTierId);
    }

    @Test
    void patchWithNewPaidTier_persistsEventFieldTierEditsAndStripeIds() throws Exception {
        TicketTierEmbeddedPatch rename = new TicketTierEmbeddedPatch(existingTierId, "Early bird",
                null, null, null, null, null, null, null, null);
        TicketTierEmbeddedPatch create = new TicketTierEmbeddedPatch(null, "GA",
                2500, 100, null, null, null, null, 1, true);
        EventPatchRequest body = new EventPatchRequest("After", null, null, null, null, null, null, null,
                null, "New description", null, null, null, null, null, List.of(rename, create), null);

        eventService.patch(principal, eventId, null, body);

        Map<String, Object> event = jdbc.queryForMap("SELECT name, description FROM events WHERE id = ?", eventId);
        assertThat(event.get("name")).isEqualTo("After");
        assertThat(event.get("description")).isEqualTo("New description");
        Map<String, Object> renamed = jdbc.queryForMap(
                "SELECT name, stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = ?", existingTierId);
        assertThat(renamed.get("name")).isEqualTo("Early bird");
        assertThat(renamed.get("stripe_product_id")).isEqualTo("prod_existing");
        assertThat(renamed.get("stripe_price_id")).isEqualTo("price_existing");
        // The sync runs after commit on the queue thread.
        Map<String, Object> added = Map.of();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            added = jdbc.queryForMap("SELECT price_minor, stripe_product_id, stripe_price_id FROM ticket_tiers "
                    + "WHERE event_id = ? AND name = 'GA'", eventId);
            if ("prod_embedded".equals(added.get("stripe_product_id"))) break;
            Thread.sleep(50);
        }
        assertThat(((Number) added.get("price_minor")).intValue()).isEqualTo(2500);
        assertThat(added.get("stripe_product_id")).isEqualTo("prod_embedded");
        assertThat(added.get("stripe_price_id")).isEqualTo("price_embedded");
    }

    @Test
    void rolledBackPatch_neverCallsStripe() throws Exception {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("Unsynced");
        t.setPriceMinor(1500);
        t.setQuantity(20);
        UUID unsyncedId = tiers.save(t).getId();
        TicketTierEmbeddedPatch rename = new TicketTierEmbeddedPatch(unsyncedId, "Renamed",
                null, null, null, null, null, null, null, null);
        TicketTierEmbeddedPatch invalid = new TicketTierEmbeddedPatch(null, null,
                2500, 100, null, null, null, null, 1, true);
        EventPatchRequest body = new EventPatchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(rename, invalid), null);

        assertThatThrownBy(() -> eventService.patch(principal, eventId, null, body))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(products, after(500).never()).create(any(ProductCreateParams.class));
        assertThat(jdbc.queryForObject("SELECT name FROM ticket_tiers WHERE id = ?", String.class, unsyncedId))
                .isEqualTo("Unsynced");
    }
}
