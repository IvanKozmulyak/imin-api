package com.imin.iminapi.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class CampaignControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    @MockitoBean CampaignService service;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    static final UUID CAMP = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubOrganizer {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubOrganizer> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubOrganizer ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private CampaignDto sampleDto() {
        Instant now = Instant.parse("2026-07-11T10:00:00Z");
        return new CampaignDto(CAMP, ORG, "email", "Launch night", "draft",
                null, null, "manual", null, null, null, null, null,
                "Subj", "Pre", "body", "classic", now, now,
                // revMinor: null — a draft campaign has never sent, so no revenue is attributable.
                null);
    }

    private com.imin.iminapi.marketing.dto.CampaignDetailDto sampleDetailDto() {
        Instant now = Instant.parse("2026-07-11T10:00:00Z");
        var stats = new com.imin.iminapi.marketing.dto.CampaignStatsDto(10, 9, 3, 1, 0, 0, 0, 2);
        return new com.imin.iminapi.marketing.dto.CampaignDetailDto(CAMP, ORG, "email", "Launch night", "draft",
                null, null, "manual", null, null, null, null, null,
                "Subj", "Pre", "body", "classic", now, now, stats);
    }

    @Test
    @WithStubOrganizer
    void create_returns_201_with_the_draft() throws Exception {
        when(service.create(any(), any())).thenReturn(sampleDto());
        mvc.perform(post("/api/v1/marketing/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("channel", "email", "name", "Launch night"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.channel").value("email"));
    }

    @Test
    @WithStubOrganizer
    void get_returns_the_detail() throws Exception {
        when(service.detailWithStats(any(), eq(CAMP))).thenReturn(sampleDetailDto());
        mvc.perform(get("/api/v1/marketing/campaigns/{id}", CAMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Launch night"))
                .andExpect(jsonPath("$.stats.opened").value(3));
    }

    @Test
    @WithStubOrganizer
    void list_returns_a_page() throws Exception {
        when(service.list(any(), eq("email"), eq(null), eq(0), any(Integer.class)))
                .thenReturn(List.of());
        mvc.perform(get("/api/v1/marketing/campaigns").param("channel", "email"))
                .andExpect(status().isOk());
    }

    /**
     * mkt-edge-9 (P2): `page` went into PageRequest.of unclamped (only `size` was guarded),
     * and PageRequest.of(-1, 50) throws IllegalArgumentException — for which
     * GlobalExceptionHandler has no handler, so ?page=-1 answered 500 INTERNAL and logged an
     * "Unhandled exception" on both the campaign list and the recipient log.
     */
    @Test
    @WithStubOrganizer
    void negativePageIsClampedNotA500() throws Exception {
        when(service.list(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(service.listRecipients(eq(CAMP), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.imin.iminapi.marketing.dto.RecipientPage(
                        List.of(), 0, 50, 0L,
                        new com.imin.iminapi.marketing.dto.RecipientCounts(0, 0, 0, 0, 0, 0, 0, 0)));

        mvc.perform(get("/api/v1/marketing/campaigns").param("page", "-1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/marketing/campaigns/{id}/recipients", CAMP).param("page", "-3"))
                .andExpect(status().isOk());

        verify(service).list(any(), any(), any(), eq(0), org.mockito.ArgumentMatchers.anyInt());
        verify(service).listRecipients(eq(CAMP), any(), any(), any(), eq(0),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @WithStubOrganizer
    void patch_returns_the_updated_draft() throws Exception {
        when(service.patch(any(), eq(CAMP), any())).thenReturn(sampleDto());
        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", CAMP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("subject", "New"))))
                .andExpect(status().isOk());
    }

    /**
     * mkt-edge-8 (P2): the wire half. The composer PATCHes {name, segmentId: null,
     * eventId: null} when the organizer leaves the Audience step, and an explicit null has to
     * reach the service as "clear it" while an absent field still reaches it as "unchanged".
     */
    @Test
    @WithStubOrganizer
    void patch_distinguishesExplicitNullFromAnAbsentField() throws Exception {
        when(service.patch(any(), eq(CAMP), any())).thenReturn(sampleDto());
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest.class);

        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", CAMP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Step 0\",\"segmentId\":null,\"eventId\":null}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", CAMP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Step 0\"}"))
                .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(2)).patch(any(), eq(CAMP), captor.capture());
        var explicitNull = captor.getAllValues().get(0);
        var absent = captor.getAllValues().get(1);
        org.assertj.core.api.Assertions.assertThat(explicitNull.segmentId())
                .isEqualTo(com.imin.iminapi.marketing.dto.PatchableUuid.NULL);
        org.assertj.core.api.Assertions.assertThat(explicitNull.eventId())
                .isEqualTo(com.imin.iminapi.marketing.dto.PatchableUuid.NULL);
        org.assertj.core.api.Assertions.assertThat(absent.segmentId()).isNull();
        org.assertj.core.api.Assertions.assertThat(absent.eventId()).isNull();
    }

    @Test
    @WithStubOrganizer
    void patch_carriesASuppliedSegmentIdThrough() throws Exception {
        when(service.patch(any(), eq(CAMP), any())).thenReturn(sampleDto());
        UUID seg = UUID.randomUUID();
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest.class);

        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", CAMP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentId\":\"" + seg + "\"}"))
                .andExpect(status().isOk());

        verify(service).patch(any(), eq(CAMP), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().segmentId().value())
                .isEqualTo(seg);
    }

    @Test
    @WithStubOrganizer
    void duplicate_returns_201() throws Exception {
        when(service.duplicate(any(), eq(CAMP))).thenReturn(sampleDto());
        mvc.perform(post("/api/v1/marketing/campaigns/{id}/duplicate", CAMP))
                .andExpect(status().isCreated());
    }

    @Test
    @WithStubOrganizer
    void preview_audience_returns_counts() throws Exception {
        when(service.previewAudience(any(), eq(CAMP)))
                .thenReturn(new PreviewAudienceResponse(12,
                        new PreviewAudienceResponse.Excluded(1, 2, 0, 3, 0, 4)));
        mvc.perform(post("/api/v1/marketing/campaigns/{id}/preview-audience", CAMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendable").value(12))
                .andExpect(jsonPath("$.excluded.unsubscribed").value(2))
                .andExpect(jsonPath("$.excluded.deliverabilitySuppressed").value(3))
                // spec §4 sixth exclusion class — must reach the wire contract the FE reads
                .andExpect(jsonPath("$.excluded.noEmail").value(4));
    }

    @Test
    @WithStubOrganizer
    void test_send_returns_204_and_delegates() throws Exception {
        mvc.perform(post("/api/v1/marketing/campaigns/{id}/test-send", CAMP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());
        verify(service).testSend(any(), eq(CAMP), eq(null));
    }

    @Test
    @WithStubOrganizer
    void delete_returns_204_and_delegates() throws Exception {
        mvc.perform(delete("/api/v1/marketing/campaigns/{id}", CAMP))
                .andExpect(status().isNoContent());
        verify(service).delete(any(), eq(CAMP));
    }

    @Test
    @WithStubOrganizer
    void delete_other_orgs_or_missing_campaign_returns_404() throws Exception {
        org.mockito.Mockito.doThrow(com.imin.iminapi.security.ApiException.notFound("Campaign"))
                .when(service).delete(any(), eq(CAMP));
        mvc.perform(delete("/api/v1/marketing/campaigns/{id}", CAMP))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /**
     * mkt-edge-7 (P2): neither request record carried a constraint and neither @RequestBody
     * was @Valid, so a 201-character subject reached Postgres as a VARCHAR(200) overflow —
     * SQLSTATE 22001 — which GlobalExceptionHandler renders as a FIELDLESS
     * "Request violates a data constraint". The composer could not say which field was wrong.
     */
    @Test
    @WithStubOrganizer
    void create_overlong_subject_is_a_fielded_400() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "channel", "email", "name", "Launch", "subject", "x".repeat(201)));
        mvc.perform(post("/api/v1/marketing/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.subject").exists());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @WithStubOrganizer
    void patch_overlong_preheader_is_a_fielded_400() throws Exception {
        String body = om.writeValueAsString(Map.of("preheader", "y".repeat(201)));
        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", CAMP)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.preheader").exists());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @WithStubOrganizer
    void create_overlong_templateKey_is_a_fielded_400() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "channel", "email", "name", "Launch", "templateKey", "t".repeat(65)));
        mvc.perform(post("/api/v1/marketing/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.templateKey").exists());
    }

    /**
     * name is deliberately NOT constrained: CampaignService.truncateName silently clips it
     * today, so a @Size there would turn a currently-succeeding request into a 400.
     */
    @Test
    @WithStubOrganizer
    void create_overlong_name_still_succeeds() throws Exception {
        when(service.create(any(), any())).thenReturn(sampleDto());
        String body = om.writeValueAsString(Map.of(
                "channel", "email", "name", "n".repeat(300)));
        mvc.perform(post("/api/v1/marketing/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @WithStubOrganizer
    void delete_non_draft_returns_409() throws Exception {
        org.mockito.Mockito.doThrow(new com.imin.iminapi.security.ApiException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        com.imin.iminapi.security.ErrorCode.INVALID_STATE,
                        "Only draft campaigns can be deleted"))
                .when(service).delete(any(), eq(CAMP));
        mvc.perform(delete("/api/v1/marketing/campaigns/{id}", CAMP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }
}
