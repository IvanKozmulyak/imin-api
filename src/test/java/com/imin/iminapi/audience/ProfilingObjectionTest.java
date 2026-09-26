package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.sms.SmsStopService;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import com.imin.iminapi.dto.publicapi.SmsConsentRequest;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.audience.SmsConsentService;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.Optional;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code memberships.objected_profiling}: set by the person's own opt-out (Art.21 objection to
 * emails and profiling), cleared only by a fresh consent from that person.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class ProfilingObjectionTest {

    @Autowired MockMvc mvc;
    @Autowired UnsubscribeTokenService tokens;
    @Autowired ConsentService consentService;
    @Autowired DsarService dsarService;
    @Autowired SmsStopService smsStopService;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired AudienceImportService importService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;

    @MockitoBean AuditLogger auditLogger;

    // ── set by the data subject ─────────────────────────────────────────────

    @Test
    void dsarObject_setsObjection_andAppendsTheConsentRecord() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("dsar"));

        dsarService.object(orgId, mid, organizerOf(orgId));

        Membership m = load(orgId, mid);
        assertThat(m.isObjectedProfiling()).isTrue();
        assertThat(m.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(recordsOf(mid))
                .anySatisfy(r -> {
                    assertThat(r.getStatus()).isEqualTo("unsubscribed");
                    assertThat(r.getSource()).isEqualTo("dsar_object");
                });
    }

    @Test
    void dsarEraseRequest_setsObjectionForTheGraceWindow() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("erase"));

        dsarService.requestErase(orgId, mid, organizerOf(orgId));

        Membership m = load(orgId, mid);
        assertThat(m.getStatus()).isEqualTo("erase_pending");
        assertThat(m.isObjectedProfiling()).isTrue();
    }

    @Test
    void oneClickPost_setsObjection() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("oneclick"));
        String token = tokens.sign(orgId, mid, UUID.randomUUID(), "email");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token)).andExpect(status().isOk());

        assertThat(load(orgId, mid).isObjectedProfiling()).isTrue();
    }

    @Test
    void smsStop_setsObjection() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("stop"));
        String phone = uniquePhone();
        Membership m = load(orgId, mid);
        m.setPhoneE164(phone);
        memberships.save(m);

        smsStopService.suppressPhone(phone, "sms_stop_reply");

        assertThat(load(orgId, mid).isObjectedProfiling()).isTrue();
    }

    // ── not set by anyone else ──────────────────────────────────────────────

    @Test
    void operatorUnsubscribe_leavesObjectionFalse() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("operator"));

        consentService.unsubscribe(orgId, mid, "one_click", ConsentOrigin.OPERATOR, organizerOf(orgId));

        assertThat(load(orgId, mid).getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(load(orgId, mid).isObjectedProfiling()).isFalse();
    }

    @Test
    void retentionUnsubscribe_leavesObjectionFalse() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("retention"));

        consentService.unsubscribe(orgId, mid, "retention_3y", ConsentOrigin.OPERATOR, null);

        assertThat(load(orgId, mid).isObjectedProfiling()).isFalse();
    }

    @Test
    void globalPreferenceUnsubscribe_leavesObjectionFalse() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("global"));

        consentService.unsubscribe(orgId, mid, "master_toggle", ConsentOrigin.DATA_SUBJECT_GLOBAL, null);

        assertThat(load(orgId, mid).isObjectedProfiling()).isFalse();
    }

    @Test
    void unsubscribeWithoutOrigin_leavesObjectionFalse() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("noorigin"));

        consentService.unsubscribe(orgId, mid, "unknown", null, null);

        assertThat(load(orgId, mid).isObjectedProfiling()).isFalse();
    }

    // ── cleared only by the person's own new consent ────────────────────────

    @Test
    void newCheckoutConsentByThePerson_clearsObjection() {
        UUID orgId = UUID.randomUUID();
        String address = email("checkout");
        UUID mid = objectedButEmailSubscribed(orgId, address);

        orderProjector.upsertMembership(orgId, address, null, null, false,
                true, UUID.randomUUID(), "Send me news about this organiser's events");

        assertThat(load(orgId, mid).isObjectedProfiling()).isFalse();
    }

    @Test
    void smsOptInOnTheOrderPage_keepsObjection() {
        UUID orgId = UUID.randomUUID();
        String address = email("smsconsent");
        UUID mid = objectedButEmailSubscribed(orgId, address);
        Order order = new Order();
        order.setOrgId(orgId);
        order.setEmail(address);
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByToken("tok")).thenReturn(Optional.of(order));
        SmsConsentService sms = new SmsConsentService(orders, consumers, memberships, consentService);

        sms.submit("tok", new SmsConsentRequest(uniquePhone(), true, "Text me about this organiser's events"));

        Membership m = load(orgId, mid);
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.isObjectedProfiling()).isTrue();
    }

    @Test
    void importRowForAnObjectedMember_keepsObjection() {
        UUID orgId = UUID.randomUUID();
        String address = email("import");
        UUID mid = objectedButEmailSubscribed(orgId, address);

        importService.importContacts(
                List.of(new CsvContactParser.RawContact(2, address, null, null, "shotgun", "2026-09-01",
                        null, null, "opted_in", "proof-2")),
                false, organizerOf(orgId));

        assertThat(recordsOf(mid)).anySatisfy(r -> assertThat(r.getSource()).isEqualTo("organizer_import_row"));
        assertThat(load(orgId, mid).isObjectedProfiling()).isTrue();
    }

    @Test
    void organizerConsentCaptureEndpoint_keepsObjection() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mid = objectedButEmailSubscribed(orgId, email("endpoint"));
        consentService.unsubscribe(orgId, mid, "tidy", ConsentOrigin.OPERATOR, organizerOf(orgId));
        AuthPrincipal owner = organizerOf(orgId);

        mvc.perform(post("/api/v1/audience/consent/capture")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                owner, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + mid + "\",\"basis\":\"explicit\","
                                + "\"source\":\"signup_form\",\"proofText\":\"Ticked the box\"}"))
                .andExpect(status().isOk());

        assertThat(load(orgId, mid).getConsentStatus()).isEqualTo("subscribed");
        assertThat(load(orgId, mid).isObjectedProfiling()).isTrue();
    }

    @Test
    void organizerCapture_keepsObjection() {
        UUID orgId = UUID.randomUUID();
        UUID mid = objectedButEmailSubscribed(orgId, email("organizer"));

        consentService.capture(orgId, mid, "explicit", "audience_ui", "proof", "email", organizerOf(orgId));

        assertThat(load(orgId, mid).getConsentStatus()).isEqualTo("subscribed");
        assertThat(load(orgId, mid).isObjectedProfiling()).isTrue();
    }

    @Test
    void globalPreferenceCapture_keepsObjection() {
        UUID orgId = UUID.randomUUID();
        UUID mid = objectedButEmailSubscribed(orgId, email("globalon"));

        consentService.capture(orgId, mid, "explicit", "buyer_preferences", "proof", "email",
                null, null, ConsentOrigin.DATA_SUBJECT_GLOBAL, null);

        assertThat(load(orgId, mid).isObjectedProfiling()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** SMS STOP objects while the email consent stays subscribed, so a later email capture can run. */
    private UUID objectedButEmailSubscribed(UUID orgId, String address) {
        UUID mid = seedMembership(orgId, address);
        consentService.unsubscribe(orgId, mid, "sms_stop_reply", "sms", ConsentOrigin.DATA_SUBJECT, null);
        Membership m = load(orgId, mid);
        assertThat(m.isObjectedProfiling()).isTrue();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        return mid;
    }

    private UUID seedMembership(UUID orgId, String normalizedEmail) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(normalizedEmail);
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    private Membership load(UUID orgId, UUID mid) {
        return memberships.findByIdAndOrgId(mid, orgId).orElseThrow();
    }

    private List<ConsentRecord> recordsOf(UUID mid) {
        return consentRecords.findByMembershipId(mid);
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniquePhone() {
        return "+3538" + (1_000_000 + (int) (Math.random() * 8_999_999));
    }

    private static AuthPrincipal organizerOf(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }
}
