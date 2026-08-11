package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.event.FreeCheckoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3b: the {@code orders.ads_consent} write path (spec §7). Without this the whole
 * server-side CAPI pipeline (Tasks 6/7/8) is dead code — {@code MetaCapiOutboxWriter}
 * short-circuits on {@code ads_consent=false}, and nothing else in the phase sets it true.
 *
 * <p>The free order-creation path runs inline in
 * {@link FreeCheckoutService#issueFreeOrder}; this test asserts the ads-consent flag the
 * buyer's cookie-consent state carried through checkout is persisted onto the {@link Order},
 * and that the V60 {@code DEFAULT false} holds when consent is absent. The paid-path facets
 * (Stripe session {@code metadata} put + webhook read-back) are covered by
 * {@code StripeCheckoutServiceTest} and {@code PaidCheckoutServiceTest} respectively.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AdsConsentWriteTest {

    @Autowired FreeCheckoutService freeCheckout;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Event event;
    private TicketTier freeTier;

    @BeforeEach
    void setUp() {
        cleanUp();

        Organization org = new Organization();
        org.setName("Ads Consent Org");
        org.setSlug("ads-consent-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("ads-consent@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("ads-consent-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Consent Fest");
        event.setSlug("ads-consent-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(3600));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        freeTier = new TicketTier();
        freeTier.setEventId(event.getId());
        freeTier.setName("Free GA");
        freeTier.setPriceMinor(0);
        freeTier.setQuantity(100);
        freeTier.setReserved(0);
        freeTier.setSold(0);
        freeTier.setEnabled(true);
        freeTier = tiers.save(freeTier);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void freeCheckout_withAdsConsent_persistsFlag() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "buyer@example.com", null, /* adsConsent */ true, /* marketingOptIn */ false,
                com.imin.iminapi.model.CheckoutAttribution.NONE, null);

        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.isAdsConsent()).isTrue();
    }

    @Test
    void freeCheckout_withoutAdsConsent_defaultsFalse() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "buyer@example.com", null, /* adsConsent */ false, /* marketingOptIn */ false,
                com.imin.iminapi.model.CheckoutAttribution.NONE, null);

        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.isAdsConsent()).isFalse();
    }
}
