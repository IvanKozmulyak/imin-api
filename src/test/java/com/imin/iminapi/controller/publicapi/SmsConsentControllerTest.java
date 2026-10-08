package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E for POST /api/v1/public/orders/{token}/sms-consent (spec §4).
 * Real service on Postgres so the consent-proof + membership writes are exercised.
 * Consumers are keyed by address across orgs, so every buyer address is unique to its test.
 */
@IminIntegrationTest
class SmsConsentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired OrderRepository orders;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentRecordRepository consentRecords;

    final ObjectMapper om = new ObjectMapper();

    /** The SMS endpoint only reads the order by token; the event and org back its foreign keys. */
    private Order seedOrder(String email) {
        Organization org = fx.org();
        Event ev = fx.event(org, fx.owner(org), EventStatus.LIVE, null);
        Order o = fx.order(ev, email);
        o.setTotalMinor(0L);
        o.setPaymentMethod("free");
        return orders.save(o);
    }

    @Test
    void optIn_persistsPhoneOptInFlagConsentAndMembership() throws Exception {
        String buyer = fx.email("buyer");
        Order o = seedOrder(buyer);

        mvc.perform(post("/api/v1/public/orders/" + o.getToken() + "/sms-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "phone", "+380 67 123 45 67",
                                "optIn", true,
                                "proofText", "Text me about this organizer's events"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true));

        Order reloaded = orders.findByToken(o.getToken()).orElseThrow();
        assertThat(reloaded.getBuyerPhone()).isEqualTo("+380671234567");
        assertThat(reloaded.isSmsMarketingOptIn()).isTrue();

        // Marker repos expose no findAll; read back via the declared query
        // (consumer is keyed by normalized email inside the endpoint).
        UUID consumerId = consumers.findByNormalizedEmail(buyer).orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(o.getOrgId(), consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isEqualTo("+380671234567");
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getSmsConsentBasis()).isEqualTo("explicit");

        assertThat(consentRecords.findByMembershipId(m.getMembershipId()))
                .anySatisfy(r -> {
                    assertThat(r.getChannel()).isEqualTo("sms");
                    assertThat(r.getLawfulBasis()).isEqualTo("explicit");
                    assertThat(r.getSource()).isEqualTo("order_confirmation");
                    assertThat(r.getProofText()).isEqualTo("Text me about this organizer's events");
                });
    }

    @Test
    void uncheckedOptIn_writesNoConsentButReturns200() throws Exception {
        String buyer = fx.email("buyer2");
        Order o = seedOrder(buyer);

        mvc.perform(post("/api/v1/public/orders/" + o.getToken() + "/sms-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "phone", "+380671234567",
                                "optIn", false,
                                "proofText", "Text me about this organizer's events"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false));

        Order reloaded = orders.findByToken(o.getToken()).orElseThrow();
        assertThat(reloaded.isSmsMarketingOptIn()).isFalse();
        // No SMS consent row written (unchecked = no consent, §4/§7).
        consumers.findByNormalizedEmail(buyer)
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(o.getOrgId(), c.getConsumerId()))
                .ifPresent(m -> assertThat(m.getSmsConsentStatus()).isEqualTo("never"));
    }

    @Test
    void optInWithInvalidPhone_returns400InvalidRequest_noWrites() throws Exception {
        String buyer = fx.email("buyer3");
        Order o = seedOrder(buyer);

        mvc.perform(post("/api/v1/public/orders/" + o.getToken() + "/sms-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "phone", "not-a-phone",
                                "optIn", true,
                                "proofText", "proof"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.phone").exists());

        assertThat(orders.findByToken(o.getToken()).orElseThrow().isSmsMarketingOptIn()).isFalse();
        // Invalid input 400s before any write — prove no consumer was projected.
        assertThat(consumers.findByNormalizedEmail(buyer)).isEmpty();
    }

    @Test
    void optInWithMissingPhone_returns400() throws Exception {
        String buyer = fx.email("buyer4");
        Order o = seedOrder(buyer);

        mvc.perform(post("/api/v1/public/orders/" + o.getToken() + "/sms-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("optIn", true, "proofText", "proof"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.phone").exists());
    }

    @Test
    void unknownToken_returns404LeakSafe() throws Exception {
        mvc.perform(post("/api/v1/public/orders/tok-does-not-exist/sms-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "phone", "+380671234567", "optIn", true, "proofText", "proof"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
