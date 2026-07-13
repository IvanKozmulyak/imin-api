package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * Real service + H2 JPA so the consent-proof + membership writes are exercised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class SmsConsentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    final ObjectMapper om = new ObjectMapper();

    /**
     * Seeds a backing Organization + User + Event so the order-level
     * {@code orders.event_id} FK holds on H2 (V24 references events(id)); the
     * SMS endpoint itself only reads the order by token, but the row must persist.
     */
    private Order seedOrder(String email) {
        Organization org = new Organization();
        org.setName("SMS Test Org");
        org.setSlug("sms-test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("sms@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("sms-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event ev = new Event();
        ev.setOrgId(org.getId());
        ev.setName("SMS Test Event");
        ev.setSlug("sms-test-event-" + UUID.randomUUID().toString().substring(0, 8));
        ev.setVisibility(EventVisibility.PUBLIC);
        ev.setStatus(EventStatus.LIVE);
        ev.setCurrency("EUR");
        ev.setCreatedBy(owner.getId());
        ev = events.save(ev);

        Order o = new Order();
        o.setToken("tok-" + UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(ev.getId());
        o.setOrgId(org.getId());
        o.setEmail(email);
        o.setTotalMinor(0L);
        o.setCurrency("EUR");
        o.setPaymentMethod("free");
        return orders.save(o);
    }

    @AfterEach
    void tearDown() {
        // Marker Repository<> interfaces expose no deleteAll — wipe via JDBC
        // (the pattern the audience suite uses, AudiencePostgresTest:343-347).
        jdbc.update("DELETE FROM consent_records");
        jdbc.update("DELETE FROM memberships");
        jdbc.update("DELETE FROM consumers");
        orders.deleteAll();
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM organizations");
    }

    @Test
    void optIn_persistsPhoneOptInFlagConsentAndMembership() throws Exception {
        Order o = seedOrder("buyer@example.com");

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
        UUID consumerId = consumers.findByNormalizedEmail("buyer@example.com").orElseThrow().getConsumerId();
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
        Order o = seedOrder("buyer2@example.com");

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
        consumers.findByNormalizedEmail("buyer2@example.com")
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(o.getOrgId(), c.getConsumerId()))
                .ifPresent(m -> assertThat(m.getSmsConsentStatus()).isEqualTo("never"));
    }

    @Test
    void optInWithInvalidPhone_returns400InvalidRequest_noWrites() throws Exception {
        Order o = seedOrder("buyer3@example.com");

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
        assertThat(consumers.findByNormalizedEmail("buyer3@example.com")).isEmpty();
    }

    @Test
    void optInWithMissingPhone_returns400() throws Exception {
        Order o = seedOrder("buyer4@example.com");

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

    @Test
    void endpointReachableWithoutAuth() throws Exception {
        Order o = seedOrder("buyer5@example.com");
        mvc.perform(post("/api/v1/public/orders/" + o.getToken() + "/sms-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("optIn", false))))
                .andExpect(status().isOk());
    }
}
