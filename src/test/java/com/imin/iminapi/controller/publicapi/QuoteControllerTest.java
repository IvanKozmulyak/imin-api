package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
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
@IminIntegrationTest
class QuoteControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired EventRepository eventRepository;
    @Autowired PromoCodeRepository promoCodeRepository;

    final ObjectMapper om = new ObjectMapper();

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
    }

    private Event publicLiveEvent() {
        Event e = fx.event(org, owner, EventStatus.LIVE, null);
        e.setPublishedAt(clock.instant().minusSeconds(3600));
        return eventRepository.save(e);
    }

    private TicketTier tier(Event event, int priceMinor) {
        return fx.tier(event, priceMinor, 100);
    }

    /** Unique per test, upper case as an organizer types it. */
    private static String uniqueCode(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @Test
    void quote_returns200WithTotals_andNoPromoBlock() throws Exception {
        Event e = publicLiveEvent();
        TicketTier t = tier(e, 2500);

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
        TicketTier t = tier(e, 2500);
        String code = uniqueCode("HALFOFF");
        PromoCode p = new PromoCode();
        p.setEventId(e.getId());
        p.setCode(code);
        p.setDiscountPct(50);
        p.setMaxUses(10);
        p.setEnabled(true);
        promoCodeRepository.save(p);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 2);
        body.put("promoCode", code.toLowerCase(Locale.ROOT));

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountMinor").value(2500))
                // Fee is computed on the pre-discount subtotal: 2 × (€0.99 + 5% × €25.00) = 448.
                .andExpect(jsonPath("$.feeMinor").value(448))
                .andExpect(jsonPath("$.totalMinor").value(2948))
                .andExpect(jsonPath("$.promo.applied").value(true))
                .andExpect(jsonPath("$.promo.code").value(code))
                .andExpect(jsonPath("$.promo.discountPct").value(50))
                .andExpect(jsonPath("$.promo.reason").doesNotExist());
    }

    @Test
    void quote_returns200WithRejectedPromo_whenCodeUnknown() throws Exception {
        Event e = publicLiveEvent();
        TicketTier t = tier(e, 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 1);
        body.put("promoCode", uniqueCode("NOPE"));

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
        TicketTier t = tier(e, 2500);

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
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        TicketTier t = tier(e, 2500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierId", t.getId().toString());
        body.put("quantity", 1);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
