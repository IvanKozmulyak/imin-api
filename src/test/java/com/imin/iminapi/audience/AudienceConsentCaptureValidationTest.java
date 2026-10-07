package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Body validation on POST /audience/consent/capture and /unsubscribe over the real ConsentService. */
@IminIntegrationTest
class AudienceConsentCaptureValidationTest {

    private static final String RESERVED = "is reserved for system-recorded consent";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired MarketingOptOutRepository optOuts;
    final ObjectMapper om = new ObjectMapper();

    private AuthPrincipal principal;
    private String email;
    private UUID membershipId;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        principal = fx.principal(fx.owner(org));
        email = fx.email("capture");
        Consumer c = new Consumer();
        c.setNormalizedEmail(email);
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(org.getId());
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        membershipId = memberships.save(m).getMembershipId();
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", membershipId.toString());
        body.put("basis", "explicit");
        body.put("source", "signup-form");
        body.put("proofText", "Ticked the newsletter box on the signup form");
        return body;
    }

    static Stream<Arguments> captureRejects() {
        return Stream.of(
                Arguments.of("soft_opt_in basis", "basis", "soft_opt_in", "basis", null),
                Arguments.of("missing basis", "basis", null, "basis", null),
                Arguments.of("missing membershipId", "membershipId", null, "membershipId", null),
                Arguments.of("blank source", "source", "  ", "source", null),
                Arguments.of("overlong source", "source", "s".repeat(65), "source", null),
                Arguments.of("blank proofText", "proofText", "  ", "proofText", null),
                Arguments.of("overlong proofText", "proofText", "p".repeat(2001), "proofText", null),
                Arguments.of("reserved exact source", "source", "checkout", "source", RESERVED),
                Arguments.of("reserved prefix source", "source", "dsar_erase", "source", null));
    }

    /** A null value removes the key; no consent row is written for the real membership. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("captureRejects")
    void capture_rejectsAnUnevidencedOrSystemConsent(String name, String key, String value,
                                                     String field, String message) throws Exception {
        Map<String, Object> body = validBody();
        if (value == null) body.remove(key); else body.put(key, value);

        ResultActions r = postJson("/api/v1/audience/consent/capture", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());
        if (message != null) r.andExpect(jsonPath("$.error.fields." + field).value(message));

        assertThat(consentRecords.findByMembershipId(membershipId)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"one_click", "dsar_object"})
    void unsubscribe_rejectsAReservedSource_andLeavesTheMemberSubscribed(String source) throws Exception {
        ResultActions r = postJson("/api/v1/audience/consent/unsubscribe", unsubBody(source))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.source").exists());
        if ("one_click".equals(source)) r.andExpect(jsonPath("$.error.fields.source").value(RESERVED));

        assertThat(memberships.findByIdAndOrgId(membershipId, principal.orgId()).orElseThrow().getConsentStatus()).isEqualTo("subscribed");
        assertThat(consentRecords.findByMembershipId(membershipId)).isEmpty();
    }

    @Test
    void capture_acceptsAnOrganizerTypedSource_andWritesTheProofAndTheAuditRow() throws Exception {
        postJson("/api/v1/audience/consent/capture", validBody()).andExpect(status().isOk());

        assertThat(consentRecords.findByMembershipId(membershipId)).singleElement().satisfies(rec -> {
            assertThat(rec.getChannel()).isEqualTo("email");
            assertThat(rec.getStatus()).isEqualTo("subscribed");
            assertThat(rec.getLawfulBasis()).isEqualTo("explicit");
            assertThat(rec.getSource()).isEqualTo("signup-form");
            assertThat(rec.getProofText()).isEqualTo("Ticked the newsletter box on the signup form");
        });
        audit.assertRecorded(principal.orgId(), "CONSENT_CAPTURED", "membership", membershipId);
    }

    /** The organizer's unsubscribe is OPERATOR: unsubscribed, but no sticky opt-out for the address. */
    @Test
    void unsubscribe_acceptsAnOrganizerTypedSource_asAnOperatorUnsubscribe() throws Exception {
        postJson("/api/v1/audience/consent/unsubscribe", unsubBody("organizer-cleanup"))
                .andExpect(status().isOk());

        assertThat(memberships.findByIdAndOrgId(membershipId, principal.orgId()).orElseThrow().getConsentStatus()).isEqualTo("unsubscribed");
        List<ConsentRecord> rows = consentRecords.findByMembershipId(membershipId);
        assertThat(rows).singleElement().satisfies(rec -> {
            assertThat(rec.getStatus()).isEqualTo("unsubscribed");
            assertThat(rec.getChannel()).isEqualTo("email");
            assertThat(rec.getSource()).isEqualTo("organizer-cleanup");
        });
        assertThat(optOuts.findByEmailNormalized(email)).isEmpty();
    }

    private Map<String, Object> unsubBody(String source) {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", membershipId.toString());
        body.put("source", source);
        return body;
    }

    private ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).with(auth(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
