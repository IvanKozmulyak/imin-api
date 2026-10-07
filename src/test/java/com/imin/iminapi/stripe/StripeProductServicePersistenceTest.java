package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.stripe.StripeProductService.SyncOutcome;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.stripe.StripeClient;
import com.stripe.model.Product;
import com.stripe.param.ProductCreateParams;
import com.stripe.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sync write-back against a real row: a stale tier snapshot must not revert inventory. */
@IminIntegrationTest
class StripeProductServicePersistenceTest {

    @Autowired StripeProductService productService;
    @Autowired StripeClient stripeClient;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void syncOfStaleSnapshot_keepsReservedAndSoldCommittedMeanwhile() throws Exception {
        Organization org = fx.org();
        Event event = fx.event(org, fx.owner(org), EventStatus.LIVE, clock.instant().plus(Duration.ofDays(14)));
        TicketTier tier = fx.tier(event, 1500, 100);
        // Checkout's snapshot was taken before reserve; the reserve and a confirm commit meanwhile.
        jdbc.update("UPDATE ticket_tiers SET reserved = 3, sold = 2 WHERE id = ?", tier.getId());

        ProductService products = mock(ProductService.class);
        when(stripeClient.products()).thenReturn(products);
        Product created = new Product();
        created.setId("prod_snap_" + tier.getId());
        created.setDefaultPrice("price_snap_" + tier.getId());
        when(products.create(any(ProductCreateParams.class))).thenReturn(created);

        SyncOutcome outcome = productService.syncTier(tier, event);

        assertThat(outcome).isEqualTo(SyncOutcome.SYNCED);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reserved, sold, stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = ?", tier.getId());
        assertThat(((Number) row.get("reserved")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("sold")).intValue()).isEqualTo(2);
        assertThat(row.get("stripe_product_id")).isEqualTo("prod_snap_" + tier.getId());
        assertThat(row.get("stripe_price_id")).isEqualTo("price_snap_" + tier.getId());
    }
}
