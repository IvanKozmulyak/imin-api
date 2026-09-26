package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.AudienceControllerWebTest.WithOrgA;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Body validation on POST /audience/consent/capture: only an evidenced explicit consent is accepted. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AudienceConsentCaptureValidationTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    @MockitoBean ConsentService consentService;
    @MockitoBean AudienceService audienceService;
    @MockitoBean AudienceMetricsService metricsService;
    @MockitoBean SegmentService segmentService;
    @MockitoBean SendGateService sendGateService;
    @MockitoBean DsarService dsarService;
    @MockitoBean AuditLogger auditLogger;

    private Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", UUID.randomUUID().toString());
        body.put("basis", "explicit");
        body.put("source", "signup-form");
        body.put("proofText", "Ticked the newsletter box on the signup form");
        return body;
    }

    private void expectRejected(Map<String, Object> body, String field) throws Exception {
        mvc.perform(post("/api/v1/audience/consent/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());
        verify(consentService, never()).capture(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithOrgA
    void rejectsSoftOptInBasis() throws Exception {
        Map<String, Object> body = validBody();
        body.put("basis", "soft_opt_in");
        expectRejected(body, "basis");
    }

    @Test
    @WithOrgA
    void rejectsMissingBasis() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("basis");
        expectRejected(body, "basis");
    }

    @Test
    @WithOrgA
    void rejectsMissingMembershipId() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("membershipId");
        expectRejected(body, "membershipId");
    }

    @Test
    @WithOrgA
    void rejectsBlankSource() throws Exception {
        Map<String, Object> body = validBody();
        body.put("source", "  ");
        expectRejected(body, "source");
    }

    @Test
    @WithOrgA
    void rejectsOverlongSource() throws Exception {
        Map<String, Object> body = validBody();
        body.put("source", "s".repeat(65));
        expectRejected(body, "source");
    }

    @Test
    @WithOrgA
    void rejectsBlankProofText() throws Exception {
        Map<String, Object> body = validBody();
        body.put("proofText", "  ");
        expectRejected(body, "proofText");
    }

    @Test
    @WithOrgA
    void rejectsOverlongProofText() throws Exception {
        Map<String, Object> body = validBody();
        body.put("proofText", "p".repeat(2001));
        expectRejected(body, "proofText");
    }

    @Test
    @WithOrgA
    void rejectsReservedExactSource() throws Exception {
        Map<String, Object> body = validBody();
        body.put("source", "checkout");
        mvc.perform(post("/api/v1/audience/consent/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.source").value("is reserved for system-recorded consent"));
        verify(consentService, never()).capture(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithOrgA
    void rejectsReservedPrefixSource() throws Exception {
        Map<String, Object> body = validBody();
        body.put("source", "dsar_erase");
        expectRejected(body, "source");
    }

    @Test
    @WithOrgA
    void acceptsOrganizerTypedSource() throws Exception {
        Map<String, Object> body = validBody();
        mvc.perform(post("/api/v1/audience/consent/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
        verify(consentService).capture(any(), eq(UUID.fromString((String) body.get("membershipId"))),
                eq("explicit"), eq("signup-form"), eq("Ticked the newsletter box on the signup form"),
                eq("email"), any());
    }

    private Map<String, Object> unsubBody(String source) {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", UUID.randomUUID().toString());
        body.put("source", source);
        return body;
    }

    @Test
    @WithOrgA
    void unsubscribe_rejectsReservedExactSource() throws Exception {
        mvc.perform(post("/api/v1/audience/consent/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(unsubBody("one_click"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.source").value("is reserved for system-recorded consent"));
        verify(consentService, never()).unsubscribe(any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithOrgA
    void unsubscribe_rejectsReservedPrefixSource() throws Exception {
        mvc.perform(post("/api/v1/audience/consent/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(unsubBody("dsar_object"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.source").exists());
        verify(consentService, never()).unsubscribe(any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithOrgA
    void unsubscribe_acceptsOrganizerTypedSource() throws Exception {
        Map<String, Object> body = unsubBody("organizer-cleanup");
        mvc.perform(post("/api/v1/audience/consent/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
        verify(consentService).unsubscribe(any(), eq(UUID.fromString((String) body.get("membershipId"))),
                eq("organizer-cleanup"), eq("email"), eq(ConsentOrigin.OPERATOR), any());
    }
}
