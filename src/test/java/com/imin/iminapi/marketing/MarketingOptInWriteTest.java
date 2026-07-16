package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.service.event.FreeCheckoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V61 email-marketing opt-in: the buy-page checkbox flows to orders.marketing_opt_in
 * (free path here; the paid path is metadata-mapped like ads_consent) and the
 * AudienceOrderProjector turns it into a channel='email' explicit consent —
 * the membership becomes SendGate-sendable.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MarketingOptInWriteTest {

    @Autowired FreeCheckoutService freeCheckout;
    @Autowired AudienceOrderProjector projector;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired DataSource dataSource;

    private Event event;
    private TicketTier freeTier;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        cleanUp();

        Organization org = new Organization();
        org.setName("OptIn Org");
        org.setSlug("optin-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("optin@example.com");
        org.setCountry("DE");
        org = orgs.save(org);
        orgId = org.getId();

        User owner = new User();
        owner.setEmail("optin-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(orgId);
        event.setName("OptIn Fest");
        event.setSlug("optin-event-" + UUID.randomUUID().toString().substring(0, 8));
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
        // Audience rows use marker repositories without deleteAll — JDBC teardown (audience convention).
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute("delete from consent_records");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void freeCheckout_persistsMarketingOptInFlag() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "optin-buyer@example.com", null, false, /* marketingOptIn */ true);
        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.isMarketingOptIn()).isTrue();
    }

    @Test
    void projector_withOptIn_capturesExplicitEmailConsent() {
        projector.upsertMembership(orgId, "optin-buyer@example.com", "optin-buyer@example.com",
                null, false, /* emailOptIn */ true, UUID.randomUUID());

        var consumer = consumers.findByNormalizedEmail("optin-buyer@example.com").orElseThrow();
        var m = memberships.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId()).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
    }

    @Test
    void projector_withoutOptIn_leavesConsentUntouched() {
        projector.upsertMembership(orgId, "no-optin@example.com", "no-optin@example.com",
                null, false, false, null);

        var consumer = consumers.findByNormalizedEmail("no-optin@example.com").orElseThrow();
        var m = memberships.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId()).orElseThrow();
        assertThat(m.getConsentStatus()).isNotEqualTo("subscribed");
    }
}
