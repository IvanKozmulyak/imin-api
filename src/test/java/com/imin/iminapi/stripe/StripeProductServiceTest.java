package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.stripe.StripeProductService.SyncOutcome;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import com.stripe.service.PriceService;
import com.stripe.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StripeProductServiceTest {

    private final StripeClient client = mock(StripeClient.class);
    private final ProductService products = mock(ProductService.class);
    private final PriceService prices = mock(PriceService.class);
    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final StripeProductService service = new StripeProductService(client, tiers);

    private Event event;
    private TicketTier tier;

    @BeforeEach
    void setUp() throws Exception {
        when(client.products()).thenReturn(products);
        when(client.prices()).thenReturn(prices);
        Product created = new Product();
        created.setId("prod_new");
        created.setDefaultPrice("price_new");
        when(products.create(any(ProductCreateParams.class))).thenReturn(created);

        event = new Event();
        event.setId(UUID.randomUUID());
        event.setOrgId(UUID.randomUUID());
        event.setName("Night");
        event.setCurrency("EUR");

        tier = new TicketTier();
        tier.setId(UUID.randomUUID());
        tier.setEventId(event.getId());
        tier.setName("GA");
        tier.setPriceMinor(1500);
        tier.setQuantity(100);
    }

    @Test
    void missingIds_createsProduct_writesIdsThroughTargetedUpdate_setsInstance() throws Exception {
        when(tiers.updateStripeIdsIfPriceUnchanged(tier.getId(), "prod_new", "price_new", 1500, "eur"))
                .thenReturn(1);

        assertThat(service.syncTier(tier, event)).isEqualTo(SyncOutcome.SYNCED);

        ArgumentCaptor<ProductCreateParams> params = ArgumentCaptor.forClass(ProductCreateParams.class);
        verify(products).create(params.capture());
        assertThat(params.getValue().getName()).isEqualTo("GA");
        assertThat(params.getValue().getDefaultPriceData().getUnitAmount()).isEqualTo(1500L);
        assertThat(params.getValue().getDefaultPriceData().getCurrency()).isEqualTo("eur");
        verify(tiers).updateStripeIdsIfPriceUnchanged(tier.getId(), "prod_new", "price_new", 1500, "eur");
        verify(tiers, never()).save(any());
        assertThat(tier.getStripeProductId()).isEqualTo("prod_new");
        assertThat(tier.getStripePriceId()).isEqualTo("price_new");
    }

    @Test
    void rowMovedAfterCreate_leavesInstanceUntouched_returnsStale() {
        when(tiers.updateStripeIdsIfPriceUnchanged(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(0);

        assertThat(service.syncTier(tier, event)).isEqualTo(SyncOutcome.STALE);

        assertThat(tier.getStripeProductId()).isNull();
        assertThat(tier.getStripePriceId()).isNull();
        verify(tiers, never()).save(any());
    }

    @Test
    void changedPriceAmount_createsAgainWithNewAmount() throws Exception {
        tier.setStripeProductId("prod_old");
        tier.setStripePriceId("price_old");
        Price old = new Price();
        old.setUnitAmount(1000L);
        old.setCurrency("eur");
        when(prices.retrieve("price_old")).thenReturn(old);
        when(tiers.updateStripeIdsIfPriceUnchanged(tier.getId(), "prod_new", "price_new", 1500, "eur"))
                .thenReturn(1);

        assertThat(service.syncTier(tier, event)).isEqualTo(SyncOutcome.SYNCED);

        ArgumentCaptor<ProductCreateParams> params = ArgumentCaptor.forClass(ProductCreateParams.class);
        verify(products).create(params.capture());
        assertThat(params.getValue().getDefaultPriceData().getUnitAmount()).isEqualTo(1500L);
        verify(products, never()).update(anyString(), any(ProductUpdateParams.class));
        assertThat(tier.getStripeProductId()).isEqualTo("prod_new");
        assertThat(tier.getStripePriceId()).isEqualTo("price_new");
    }

    @Test
    void unchangedPrice_updatesProductOnly_noDbWrite() throws Exception {
        tier.setStripeProductId("prod_old");
        tier.setStripePriceId("price_old");
        Price same = new Price();
        same.setUnitAmount(1500L);
        same.setCurrency("eur");
        when(prices.retrieve("price_old")).thenReturn(same);

        assertThat(service.syncTier(tier, event)).isEqualTo(SyncOutcome.UPDATED);

        ArgumentCaptor<ProductUpdateParams> params = ArgumentCaptor.forClass(ProductUpdateParams.class);
        verify(products).update(eq("prod_old"), params.capture());
        assertThat(params.getValue().getName()).isEqualTo("GA");
        verify(products, never()).create(any(ProductCreateParams.class));
        verifyNoInteractions(tiers);
        assertThat(tier.getStripeProductId()).isEqualTo("prod_old");
        assertThat(tier.getStripePriceId()).isEqualTo("price_old");
    }

    @Test
    void stripeException_isSwallowed_returnsFailed_noDbWrite() throws Exception {
        when(products.create(any(ProductCreateParams.class)))
                .thenThrow(new ApiConnectionException("timeout"));

        assertThat(service.syncTier(tier, event)).isEqualTo(SyncOutcome.FAILED);

        verifyNoInteractions(tiers);
        assertThat(tier.getStripeProductId()).isNull();
    }

    @Test
    void runtimeException_isSwallowed_returnsFailed() {
        when(client.products()).thenThrow(new IllegalStateException("boom"));

        assertThat(service.syncTier(tier, event)).isEqualTo(SyncOutcome.FAILED);

        verifyNoInteractions(tiers);
        assertThat(tier.getStripeProductId()).isNull();
    }
}
