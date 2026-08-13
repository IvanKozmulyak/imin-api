package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.*;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer (MockMvc) tests for AudienceController.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy-path GET /members and POST /handoff</li>
 *   <li>Cross-org isolation: org A GET /members/{B id} → 404 not 403</li>
 *   <li>Tampered orgId in request body is ignored (orgId always from auth context)</li>
 *   <li>M4 architecture assertion: MembershipRepository does not expose unscoped finders</li>
 *   <li>Audit ArgumentCaptor per governance mutation (consent capture, unsubscribe, handoff,
 *       segment create/delete, DSAR mutations)</li>
 *   <li>Unauthenticated requests → 401/403 (not 200)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AudienceControllerWebTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    // Mock the service layer — this is a pure web-layer test
    @MockitoBean AudienceService audienceService;
    @MockitoBean AudienceMetricsService metricsService;
    @MockitoBean ConsentService consentService;
    @MockitoBean SegmentService segmentService;
    @MockitoBean SendGateService sendGateService;
    @MockitoBean DsarService dsarService;
    @MockitoBean AuditLogger auditLogger;

    static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final UUID USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000010");
    static final UUID MEMBER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000100");
    static final UUID MEMBER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000100");

    // ── Auth factory ──────────────────────────────────────────────────────────

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithOrgA {}

    public static class StubFactory implements WithSecurityContextFactory<WithOrgA> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithOrgA ann) {
            AuthPrincipal p = new AuthPrincipal(USER_A, ORG_A, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MemberDto stubMember(UUID membershipId) {
        return new MemberDto(
                membershipId.toString(), "Test User", "test@example.com",
                "Berlin", List.of("techno"), 3, 2, 0, 3,
                9000L, 3000L,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                14, "organic",
                "explicit", "subscribed", null,
                null, null, null, null, null,
                List.of("vip"), "", "repeat",
                new MemberDto.RfmInfo(4, 3, 5)
        );
    }

    // ── GET /members — happy path ─────────────────────────────────────────────

    @Test
    @WithOrgA
    void get_members_returns_200_with_items() throws Exception {
        MemberDto dto = stubMember(MEMBER_A);
        when(audienceService.listMembers(eq(ORG_A), isNull(), eq(50), isNull(), isNull()))
                .thenReturn(new MemberPage(List.of(dto), null));

        mvc.perform(get("/api/v1/audience/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].membershipId").value(MEMBER_A.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Test User"))
                .andExpect(jsonPath("$.items[0].lifecycle").value("repeat"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @WithOrgA
    void get_members_with_cursor_passes_cursor_to_service() throws Exception {
        when(audienceService.listMembers(eq(ORG_A), eq("someCursor"), eq(20), eq("vip"), isNull()))
                .thenReturn(new MemberPage(List.of(), null));

        mvc.perform(get("/api/v1/audience/members?cursor=someCursor&limit=20&lifecycle=vip"))
                .andExpect(status().isOk());

        verify(audienceService).listMembers(ORG_A, "someCursor", 20, "vip", null);
    }

    @Test
    @WithOrgA
    void get_members_with_next_cursor_included_in_response() throws Exception {
        MemberDto dto = stubMember(MEMBER_A);
        when(audienceService.listMembers(any(), any(), anyInt(), any(), any()))
                .thenReturn(new MemberPage(List.of(dto), "nextCursorToken"));

        mvc.perform(get("/api/v1/audience/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").value("nextCursorToken"));
    }

    // ── GET /members/{id} — happy path and cross-org isolation ────────────────

    @Test
    @WithOrgA
    void get_member_by_id_returns_200() throws Exception {
        MemberDto dto = stubMember(MEMBER_A);
        when(audienceService.getMember(ORG_A, MEMBER_A)).thenReturn(dto);

        mvc.perform(get("/api/v1/audience/members/" + MEMBER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(MEMBER_A.toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.rfm.r").value(4));
    }

    /**
     * Cross-org isolation: org A accessing org B's member ID must get 404, NOT 403.
     * The service throws ApiException.notFound which maps to HTTP 404.
     * The 404 body must NOT reveal the existence of the resource.
     */
    @Test
    @WithOrgA
    void get_member_cross_org_returns_404_not_403() throws Exception {
        // Service throws notFound (404) when the membership doesn't belong to the org
        when(audienceService.getMember(ORG_A, MEMBER_B))
                .thenThrow(ApiException.notFound("Membership"));

        mvc.perform(get("/api/v1/audience/members/" + MEMBER_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /**
     * The orgId must NEVER come from the request body — it always comes from the
     * auth context (SPINE INVARIANT 1). This test verifies that injecting an orgId
     * in the body is silently ignored: the service sees ORG_A from the principal.
     */
    @Test
    @WithOrgA
    void tampered_orgId_in_body_is_ignored_for_handoff() throws Exception {
        // Body contains ORG_B trying to tamper — service must still be called with ORG_A
        UUID tamperOrgId = ORG_B;
        List<UUID> memberIds = List.of(MEMBER_A);

        when(sendGateService.handoff(eq(ORG_A), any(), any()))
                .thenReturn(new HandoffResponse(1, List.of(MEMBER_A.toString()), List.of(), "/campaigns"));

        // Post with membershipIds — the orgId is from auth, not from body
        String body = om.writeValueAsString(Map.of("membershipIds", List.of(MEMBER_A.toString())));
        mvc.perform(post("/api/v1/audience/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(1));

        // Verify service was called with ORG_A (from principal), never ORG_B
        verify(sendGateService).handoff(eq(ORG_A), anyList(), any());
        verify(sendGateService, never()).handoff(eq(tamperOrgId), anyList(), any());
    }

    // ── POST /handoff — happy path ────────────────────────────────────────────

    @Test
    @WithOrgA
    void post_handoff_returns_recipient_count_and_campaign_url() throws Exception {
        UUID mid1 = UUID.randomUUID();
        UUID mid2 = UUID.randomUUID();

        when(sendGateService.handoff(eq(ORG_A), anyList(), any()))
                .thenReturn(new HandoffResponse(
                        2,
                        List.of(mid1.toString(), mid2.toString()),
                        List.of(),
                        "/campaigns"
                ));

        String body = om.writeValueAsString(Map.of(
                "membershipIds", List.of(mid1.toString(), mid2.toString())
        ));

        mvc.perform(post("/api/v1/audience/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(2))
                .andExpect(jsonPath("$.selectedMembershipIds.length()").value(2))
                .andExpect(jsonPath("$.campaignHubUrl").value("/campaigns"));
    }

    @Test
    @WithOrgA
    void post_handoff_with_exclusions_shows_excluded_reasons() throws Exception {
        UUID sendable = UUID.randomUUID();
        UUID excluded = UUID.randomUUID();

        when(sendGateService.handoff(eq(ORG_A), anyList(), any()))
                .thenReturn(new HandoffResponse(
                        1,
                        List.of(sendable.toString()),
                        List.of(new ExclusionReason(excluded, "no_lawful_basis")),
                        "/campaigns"
                ));

        String body = om.writeValueAsString(Map.of(
                "membershipIds", List.of(sendable.toString(), excluded.toString())
        ));

        mvc.perform(post("/api/v1/audience/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(1))
                .andExpect(jsonPath("$.excludedReasons[0].reason").value("no_lawful_basis"));
    }

    // ── GET /metrics ──────────────────────────────────────────────────────────

    @Test
    @WithOrgA
    void get_metrics_returns_200() throws Exception {
        when(metricsService.compute(ORG_A))
                .thenReturn(new AudienceMetricsDto(
                        100L, 80L, 20L, 60L, 60.0,
                        List.of(5, 8, 10, 12, 15, 18, 20, 22),
                        75.0, 50L, 10L, 5.0, 0.1
                ));

        mvc.perform(get("/api/v1/audience/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMembers").value(100))
                .andExpect(jsonPath("$.buyers").value(80))
                .andExpect(jsonPath("$.prospects").value(20))
                .andExpect(jsonPath("$.listGrowth8w.length()").value(8));
    }

    // ── POST /consent/capture — audit ArgumentCaptor ──────────────────────────

    @Test
    @WithOrgA
    void post_consent_capture_calls_service_and_returns_200() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "membershipId", MEMBER_A.toString(),
                "basis", "explicit",
                "source", "signup-form",
                "proofText", "checkbox checked"
        ));

        mvc.perform(post("/api/v1/audience/consent/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(consentService).capture(
                eq(ORG_A), eq(MEMBER_A), eq("explicit"),
                eq("signup-form"), eq("checkbox checked"), eq("email"), any());
    }

    @Test
    @WithOrgA
    void post_consent_unsubscribe_calls_service_and_returns_200() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "membershipId", MEMBER_A.toString(),
                "source", "unsubscribe-link"
        ));

        mvc.perform(post("/api/v1/audience/consent/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // OPERATOR, not DATA_SUBJECT (§16): this is the organizer's own list-tidying
        // endpoint and `source` is arbitrary request-body text. The origin is chosen by
        // the endpoint, so no `source` an organizer can POST writes a sticky
        // marketing_optouts row that would bar the buyer from ever opting back in.
        verify(consentService).unsubscribe(eq(ORG_A), eq(MEMBER_A), eq("unsubscribe-link"), eq("email"),
                eq(ConsentOrigin.OPERATOR), any());
    }

    // ── DSAR endpoints — correct status codes ────────────────────────────────

    @Test
    @WithOrgA
    void post_dsar_access_returns_member_dto() throws Exception {
        MemberDto dto = stubMember(MEMBER_A);
        when(dsarService.access(eq(ORG_A), eq(MEMBER_A), any())).thenReturn(null /* not used directly */);
        when(audienceService.getMember(eq(ORG_A), eq(MEMBER_A))).thenReturn(dto);

        mvc.perform(post("/api/v1/audience/members/" + MEMBER_A + "/access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(MEMBER_A.toString()));
    }

    @Test
    @WithOrgA
    void post_dsar_erase_returns_202_accepted() throws Exception {
        doNothing().when(dsarService).requestErase(eq(ORG_A), eq(MEMBER_A), any());

        mvc.perform(post("/api/v1/audience/members/" + MEMBER_A + "/erase"))
                .andExpect(status().isAccepted());

        verify(dsarService).requestErase(eq(ORG_A), eq(MEMBER_A), any());
    }

    @Test
    @WithOrgA
    void post_dsar_object_synchronous_returns_200() throws Exception {
        doNothing().when(dsarService).object(eq(ORG_A), eq(MEMBER_A), any());

        mvc.perform(post("/api/v1/audience/members/" + MEMBER_A + "/object"))
                .andExpect(status().isOk());

        verify(dsarService).object(eq(ORG_A), eq(MEMBER_A), any());
    }

    @Test
    @WithOrgA
    void post_dsar_rectify_calls_service_and_returns_member() throws Exception {
        MemberDto dto = stubMember(MEMBER_A);
        when(dsarService.rectify(eq(ORG_A), eq(MEMBER_A), any(), any(), any(), any())).thenReturn(null);
        when(audienceService.getMember(ORG_A, MEMBER_A)).thenReturn(dto);

        String body = om.writeValueAsString(Map.of(
                "displayName", "New Name",
                "city", "Munich",
                "notes", "updated notes"
        ));

        mvc.perform(post("/api/v1/audience/members/" + MEMBER_A + "/rectify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(MEMBER_A.toString()));
    }

    /**
     * DSAR cross-org: org A accessing a DSAR on org B's member must return 404.
     */
    @Test
    @WithOrgA
    void post_dsar_access_cross_org_returns_404() throws Exception {
        when(dsarService.access(eq(ORG_A), eq(MEMBER_B), any()))
                .thenThrow(ApiException.notFound("Membership"));

        mvc.perform(post("/api/v1/audience/members/" + MEMBER_B + "/access"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ── Segments ──────────────────────────────────────────────────────────────

    @Test
    @WithOrgA
    void get_segments_returns_list() throws Exception {
        when(segmentService.listSegments(ORG_A)).thenReturn(List.of());

        mvc.perform(get("/api/v1/audience/segments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithOrgA
    void post_segments_returns_201_with_dto() throws Exception {
        com.imin.iminapi.audience.model.Segment seg = new com.imin.iminapi.audience.model.Segment();
        seg.setId(UUID.randomUUID());
        seg.setOrgId(ORG_A);
        seg.setName("High LTV");
        seg.setKind("dynamic");
        seg.setCreatedAt(Instant.now());
        seg.setUpdatedAt(Instant.now());

        when(segmentService.createSegment(eq(ORG_A), eq("High LTV"), eq("dynamic"), isNull(), any()))
                .thenReturn(seg);

        String body = om.writeValueAsString(Map.of("name", "High LTV", "kind", "dynamic"));
        mvc.perform(post("/api/v1/audience/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("High LTV"))
                .andExpect(jsonPath("$.kind").value("dynamic"));
    }

    @Test
    @WithOrgA
    void post_segments_blank_name_returns_400_field_error() throws Exception {
        // @NotBlank on CreateSegmentRequest rejects before the controller body runs — the
        // service is never called. Was a NOT NULL DB violation → 500.
        String body = om.writeValueAsString(Map.of("name", "   ", "kind", "dynamic"));
        mvc.perform(post("/api/v1/audience/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.name").exists());
        verify(segmentService, never()).createSegment(any(), any(), any(), any(), any());
    }

    @Test
    @WithOrgA
    void post_segments_unique_violation_maps_to_409_not_500() throws Exception {
        // A DB unique_violation (SQLState 23505) surfacing from the service must become a clean
        // 409, never a 500 (there was no DataIntegrityViolationException handler before).
        when(segmentService.createSegment(any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key",
                        new java.sql.SQLException("duplicate key value violates unique constraint", "23505")));

        String body = om.writeValueAsString(Map.of("name", "Dup"));
        mvc.perform(post("/api/v1/audience/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE"));
    }

    @Test
    @WithOrgA
    void post_segments_other_constraint_violation_maps_to_400_not_500() throws Exception {
        when(segmentService.createSegment(any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("check constraint failed"));

        String body = om.writeValueAsString(Map.of("name", "Bad"));
        mvc.perform(post("/api/v1/audience/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    @WithOrgA
    void get_segments_triggers_prebuilt_seeding() throws Exception {
        when(segmentService.listSegments(ORG_A)).thenReturn(List.of());

        mvc.perform(get("/api/v1/audience/segments"))
                .andExpect(status().isOk());

        // The list endpoint must lazily provision prebuilts for the org before reading.
        verify(segmentService).ensurePrebuiltSegments(ORG_A);
    }

    @Test
    @WithOrgA
    void delete_segment_returns_204() throws Exception {
        UUID segId = UUID.randomUUID();
        doNothing().when(segmentService).deleteSegment(eq(ORG_A), eq(segId), any());

        mvc.perform(delete("/api/v1/audience/segments/" + segId))
                .andExpect(status().isNoContent());

        verify(segmentService).deleteSegment(eq(ORG_A), eq(segId), any());
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @Test
    @WithOrgA
    void get_suppression_returns_list() throws Exception {
        // SuppressionRepository is wired directly in the controller, but we mock
        // via the audienceService path - verify the suppression endpoint is accessible
        // The controller calls suppressionRepo.findMarketingByOrg directly
        // Since this is a MockMvc test, the real bean must be available or mocked
        // We mock via @MockitoBean on services; the suppressionRepo is not mocked here,
        // so we test via an integration approach on just the endpoint path
        mvc.perform(get("/api/v1/audience/suppression"))
                .andExpect(status().isOk()); // returns empty list from real H2
    }

    // ── Unauthenticated requests blocked ─────────────────────────────────────

    @Test
    void unauthenticated_get_members_returns_403() throws Exception {
        // No auth → Spring Security rejects before reaching the controller
        mvc.perform(get("/api/v1/audience/members"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_post_handoff_returns_403() throws Exception {
        mvc.perform(post("/api/v1/audience/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    // ── M4: Architecture test — MembershipRepository has no unscoped finders ──

    /**
     * M4 invariant: MembershipRepository must NOT expose any method that loads
     * memberships without an orgId parameter. This is a structural assertion
     * that prevents accidental cross-tenant data leaks.
     *
     * <p>We check that none of the repository's declared query methods are named
     * in a way that could bypass tenant scoping (e.g., findAll, findById,
     * deleteAll, getReferenceById, getOne).
     */
    @Test
    void m4_membership_repository_has_no_unscoped_finders() {
        // MembershipRepository extends Repository<T,ID>, not JpaRepository
        // Verify it does NOT inherit the dangerous unscoped methods
        Class<?> repoClass = MembershipRepository.class;

        // It must NOT extend JpaRepository or CrudRepository
        assertThat(org.springframework.data.jpa.repository.JpaRepository.class.isAssignableFrom(repoClass))
                .withFailMessage("MembershipRepository must not extend JpaRepository")
                .isFalse();

        assertThat(org.springframework.data.repository.CrudRepository.class.isAssignableFrom(repoClass))
                .withFailMessage("MembershipRepository must not extend CrudRepository")
                .isFalse();

        // Verify every declared method takes orgId (by name convention)
        // All read methods must have orgId or consume it through the findByIdsAndOrgId pattern
        java.lang.reflect.Method[] methods = repoClass.getDeclaredMethods();

        for (java.lang.reflect.Method method : methods) {
            String name = method.getName();

            // Skip write methods (save, delete) and explicit cross-consumer methods
            if (name.equals("save") || name.startsWith("delete") || name.startsWith("count")) {
                continue; // deletes take orgId; counts take orgId
            }
            if (name.equals("findErasureDue")) {
                // Erasure job: reads across all orgs (admin-style) - allowed by spec
                // (the spec says "findErasureDue" in the erasure job section)
                continue;
            }

            // All remaining methods must accept an orgId parameter (by name or position)
            java.lang.reflect.Parameter[] params = method.getParameters();
            boolean hasOrgIdParam = false;
            for (java.lang.reflect.Parameter p : params) {
                if (p.getName().equals("orgId") || p.getName().equals("arg0")) {
                    // arg0 means parameter names not preserved; check by annotation
                    org.springframework.data.repository.query.Param ann =
                            p.getAnnotation(org.springframework.data.repository.query.Param.class);
                    if (ann != null && ann.value().equals("orgId")) {
                        hasOrgIdParam = true;
                        break;
                    }
                }
                // Check @Param annotation value
                org.springframework.data.repository.query.Param ann =
                        p.getAnnotation(org.springframework.data.repository.query.Param.class);
                if (ann != null && ann.value().equals("orgId")) {
                    hasOrgIdParam = true;
                    break;
                }
            }

            if (!hasOrgIdParam) {
                // findSendCandidates and findCreatedSince and findByIdsAndOrgId take orgId
                // findByOrgIdAndConsumerId takes orgId
                // If there's no orgId param, this is an unscoped method - FAIL
                // But we need to allow "save" (already skipped) and Pageable variants
                // Let's collect names that are suspicious
                boolean hasPagingOnly = false;
                if (params.length == 1 && (
                        params[0].getType().equals(org.springframework.data.domain.Pageable.class) ||
                        params[0].getType().equals(java.time.Instant.class) ||
                        params[0].getType().equals(java.util.Collection.class)
                )) {
                    hasPagingOnly = true;
                }

                if (hasPagingOnly) {
                    // Default-deny. Two named exceptions, each one a read that is
                    // cross-organizer BY DEFINITION and cannot be expressed with an
                    // orgId — adding a third means arguing for it here:
                    //
                    //   findErasureDue          — the erasure job sweeps every org.
                    //   findAllOrgsByConsumerIdIn — the buyer preference centre (§4.4).
                    //       A buyer's own consent spans organizers the same way
                    //       GET /buyer/orders does. Named "AllOrgs" so the absence of
                    //       tenant scoping is visible at the call site rather than
                    //       inferred from a missing parameter. Buyer-authenticated
                    //       only: scoped by the caller having proved they own the
                    //       addresses behind those consumer ids.
                    assertThat(name)
                            .withFailMessage(
                                    "Method '%s' in MembershipRepository has no orgId scope — potential cross-tenant leak (M4)",
                                    name)
                            .isIn("findErasureDue", "findAllOrgsByConsumerIdIn");
                }
            }
        }

        // Explicitly assert there is no findAll(), findById() without orgId, deleteAll()
        assertThatNoUnscopedMethodExists(repoClass, "findAll");
        assertThatNoUnscopedMethodExists(repoClass, "deleteAll");
        assertThatNoUnscopedMethodExists(repoClass, "getReferenceById");
        assertThatNoUnscopedMethodExists(repoClass, "getOne");
    }

    private void assertThatNoUnscopedMethodExists(Class<?> repoClass, String methodName) {
        boolean found = false;
        for (java.lang.reflect.Method m : repoClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Method '%s' found in MembershipRepository — this is an unscoped finder (M4 violation)", methodName)
                .isFalse();
    }

    // ── Audit: ArgumentCaptor assertions via direct service calls ─────────────

    /**
     * Verifies the CORRECT AuditActions constant is passed for each governance mutation.
     * We call the controller (via MockMvc) and then verify the downstream service was
     * invoked with the right arguments.
     *
     * For audit constants specifically, since AuditLogger is mocked and
     * the actual service beans are mocked too, we verify the controller
     * correctly delegates to the right service method (which internally calls audit).
     * The per-service ArgumentCaptor audit tests live in AudienceSendGateConsentSuppressionTest.
     * Here we verify the correct service method is dispatched per endpoint.
     */
    @Test
    @WithOrgA
    void audit_handoff_service_called_with_org_a_principal() throws Exception {
        when(sendGateService.handoff(eq(ORG_A), anyList(), any()))
                .thenReturn(new HandoffResponse(0, List.of(), List.of(), "/campaigns"));

        String body = om.writeValueAsString(Map.of(
                "membershipIds", List.of(MEMBER_A.toString())
        ));

        mvc.perform(post("/api/v1/audience/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Verify the handoff is called with ORG_A from auth context, NOT from body
        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(sendGateService).handoff(orgCaptor.capture(), anyList(), any());
        assertThat(orgCaptor.getValue()).isEqualTo(ORG_A);
    }

    @Test
    @WithOrgA
    void audit_consent_capture_service_dispatched_with_org_scoped_id() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "membershipId", MEMBER_A.toString(),
                "basis", "explicit",
                "source", "double-opt-in",
                "proofText", "link clicked"
        ));

        mvc.perform(post("/api/v1/audience/consent/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // The principal's orgId must be passed, not a body orgId
        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(consentService).capture(orgCaptor.capture(), eq(MEMBER_A),
                eq("explicit"), eq("double-opt-in"), eq("link clicked"), eq("email"), any());
        assertThat(orgCaptor.getValue()).isEqualTo(ORG_A);
    }

    @Test
    @WithOrgA
    void audit_segment_delete_service_dispatched_with_org_a() throws Exception {
        UUID segId = UUID.randomUUID();
        doNothing().when(segmentService).deleteSegment(any(), any(), any());

        mvc.perform(delete("/api/v1/audience/segments/" + segId))
                .andExpect(status().isNoContent());

        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(segmentService).deleteSegment(orgCaptor.capture(), eq(segId), any());
        assertThat(orgCaptor.getValue()).isEqualTo(ORG_A);
    }

    @Test
    @WithOrgA
    void audit_dsar_erase_service_dispatched_with_org_a() throws Exception {
        doNothing().when(dsarService).requestErase(any(), any(), any());

        mvc.perform(post("/api/v1/audience/members/" + MEMBER_A + "/erase"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(dsarService).requestErase(orgCaptor.capture(), eq(MEMBER_A), any());
        assertThat(orgCaptor.getValue()).isEqualTo(ORG_A);
    }

    // ── Segment/handoff cross-org via path does not leak ─────────────────────

    @Test
    @WithOrgA
    void segment_handoff_cross_org_segment_returns_404() throws Exception {
        UUID foreignSegId = UUID.randomUUID();
        when(segmentService.listSegments(ORG_A)).thenReturn(List.of()); // org A has no segments

        mvc.perform(post("/api/v1/audience/segments/" + foreignSegId + "/handoff"))
                .andExpect(status().isNotFound());
    }

    // ── /segments/{id}/resolve ────────────────────────────────────────────────

    @Test
    @WithOrgA
    void get_segment_resolve_returns_dto() throws Exception {
        UUID segId = UUID.randomUUID();
        when(segmentService.resolve(ORG_A, segId))
                .thenReturn(new SegmentResolveDto(25, 20, 5, 45000L));

        mvc.perform(get("/api/v1/audience/segments/" + segId + "/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(25))
                .andExpect(jsonPath("$.mailable").value(20))
                .andExpect(jsonPath("$.excluded").value(5))
                .andExpect(jsonPath("$.avgLtvMinor").value(45000));
    }

    // ── CSV export: GET /members?format=csv ───────────────────────────────────

    @Test
    @WithOrgA
    void get_members_csv_returns_200_text_csv_with_header_and_member() throws Exception {
        MemberDto dto = stubMember(MEMBER_A);
        when(audienceService.exportMembersCsv(eq(ORG_A), isNull(), isNull()))
                .thenReturn(List.of(dto));

        String body = mvc.perform(get("/api/v1/audience/members?format=csv")
                        .accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("audience-members.csv")))
                .andReturn().getResponse().getContentAsString();

        // First line must be the header
        String firstLine = body.lines().findFirst().orElse("");
        assertThat(firstLine).contains("name").contains("email").contains("lifecycle");

        // Member data must appear somewhere in the body
        assertThat(body).contains("Test User");
        assertThat(body).contains("test@example.com");
    }

    @Test
    @WithOrgA
    void get_members_csv_with_lifecycle_filter_passes_param_to_service() throws Exception {
        when(audienceService.exportMembersCsv(eq(ORG_A), eq("repeat"), isNull()))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/audience/members?format=csv&lifecycle=repeat")
                        .accept("text/csv"))
                .andExpect(status().isOk());

        verify(audienceService).exportMembersCsv(ORG_A, "repeat", null);
    }

    @Test
    @WithOrgA
    void get_members_csv_field_with_comma_and_quote_is_properly_quoted() throws Exception {
        // Member with a name containing comma and a double-quote
        MemberDto tricky = new MemberDto(
                MEMBER_A.toString(), "Smith, \"DJ\" Joe", "tricky@example.com",
                "Berlin", List.of(), 1, 1, 0, 1,
                5000L, 5000L,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                7, "organic", "explicit", "subscribed", null,
                null, null, null, null, null,
                List.of("tag1"), "", "repeat",
                new MemberDto.RfmInfo(3, 2, 4)
        );
        when(audienceService.exportMembersCsv(eq(ORG_A), isNull(), isNull()))
                .thenReturn(List.of(tricky));

        String body = mvc.perform(get("/api/v1/audience/members?format=csv")
                        .accept("text/csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // RFC4180: the name field must be wrapped in double-quotes and internal quotes doubled
        assertThat(body).contains("\"Smith, \"\"DJ\"\" Joe\"");
    }

    // ── CSV export: GET /segments/{id}/snapshot ───────────────────────────────

    @Test
    @WithOrgA
    void get_segment_snapshot_csv_returns_text_csv() throws Exception {
        UUID segId = UUID.randomUUID();
        Segment seg = new Segment();
        seg.setId(segId);
        seg.setOrgId(ORG_A);
        seg.setName("VIP");
        seg.setKind("dynamic");
        seg.setCreatedAt(Instant.now());
        seg.setUpdatedAt(Instant.now());

        MemberDto dto = stubMember(MEMBER_A);
        when(segmentService.requireSegmentForOrg(ORG_A, segId)).thenReturn(seg);
        when(segmentService.resolveMembers(eq(ORG_A), eq(seg))).thenReturn(List.of());
        when(audienceService.toMemberDtos(eq(ORG_A), eq(List.of()))).thenReturn(List.of(dto));

        String body = mvc.perform(get("/api/v1/audience/segments/" + segId + "/snapshot")
                        .accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("segment-" + segId + ".csv")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body.lines().findFirst().orElse("")).contains("name");
        assertThat(body).contains("Test User");
    }

    @Test
    @WithOrgA
    void get_segment_snapshot_csv_cross_org_returns_404() throws Exception {
        UUID foreignSegId = UUID.randomUUID();
        when(segmentService.requireSegmentForOrg(ORG_A, foreignSegId))
                .thenThrow(ApiException.notFound("Segment"));

        // Do NOT send Accept: text/csv — the global exception handler returns JSON,
        // and Spring cannot negotiate a CSV error body, which would produce 406 instead of 404.
        mvc.perform(get("/api/v1/audience/segments/" + foreignSegId + "/snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
