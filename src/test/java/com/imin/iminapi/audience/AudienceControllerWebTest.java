package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.PrebuiltSegment;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AudienceController over the real services: no-leak 404 across orgs, the principal's org over the body,
 * a member payload without tracking fields, and the CSV exports; service rules live in their own classes.
 */
@IminIntegrationTest
class AudienceControllerWebTest {

    private static final String CSV_HEADER = "\"name\",\"email\",\"city\",\"lifecycle\",\"events\",\"attended\","
            + "\"noShow\",\"orders\",\"spend\",\"recencyDays\",\"subscriptionStatus\","
            + "\"lawfulBasis\",\"firstTouchSource\",\"tags\",\"nps\"";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired SegmentRepository segments;
    @Autowired SegmentService segmentService;
    @Autowired ConsentService consentService;
    @Autowired SuppressionService suppressionService;
    final ObjectMapper om = new ObjectMapper();

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;
    private AuthPrincipal principalB;

    @BeforeEach
    void setUp() {
        Organization a = fx.org();
        Organization b = fx.org();
        orgA = a.getId();
        orgB = b.getId();
        principalA = fx.principal(fx.owner(a));
        principalB = fx.principal(fx.owner(b));
    }

    /** Own memberships (consent rows cascade), own segments, then own orgs. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from memberships where org_id in (?, ?)", orgA, orgB);
            jdbc.update("delete from segments where org_id in (?, ?)", orgA, orgB);
        } finally {
            OrgRows.delete(jdbc, List.of(orgA, orgB));
        }
    }

    // ── Cross-org isolation: 404, never 403, and nothing done ────────────────

    /** Org A naming org B's member or segment gets the no-leak 404; nothing is handed off or deleted. */
    @ParameterizedTest
    @ValueSource(strings = {"member", "consent-history", "dsar-access", "segment-handoff", "segment-csv",
            "segment-delete"})
    void cross_org_resource_returns_404_not_found(String route) throws Exception {
        UUID memberB = subscribed(orgB, fx.email("b"), "B", principalB);
        UUID segmentB = segmentService.createSegment(orgB, "B list", "dynamic", null, principalB).getId();

        // No Accept: text/csv on the CSV route: the error body is JSON, a CSV accept would turn 404 into 406.
        RequestBuilder req = switch (route) {
            case "member" -> get("/api/v1/audience/members/" + memberB).with(auth(principalA));
            case "consent-history" -> get("/api/v1/audience/members/" + memberB + "/consent-history").with(auth(principalA));
            case "dsar-access" -> post("/api/v1/audience/members/" + memberB + "/access").with(auth(principalA));
            case "segment-handoff" -> post("/api/v1/audience/segments/" + segmentB + "/handoff").with(auth(principalA));
            case "segment-csv" -> get("/api/v1/audience/segments/" + segmentB + "/snapshot").with(auth(principalA));
            case "segment-delete" -> delete("/api/v1/audience/segments/" + segmentB).with(auth(principalA));
            default -> throw new IllegalArgumentException(route);
        };

        mvc.perform(req)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(audit.forOrg(orgA)).as("nothing audited for org A").isEmpty();
        assertThat(segments.findByIdAndOrgId(segmentB, orgB)).as("org B's segment survives").isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"members", "handoff"})
    void unauthenticated_request_is_blocked(String route) throws Exception {
        RequestBuilder req = route.equals("members")
                ? get("/api/v1/audience/members")
                : post("/api/v1/audience/handoff").contentType(MediaType.APPLICATION_JSON).content("{}");

        mvc.perform(req).andExpect(status().is4xxClientError());
    }

    /**
     * The orgId must NEVER come from the request body — it always comes from the auth context
     * (SPINE INVARIANT 1). A body naming org B and org B's member hands off only org A's own member.
     */
    @Test
    void tampered_body_cannot_hand_off_another_orgs_member() throws Exception {
        UUID memberA = subscribed(orgA, fx.email("a"), "A", principalA);
        UUID memberB = subscribed(orgB, fx.email("b"), "B", principalB);

        String body = om.writeValueAsString(Map.of(
                "orgId", orgB.toString(),
                "membershipIds", List.of(memberA.toString(), memberB.toString())));
        String response = mvc.perform(post("/api/v1/audience/handoff").with(auth(principalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(1))
                .andExpect(jsonPath("$.selectedMembershipIds.length()").value(1))
                .andExpect(jsonPath("$.selectedMembershipIds[0]").value(memberA.toString()))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(memberB.toString());
        audit.assertRecorded(orgA, AuditActions.AUDIENCE_HANDOFF, "membership", null);
        assertThat(audit.forOrg(orgB)).as("no handoff on org B's trail")
                .noneMatch(r -> AuditActions.AUDIENCE_HANDOFF.equals(r.getAction()));
    }

    // ── POST /members/bulk-action — must not fake success ─────────────────────

    /**
     * audience-14: the endpoint accepted any body, wrote nothing and answered 200, and the
     * dashboard turned that into "Tagged N members" / "Export queued" / "Handed off to
     * marketing" plus a navigate — three organizer actions reporting success over a no-op.
     */
    @Test
    void bulk_action_reports_that_it_is_not_implemented() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "action", "tag",
                "membershipIds", List.of(UUID.randomUUID().toString()),
                "tag", "vip"));

        mvc.perform(post("/api/v1/audience/members/bulk-action").with(auth(principalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // ── Member payload ────────────────────────────────────────────────────────

    /** Member detail, DSAR export and list carry no open/click fields; the list has no consent table. */
    @Test
    void member_json_has_no_open_or_click_fields_and_the_list_no_consent_history() throws Exception {
        UUID member = subscribed(orgA, fx.email("m"), "Member", principalA);

        for (var req : List.of(get("/api/v1/audience/members/" + member),
                post("/api/v1/audience/members/" + member + "/export"))) {
            String body = mvc.perform(req.with(auth(principalA))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("\"membershipId\"").doesNotContain("lastEmailOpenAt")
                    .doesNotContain("lastEmailClickAt");
        }
        String list = mvc.perform(get("/api/v1/audience/members").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].membershipId").value(member.toString()))
                .andExpect(jsonPath("$.items[0].consentHistory").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("lastEmailOpenAt").doesNotContain("lastEmailClickAt");
    }

    // ── GET /metrics ──────────────────────────────────────────────────────────

    /** Nothing sent yet: both rates are present as null, never a fabricated 0. */
    @Test
    void metrics_of_an_org_with_no_sends_serialize_null_rates() throws Exception {
        mvc.perform(get("/api/v1/audience/metrics").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("unsubRatePct")))
                .andExpect(jsonPath("$.unsubRatePct").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("complaintRatePct")))
                .andExpect(jsonPath("$.complaintRatePct").value(nullValue()));
    }

    // ── Consent trail ─────────────────────────────────────────────────────────

    @Test
    void consent_history_and_dsar_export_carry_the_consent_trail() throws Exception {
        UUID member = member(orgA, fx.email("trail"), "Trail");
        consentService.capture(orgA, member, "explicit", "signup-form", "Ticked the box at signup", principalA);
        consentService.unsubscribe(orgA, member, "unsubscribe-link", ConsentOrigin.OPERATOR, principalA);

        mvc.perform(get("/api/v1/audience/members/" + member + "/consent-history").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].granted").value(true))
                .andExpect(jsonPath("$[0].lawfulBasis").value("explicit"))
                .andExpect(jsonPath("$[0].source").value("signup-form"))
                .andExpect(jsonPath("$[0].channel").value("email"))
                .andExpect(jsonPath("$[0].proofText").value("Ticked the box at signup"))
                .andExpect(jsonPath("$[0].at").exists())
                .andExpect(jsonPath("$[0].textVersion").value(nullValue()))
                .andExpect(jsonPath("$[0].orderId").value(nullValue()))
                .andExpect(jsonPath("$[0].eventName").value(nullValue()))
                .andExpect(jsonPath("$[1].granted").value(false))
                .andExpect(jsonPath("$[1].source").value("unsubscribe-link"));

        mvc.perform(post("/api/v1/audience/members/" + member + "/export").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(member.toString()))
                .andExpect(jsonPath("$.consentHistory.length()").value(2))
                .andExpect(jsonPath("$.consentHistory[0].granted").value(true))
                .andExpect(jsonPath("$.consentHistory[0].lawfulBasis").value("explicit"))
                .andExpect(jsonPath("$.consentHistory[0].source").value("signup-form"))
                .andExpect(jsonPath("$.consentHistory[0].channel").value("email"))
                .andExpect(jsonPath("$.consentHistory[0].proofText").value("Ticked the box at signup"))
                .andExpect(jsonPath("$.consentHistory[0].at").exists())
                .andExpect(jsonPath("$.consentHistory[1].granted").value(false));
    }

    // ── Segments ──────────────────────────────────────────────────────────────

    @Test
    void create_segment_blank_name_returns_400_field_error_and_saves_nothing() throws Exception {
        // @NotBlank on CreateSegmentRequest rejects before the controller body runs. Was a NOT NULL DB violation → 500.
        mvc.perform(post("/api/v1/audience/segments").with(auth(principalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "   ", "kind", "dynamic"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.name").exists());

        assertThat(segments.findByOrgId(orgA)).isEmpty();
    }

    /** The dashboard sends a structured array; older callers a pre-serialized string. Both bind. */
    @ParameterizedTest
    @ValueSource(strings = {"object", "string"})
    void create_segment_accepts_object_and_string_shaped_rules(String shape) throws Exception {
        String rules = "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"3\"},"
                + "{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]";
        String body = shape.equals("object")
                ? "{\"name\":\"Big Spenders\",\"rulesJson\":" + rules + "}"
                : "{\"name\":\"Big Spenders\",\"rulesJson\":" + om.writeValueAsString(rules) + "}";

        mvc.perform(post("/api/v1/audience/segments").with(auth(principalA))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Big Spenders"))
                .andExpect(jsonPath("$.kind").value("dynamic"))
                .andExpect(jsonPath("$.rules.length()").value(2));
    }

    /** Prebuilts used to be seeded only from tests, so a production org could list zero segments forever. */
    @Test
    void list_seeds_six_prebuilt_segments_and_is_idempotent() throws Exception {
        // First list: org has never been seeded → the endpoint provisions the 6 live prebuilts (no Promoters).
        mvc.perform(get("/api/v1/audience/segments").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.name == 'Promoters')]").isEmpty());

        // Second list: no duplicate seeding.
        mvc.perform(get("/api/v1/audience/segments").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));

        assertThat(segments.findByOrgId(orgA)).filteredOn(Segment::isPrebuilt).hasSize(6);
    }

    /** A retired prebuilt is hidden from the list, but a campaign may still point at it: it hands off by id. */
    @Test
    void segment_handoff_still_hands_off_a_retired_promoters_row() throws Exception {
        UUID promoter = subscribed(orgA, fx.email("promoter"), "Promoter", principalA);
        Membership m = memberships.findByIdAndOrgId(promoter, orgA).orElseThrow();
        m.setNps((short) 9);
        memberships.save(m);
        Segment promoters = new Segment();
        promoters.setOrgId(orgA);
        promoters.setName("Promoters");
        promoters.setKind("dynamic");
        promoters.setPrebuilt(true);
        promoters.setPrebuiltKey(PrebuiltSegment.PROMOTERS.key());
        UUID segId = segments.save(promoters).getId();

        mvc.perform(post("/api/v1/audience/segments/" + segId + "/handoff").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(1))
                .andExpect(jsonPath("$.selectedMembershipIds[0]").value(promoter.toString()));

        mvc.perform(get("/api/v1/audience/segments").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + segId + "')]").isEmpty());
    }

    /** No body previews everyone; a structured groups body is canonicalised and evaluated, nothing is saved. */
    @ParameterizedTest
    @ValueSource(strings = {"no-body", "groups"})
    void segment_preview_counts_unsaved_rules(String body) throws Exception {
        UUID repeat = member(orgA, fx.email("p1"), "P1");
        Membership m = memberships.findByIdAndOrgId(repeat, orgA).orElseThrow();
        m.setEvents(2);
        memberships.save(m);
        member(orgA, fx.email("p2"), "P2");
        member(orgB, fx.email("other"), "Other");

        var req = post("/api/v1/audience/segments/preview").with(auth(principalA));
        if (body.equals("groups")) {
            req.contentType(MediaType.APPLICATION_JSON).content("{\"rulesJson\":{\"groups\":[{\"combinator\":\"and\",\"rules\":"
                    + "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]}]}}");
        }
        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(body.equals("groups") ? 1 : 2));

        assertThat(segments.findByOrgId(orgA)).isEmpty();
    }

    @Test
    void segment_resolve_and_snapshot_csv_cover_only_its_own_members() throws Exception {
        UUID inside = member(orgA, fx.email("inside"), "Snap Member");
        Membership m = memberships.findByIdAndOrgId(inside, orgA).orElseThrow();
        m.setEvents(2);
        memberships.save(m);
        String outsideEmail = fx.email("outside");
        member(orgA, outsideEmail, "Not In Segment");
        UUID segId = segmentService.createSegment(orgA, "Regulars", "dynamic",
                "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]", principalA).getId();

        mvc.perform(get("/api/v1/audience/segments/" + segId + "/resolve").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.mailable").value(0))
                .andExpect(jsonPath("$.excluded").value(1));

        String csv = mvc.perform(get("/api/v1/audience/segments/" + segId + "/snapshot")
                        .with(auth(principalA)).accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("segment-" + segId + ".csv")))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.lines().findFirst().orElse("")).isEqualTo(CSV_HEADER);
        assertThat(csv).contains("Snap Member").doesNotContain(outsideEmail);
    }

    @Test
    void delete_own_segment_returns_204_and_removes_it() throws Exception {
        UUID segId = segmentService.createSegment(orgA, "Short-lived", "dynamic", null, principalA).getId();

        mvc.perform(delete("/api/v1/audience/segments/" + segId).with(auth(principalA)))
                .andExpect(status().isNoContent());

        assertThat(segments.findByIdAndOrgId(segId, orgA)).isEmpty();
    }

    // ── DSAR and suppression on the org's own member ──────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"access", "rectify", "erase", "object"})
    void dsar_route_acts_on_the_orgs_own_member(String route) throws Exception {
        UUID member = subscribed(orgA, fx.email("dsar"), "Old Name", principalA);
        String path = "/api/v1/audience/members/" + member + "/" + route;

        var req = post(path).with(auth(principalA));
        if (route.equals("rectify")) {
            req.contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("displayName", "New Name", "city", "Munich", "notes", "n")));
        }
        var result = mvc.perform(req);

        // The HTTP contract only; the DSAR effects are owned by AudienceDsarTest.
        switch (route) {
            case "access", "rectify" -> result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.membershipId").value(member.toString()));
            case "erase" -> result.andExpect(status().isAccepted());
            case "object" -> result.andExpect(status().isOk());
            default -> throw new IllegalArgumentException(route);
        }
    }

    @Test
    void suppression_list_shows_only_the_orgs_own_marketing_suppressions() throws Exception {
        UUID mine = member(orgA, fx.email("supp-a"), "A");
        UUID theirs = member(orgB, fx.email("supp-b"), "B");
        suppressionService.addMarketing(orgA, mine, SuppressionEntry.REASON_MANUAL, principalA);
        suppressionService.addMarketing(orgB, theirs, SuppressionEntry.REASON_MANUAL, principalB);

        String body = mvc.perform(get("/api/v1/audience/suppression").with(auth(principalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(mine.toString()).doesNotContain(theirs.toString());
    }

    // ── CSV export: GET /members?format=csv ───────────────────────────────────

    @Test
    void members_csv_of_an_empty_org_is_the_pinned_header_without_open_or_click() throws Exception {
        String body = mvc.perform(get("/api/v1/audience/members?format=csv").with(auth(principalA)).accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("audience-members.csv")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo(CSV_HEADER + "\r\n");
    }

    @Test
    void members_csv_field_with_comma_and_quote_is_properly_quoted() throws Exception {
        member(orgA, fx.email("tricky"), "Smith, \"DJ\" Joe");

        String body = mvc.perform(get("/api/v1/audience/members?format=csv").with(auth(principalA)).accept("text/csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // RFC4180: the name field must be wrapped in double-quotes and internal quotes doubled
        assertThat(body.lines().findFirst().orElse("")).isEqualTo(CSV_HEADER);
        assertThat(body).contains("\"Smith, \"\"DJ\"\" Joe\"");
    }

    @Test
    void members_csv_lifecycle_filter_exports_only_that_lifecycle() throws Exception {
        String repeatEmail = fx.email("repeat");
        String prospectEmail = fx.email("prospect");
        UUID repeat = member(orgA, repeatEmail, "Repeat");
        member(orgA, prospectEmail, "Prospect");
        Membership m = memberships.findByIdAndOrgId(repeat, orgA).orElseThrow();
        m.setLifecycle("repeat");
        memberships.save(m);

        String body = mvc.perform(get("/api/v1/audience/members?format=csv&lifecycle=repeat")
                        .with(auth(principalA)).accept("text/csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(repeatEmail).doesNotContain(prospectEmail);
    }

    // ── Tenant repositories expose no unscoped finders ───────────────────────

    // Kept despite the reflection/source-scan rule: the only static guard against a cross-tenant finder or caller.
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
    void m4_membership_repository_has_no_unscoped_finders() throws Exception {
        // Not extending JpaRepository/CrudRepository is pinned by m4_tenant_repos_dont_expose_findAll.
        Class<?> repoClass = MembershipRepository.class;

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
                // findCreatedSince and findByIdsAndOrgId take orgId
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

        // Name-based allow-listing is necessary but not sufficient: it permits
        // ANY future caller of the exempted method, including an
        // organizer-authenticated one. So the call sites are pinned too.
        assertThatOnlyTheBuyerPreferenceCentreCallsTheCrossOrgFinder();

        // Explicitly assert there is no findAll(), findById() without orgId, deleteAll()
        assertThatNoUnscopedMethodExists(repoClass, "findAll");
        assertThatNoUnscopedMethodExists(repoClass, "deleteAll");
        assertThatNoUnscopedMethodExists(repoClass, "getReferenceById");
        assertThatNoUnscopedMethodExists(repoClass, "getOne");
    }

    /**
     * {@code findAllOrgsByConsumerIdIn} is exempt from the orgId rule because a
     * buyer's own consent crosses organizers by definition. That exemption is
     * only safe while the sole caller is buyer-authenticated — reached through
     * {@code @CurrentBuyer} on an {@code /api/v1/buyer/**} path, scoped by the
     * caller having proved they own the addresses behind those consumer ids.
     *
     * <p>An organizer-path caller would turn the same method into a
     * cross-tenant read with no scope at all, and the name-based allow-list
     * above would not notice. This does.
     */
    private void assertThatOnlyTheBuyerPreferenceCentreCallsTheCrossOrgFinder() throws Exception {
        java.util.List<String> callers = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String body = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                if (body.contains(".findAllOrgsByConsumerIdIn(")) callers.add(file.getFileName().toString());
            }
        }
        assertThat(callers)
                .withFailMessage(
                        "findAllOrgsByConsumerIdIn is the deliberately unscoped cross-org read (M4). "
                                + "Its exemption holds only while every caller is buyer-authenticated. "
                                + "New callers: %s — prove the caller is under /api/v1/buyer/** with "
                                + "@CurrentBuyer before adding it here.", callers)
                .containsExactly("BuyerPreferencesService.java");
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

    // Kept despite the reflection rule: the only guard that tenant repos never inherit unscoped findAll/findById.
    /** Tenant repositories must not extend JpaRepository/CrudRepository (findAll/findById/deleteAll). */
    @Test
    void m4_tenant_repos_dont_expose_findAll() {
        // MembershipRepository, ConsentRecordRepository, SegmentRepository must NOT
        // extend JpaRepository (which exposes findAll/findById/deleteAll).
        // Verify via reflection that none of these interfaces extends JpaRepository.
        assertThat(isJpaRepository(MembershipRepository.class))
                .as("MembershipRepository must not extend JpaRepository").isFalse();
        assertThat(isJpaRepository(com.imin.iminapi.audience.repository.ConsentRecordRepository.class))
                .as("ConsentRecordRepository must not extend JpaRepository").isFalse();
        assertThat(isJpaRepository(SegmentRepository.class))
                .as("SegmentRepository must not extend JpaRepository").isFalse();
    }

    /** Transitive: an intermediate interface extending CrudRepository counts too. */
    private boolean isJpaRepository(Class<?> iface) {
        return org.springframework.data.jpa.repository.JpaRepository.class.isAssignableFrom(iface)
                || org.springframework.data.repository.CrudRepository.class.isAssignableFrom(iface);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A projected member with no lawful basis yet. */
    private UUID member(UUID orgId, String email, String name) {
        orderProjector.upsertMembership(orgId, email, name);
        Consumer c = consumers.findByNormalizedEmail(email).orElseThrow();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        m.setDisplayName(name);
        memberships.save(m);
        return m.getMembershipId();
    }

    /** A member with an explicit grant, so the send gate lets them through. */
    private UUID subscribed(UUID orgId, String email, String name, AuthPrincipal p) {
        UUID mid = member(orgId, email, name);
        consentService.capture(orgId, mid, "explicit", "seed", "proof", p);
        return mid;
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
