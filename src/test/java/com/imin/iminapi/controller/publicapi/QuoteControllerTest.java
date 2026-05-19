package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for {@code POST /api/v1/public/events/{id}/quote}.
 *
 * <p>Hits the full Spring stack (security, controller, JPA) without auth so the
 * SecurityConfig wildcard for {@code /quote} is also covered.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class QuoteControllerTest {

    @Autowired MockMvc mvc;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired TicketTierRepository ticketTierRepository;
    @Autowired PromoCodeRepository promoCodeRepository;
    @Autowired NotifySubscriptionRepository notifySubscriptionRepository;

    final ObjectMapper om = new ObjectMapper();

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        notifySubscriptionRepository.deleteAll();
        promoCodeRepository.deleteAll();
        ticketTierRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new Organization();
        org.setName("Quote Ctrl Org");
        org.setSlug("quote-ctrl-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("quote-ctrl@example.com");
        org.setCountry("DE");
        org = organizationRepository.save(org);

        owner = new User();
        owner.setEmail("quote-ctrl-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = userRepository.save(owner);
    }

    @AfterEach
    void tearDown() {
        notifySubscriptionRepository.deleteAll();
        promoCodeRepository.deleteAll();
        ticketTierRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private Event publicLiveEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Q Event");
        e.setSlug("q-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepository.save(e);
    }

    private TicketTier tier(UUID eventId, int priceMinor) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(priceMinor);
        t.setQuantity(100);
        t.setEnabled(true);
        return ticketTierRepository.save(t);
    }

    @Test
    void quote_returns200WithTotals_andNoPromoBlock() throws Exception {
        Event e = publicLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 2);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.unitPriceMinor").value(2500))
                .andExpect(jsonPath("$.subtotalMinor").value(5000))
                .andExpect(jsonPath("$.discountMinor").value(0))
                // 2 tickets × (€0.99 + 5% × €25.00) = 2 × 224 = 448 minor.
                .andExpect(jsonPath("$.feeMinor").value(448))
                .andExpect(jsonPath("$.totalMinor").value(5448))
                .andExpect(jsonPath("$.promo").doesNotExist());
    }

    @Test
    void quote_returns200WithAppliedPromo_whenCodeValid() throws Exception {
        Event e = publicLiveEvent();
        TicketTier t = tier(e.getId(), 2500);
        PromoCode p = new PromoCode();
        p.setEventId(e.getId());
        p.setCode("HALFOFF");
        p.setDiscountPct(50);
        p.setMaxUses(10);
        p.setEnabled(true);
        promoCodeRepository.save(p);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 2);
        body.put("promoCode", "halfoff");

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountMinor").value(2500))
                // Fee is computed on the pre-discount subtotal: 2 × (€0.99 + 5% × €25.00) = 448.
                .andExpect(jsonPath("$.feeMinor").value(448))
                .andExpect(jsonPath("$.totalMinor").value(2948))
                .andExpect(jsonPath("$.promo.applied").value(true))
                .andExpect(jsonPath("$.promo.code").value("HALFOFF"))
                .andExpect(jsonPath("$.promo.discountPct").value(50))
                .andExpect(jsonPath("$.promo.reason").doesNotExist());
    }

    @Test
    void quote_returns200WithRejectedPromo_whenCodeUnknown() throws Exception {
        Event e = publicLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 1);
        body.put("promoCode", "NOPE");

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountMinor").value(0))
                // 1 ticket × (€0.99 + 5% × €25.00) = 224 minor fee.
                .andExpect(jsonPath("$.feeMinor").value(224))
                .andExpect(jsonPath("$.totalMinor").value(2724))
                .andExpect(jsonPath("$.promo.applied").value(false))
                .andExpect(jsonPath("$.promo.reason").value("Invalid code"));
    }

    @Test
    void quote_returns400_onQuantityZero() throws Exception {
        Event e = publicLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 0);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.quantity").exists());
    }

    @Test
    void quote_returns404_onDraftEvent() throws Exception {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Draft");
        e.setSlug("draft-q-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e = eventRepository.save(e);
        TicketTier t = tier(e.getId(), 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 1);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void quote_endpointReachableWithoutAuth() throws Exception {
        // Sanity check: SecurityConfig permits this POST. Use the body of the
        // happy path but only assert status here.
        Event e = publicLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 1);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
    }
}
