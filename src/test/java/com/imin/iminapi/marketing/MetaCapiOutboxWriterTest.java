package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.marketing.service.MetaCapiOutboxWriter;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class MetaCapiOutboxWriterTest {

    @Autowired MetaCapiOutboxWriter writer;
    @Autowired MetaCapiEventRepository capiRepo;
    @Autowired MetaPixelConnectionRepository connRepo;
    @Autowired OrderRepository orders;

    private MetaPixelConnection orgConnection(UUID orgId) {
        MetaPixelConnection c = new MetaPixelConnection();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setEventId(null);
        c.setPixelId("1234567890");
        c.setCapiAccessTokenEnc("enc");
        return connRepo.save(c);
    }

    @Autowired com.imin.iminapi.repository.EventRepository events;
    @Autowired com.imin.iminapi.repository.OrganizationRepository organizations;
    @Autowired com.imin.iminapi.repository.UserRepository users;

    /**
     * Seeds a real, FK-valid org so both {@code events.org_id → organizations(id)}
     * (V6) and the writer's connection/order lookups resolve. organizations requires
     * NOT NULL name + UNIQUE slug + contact_email + country (V5) with no defaults —
     * copied from the proven setup in PublicEventServiceTest.setUp() (lines 59-64).
     * Returns the generated org id (do NOT pre-set the id; @GeneratedValue populates it).
     */
    private UUID persistedOrg() {
        com.imin.iminapi.model.Organization org = new com.imin.iminapi.model.Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8)); // UNIQUE
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        return organizations.save(org).getId();
    }

    /** events.created_by is a NOT NULL FK to users(id) (V6), so a real user is required. */
    private UUID persistedUser(UUID orgId) {
        com.imin.iminapi.model.User u = new com.imin.iminapi.model.User();
        u.setEmail("owner-" + UUID.randomUUID() + "@example.com"); // setEmail derives email_lower
        u.setOrgId(orgId);
        u.setRole(com.imin.iminapi.model.UserRole.OWNER);
        return users.save(u).getId();
    }

    /**
     * orders.event_id is a NOT NULL FK to events(id) (V24), and events itself has NOT
     * NULL FKs org_id → organizations(id) and created_by → users(id) (V6). Build a
     * minimal valid LIVE event exactly as PublicEventServiceTest.publishedLiveEvent()
     * (lines 79-90) does: orgId + name + UNIQUE slug + status=LIVE + createdBy + currency.
     * All other NOT NULL event columns (type, venue_street/city/postal_code, description,
     * revenue_minor) carry Java-level defaults on the Event entity, so they need no setter.
     * Do NOT pre-set the id (@GeneratedValue(strategy=UUID)).
     */
    private UUID persistedEventId(UUID orgId, UUID createdBy) {
        com.imin.iminapi.model.Event e = new com.imin.iminapi.model.Event();
        e.setOrgId(orgId);
        e.setName("Great Event");
        e.setSlug("great-event-" + UUID.randomUUID().toString().substring(0, 8)); // UNIQUE(org_id, slug)
        e.setStatus(com.imin.iminapi.model.EventStatus.LIVE);
        e.setCreatedBy(createdBy); // FK → users(id)
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private Order paidOrder(UUID orgId, boolean adsConsent, String currency) {
        UUID createdBy = persistedUser(orgId);
        Order o = new Order();
        o.setToken("tok-" + UUID.randomUUID());
        o.setEventId(persistedEventId(orgId, createdBy)); // FK-valid event under this org
        o.setOrgId(orgId);
        o.setEmail("Buyer@Example.com "); // deliberately mixed-case + trailing space
        o.setTotalMinor(4200L);
        o.setCurrency(currency);
        o.setPaymentMethod("stripe");
        o.setAdsConsent(adsConsent);
        return orders.save(o);
    }

    @Test
    void insertsOutboxRowWhenConsentAndConnectionPresent() {
        UUID orgId = persistedOrg(); // real org row — events.org_id FK requires it
        orgConnection(orgId);
        Order o = paidOrder(orgId, true, "uah");

        writer.writeForOrder(o.getId());

        List<MetaCapiEvent> all = capiRepo.findAll().stream()
                .filter(e -> e.getOrderId().equals(o.getId())).toList();
        assertThat(all).hasSize(1);
        MetaCapiEvent e = all.get(0);
        assertThat(e.getCurrency()).isEqualTo("uah");           // from orders.currency, not defaulted
        assertThat(e.getValueMinor()).isEqualTo(4200L);
        assertThat(e.getPixelId()).isEqualTo("1234567890");
        assertThat(e.getOrderToken()).isEqualTo(o.getToken());  // dedup key = order token, copied from the order
        // sha256 of the NORMALIZED email (lower+trim) — 64 hex chars.
        assertThat(e.getEmailSha256()).hasSize(64)
                .isEqualTo(sha256Hex("buyer@example.com"));
        assertThat(e.getStatus()).isEqualTo(MetaCapiEvent.STATUS_PENDING);
    }

    @Test
    void skipsWhenNoAdsConsent() {
        UUID orgId = persistedOrg();
        orgConnection(orgId);
        Order o = paidOrder(orgId, false, "eur");
        writer.writeForOrder(o.getId());
        assertThat(capiRepo.existsByOrderId(o.getId())).isFalse();
    }

    @Test
    void skipsWhenNoConnection() {
        UUID orgId = persistedOrg(); // real org, but no pixel connection saved
        Order o = paidOrder(orgId, true, "eur");
        writer.writeForOrder(o.getId());
        assertThat(capiRepo.existsByOrderId(o.getId())).isFalse();
    }

    @Test
    void skipsWhenCurrencyBlank() {
        UUID orgId = persistedOrg();
        orgConnection(orgId);
        Order o = paidOrder(orgId, true, "  ");
        writer.writeForOrder(o.getId());
        // Currency guard: a blank currency must NOT produce a silently-mis-valued row.
        assertThat(capiRepo.existsByOrderId(o.getId())).isFalse();
    }

    @Test
    void isIdempotentPerOrder() {
        UUID orgId = persistedOrg();
        orgConnection(orgId);
        Order o = paidOrder(orgId, true, "eur");
        writer.writeForOrder(o.getId());
        writer.writeForOrder(o.getId()); // second call must not duplicate
        long count = capiRepo.findAll().stream().filter(e -> e.getOrderId().equals(o.getId())).count();
        assertThat(count).isEqualTo(1);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
