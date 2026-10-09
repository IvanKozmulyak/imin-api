package com.imin.iminapi.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The campaign HTTP contract over the real service: statuses, fielded 400s and the no-leak 404. */
@IminIntegrationTest
class CampaignControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyFlips flips;
    @Autowired MarketingEmailProperties marketingProps;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal owner;
    private AuthPrincipal otherOwner;

    @BeforeEach
    void setUp() {
        owner = fx.principal(fx.owner(fx.org()));
        otherOwner = fx.principal(fx.owner(fx.org()));
        orgIds.add(owner.orgId());
        orgIds.add(otherOwner.orgId());
    }

    // A valid send leaves a scheduled campaign the dispatcher would claim.
    @AfterEach
    void tearDown() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /**
     * mkt-edge-9 (P2): `page` went into PageRequest.of unclamped, and PageRequest.of(-1, 50) throws
     * IllegalArgumentException, which GlobalExceptionHandler has no handler for: ?page=-1 answered 500.
     */
    @Test
    void negativePageIsClampedNotA500() throws Exception {
        UUID id = draft(owner, null, null).id();

        mvc.perform(get("/api/v1/marketing/campaigns").param("page", "-1").with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(id.toString())));
        mvc.perform(get("/api/v1/marketing/campaigns/{id}/recipients", id).param("page", "-3").with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }

    /**
     * mkt-edge-8 (P2): the composer PATCHes {name, segmentId: null, eventId: null} when the organizer leaves
     * the Audience step; an explicit null clears the link, an absent field keeps it, a supplied id replaces it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("patchLinkCases")
    void patch_explicitNullClears_absentKeeps_suppliedReplaces(String label, String body,
                                                                String segmentAfter, boolean eventKept) throws Exception {
        UUID segment = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID id = draft(owner, segment, event).id();

        var res = mvc.perform(patch("/api/v1/marketing/campaigns/{id}", id).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        switch (segmentAfter) {
            case "kept" -> res.andExpect(jsonPath("$.segmentId").value(segment.toString()));
            case "cleared" -> res.andExpect(jsonPath("$.segmentId").value(nullValue()));
            default -> res.andExpect(jsonPath("$.segmentId").value(segmentAfter));
        }
        res.andExpect(eventKept ? jsonPath("$.eventId").value(event.toString()) : jsonPath("$.eventId").value(nullValue()));
    }

    static Stream<Arguments> patchLinkCases() {
        String replacement = "00000000-0000-0000-0000-00000000beef";
        return Stream.of(
                Arguments.of("explicit null clears both links",
                        "{\"name\":\"Step 0\",\"segmentId\":null,\"eventId\":null}", "cleared", false),
                Arguments.of("absent fields keep both links", "{\"name\":\"Step 0\"}", "kept", true),
                Arguments.of("a supplied segment id replaces the segment only",
                        "{\"segmentId\":\"" + replacement + "\"}", replacement, true));
    }

    /** Draft-only delete: 204 for an own draft, the no-leak 404 for another org's, 409 for a non-draft. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "own draft,         own,   draft, 204, ",
            "other org's draft, other, draft, 404, NOT_FOUND",
            "own sent campaign, own,   sent,  409, INVALID_STATE"})
    void delete_isDraftOnly_andOrgScoped(String label, String whose, String status, int expected, String code)
            throws Exception {
        AuthPrincipal campaignOwner = "own".equals(whose) ? owner : otherOwner;
        UUID id = draft(campaignOwner, null, null).id();
        if (!"draft".equals(status)) service.forceStatusForTest(id, status);

        var res = mvc.perform(delete("/api/v1/marketing/campaigns/{id}", id).with(auth(owner)))
                .andExpect(status().is(expected));
        if (code != null) res.andExpect(jsonPath("$.error.code").value(code));

        Optional<Campaign> row = campaigns.findByIdAndOrgId(id, campaignOwner.orgId());
        if (expected == 204) {
            assertThat(row).isEmpty();
        } else {
            assertThat(row).get().extracting(Campaign::getStatus).isEqualTo(status);
        }
    }

    /**
     * mkt-edge-7 (P2): with no constraint and no @Valid, a 201-character subject reached Postgres as a
     * VARCHAR(200) overflow and came back as a fieldless "Request violates a data constraint".
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "create, subject,     201",
            "patch,  preheader,   201",
            "create, templateKey, 65"})
    void overlongField_isAFielded400_andWritesNothing(String route, String field, int length) throws Exception {
        UUID existing = draft(owner, null, null).id();
        Map<String, String> body = "create".equals(route)
                ? Map.of("channel", "email", "name", "Launch", field, "x".repeat(length))
                : Map.of(field, "x".repeat(length));
        MockHttpServletRequestBuilder req = "create".equals(route)
                ? post("/api/v1/marketing/campaigns")
                : patch("/api/v1/marketing/campaigns/{id}", existing);

        mvc.perform(req.with(auth(owner)).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());

        assertThat(service.list(owner, null, null, 0, 50)).extracting(s -> s.id()).containsExactly(existing);
        assertThat(service.get(owner, existing).preheader()).isNull();
    }

    /** name is deliberately unconstrained: the service clips it, so a @Size there would turn a 201 into a 400. */
    @Test
    void create_overlong_name_still_succeeds() throws Exception {
        String body = om.writeValueAsString(Map.of("channel", "email", "name", "n".repeat(300)));
        mvc.perform(post("/api/v1/marketing/campaigns").with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "no Idempotency-Key, '',    draft, 400, MISSING_IDEMPOTENCY_KEY",
            "not a draft,        key-1, sent,  409, INVALID_STATE"})
    void send_refusals(String label, String key, String status, int expected, String code) throws Exception {
        UUID id = draft(owner, null, null).id();
        if (!"draft".equals(status)) service.forceStatusForTest(id, status);
        MockHttpServletRequestBuilder req = post("/api/v1/marketing/campaigns/{id}/send", id).with(auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (!key.isEmpty()) req.header("Idempotency-Key", key);

        mvc.perform(req)
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    @Test
    void send_valid_returns202_withTheStoredSendTime() throws Exception {
        UUID id = draft(owner, null, null).id();

        mvc.perform(post("/api/v1/marketing/campaigns/{id}/send", id).with(auth(owner))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scheduledAt").isNotEmpty())
                .andExpect(jsonPath("$.armed").value(false));
    }

    /** The remaining campaign routes over the real service answer their documented status. */
    @ParameterizedTest(name = "{0} {1} on a {2} campaign")
    @CsvSource({
            "GET,  '',                draft,     200",
            "POST, /duplicate,        sent,      201",
            "POST, /preview-audience, draft,     200",
            "POST, /test-send,        draft,     204",
            "POST, /cancel,           scheduled, 200",
            "POST, /retry,            failed,    202"})
    void campaignRoutes_answerTheirStatus(String method, String suffix, String status, int expected) throws Exception {
        flips.set(marketingProps, "fromAddress", "contact@imin.support");
        UUID id = service.create(owner, new CreateCampaignRequest("email", "Routes", null, null,
                "Subject", "Pre", "Body", null)).id();
        if (!"draft".equals(status)) service.forceStatusForTest(id, status);
        String path = "/api/v1/marketing/campaigns/" + id + suffix;
        MockHttpServletRequestBuilder req = "GET".equals(method) ? get(path)
                : post(path).contentType(MediaType.APPLICATION_JSON).content("{}");

        mvc.perform(req.with(auth(owner))).andExpect(status().is(expected));
    }

    /** Another org's campaign is the no-leak 404 on every state-changing route, and stays as it was. */
    @ParameterizedTest(name = "{0} on another org's {1} campaign")
    @CsvSource({"/cancel, scheduled", "/cancel, sending", "/retry, failed", "/send, draft"})
    void anotherOrgsCampaign_isNotFound_andUntouched(String suffix, String status) throws Exception {
        UUID id = draft(otherOwner, null, null).id();
        if (!"draft".equals(status)) service.forceStatusForTest(id, status);

        mvc.perform(post("/api/v1/marketing/campaigns/" + id + suffix).with(auth(owner))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(campaigns.findByIdAndOrgId(id, otherOwner.orgId())).get()
                .extracting(Campaign::getStatus).isEqualTo(status);
    }

    @Test
    void emailTemplates_routeAnswersOk() throws Exception {
        mvc.perform(get("/api/v1/marketing/email-templates").with(auth(owner)))
                .andExpect(status().isOk());
    }

    private CampaignDto draft(AuthPrincipal p, UUID segmentId, UUID eventId) {
        return service.create(p, new CreateCampaignRequest("email", "Launch night", segmentId, eventId,
                null, null, null, null));
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
