package com.imin.iminapi.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
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

import java.time.Duration;
import java.time.Instant;
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
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired SegmentRepository segments;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MutableClock clock;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired com.imin.iminapi.support.RecordingRateLimiter limiter;
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
        clock.reset();
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
            "create, templateKey, 65",
            "render-preview, subject, 201",
            "render-preview, bodyMd,  50001"})
    void overlongField_isAFielded400_andWritesNothing(String route, String field, int length) throws Exception {
        UUID existing = draft(owner, null, null).id();
        Map<String, String> body = "create".equals(route)
                ? Map.of("channel", "email", "name", "Launch", field, "x".repeat(length))
                : Map.of(field, "x".repeat(length));
        MockHttpServletRequestBuilder req = switch (route) {
            case "create" -> post("/api/v1/marketing/campaigns");
            case "render-preview" -> post("/api/v1/marketing/campaigns/{id}/render-preview", existing);
            default -> patch("/api/v1/marketing/campaigns/{id}", existing);
        };

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

    /** One click must not mail a footer-only shell, nobody, or a segment that no longer resolves. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "blank subject, subject",
            "blank body,    bodyMd",
            "no segment,    segmentId",
            "segment deleted after it was chosen, segmentId",
            "nobody in the segment can be emailed (unsubscribed), audience"})
    void send_refusesADraftThatIsNotReady(String label, String field) throws Exception {
        UUID segmentId = "no segment".equals(label) ? null
                : "segmentId".equals(field) ? segmentOf(owner.orgId(), "subscribed")
                : segmentOf(owner.orgId(), "audience".equals(field) ? "unsubscribed" : "subscribed");
        UUID id = service.create(owner, new CreateCampaignRequest("email", "Launch night", segmentId, null,
                "subject".equals(field) ? " " : "Subject", null,
                "bodyMd".equals(field) ? " " : "Body", null)).id();
        if (label.startsWith("segment deleted")) jdbc.update("DELETE FROM segments WHERE id = ?", segmentId);

        mvc.perform(post("/api/v1/marketing/campaigns/{id}/send", id).with(auth(owner))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());

        assertThat(campaigns.findByIdAndOrgId(id, owner.orgId())).get()
                .extracting(Campaign::getStatus).isEqualTo("draft");
    }

    private UUID segmentOf(UUID orgId, String consentStatus) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("ready"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("explicit");
        m = memberships.save(m);
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Ready " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds("[\"" + m.getMembershipId() + "\"]");
        return segments.save(seg).getId();
    }

    @Test
    void send_valid_returns202_withTheStoredSendTime() throws Exception {
        UUID id = service.create(owner, new CreateCampaignRequest("email", "Launch night",
                segmentOf(owner.orgId(), "subscribed"), null, "Subject", null, "Body", null)).id();

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

    /** Another org's campaign is the no-leak 404 on every state-changing route and the preview, and stays as it was. */
    @ParameterizedTest(name = "{0} on another org's {1} campaign")
    @CsvSource({"/cancel, scheduled", "/cancel, sending", "/retry, failed", "/send, draft", "/render-preview, draft"})
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

    /** The preview is email only, and only a draft's preview may carry unsaved text; a sent campaign previews as saved. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "SMS campaign                    | sms   | draft | {}                  | 400 | INVALID_STATE",
            "unsaved text on a sent campaign | email | sent  | '{\"subject\":\"x\"}' | 409 | INVALID_STATE",
            "saved text on a sent campaign   | email | sent  | {}                  | 200 | "})
    void renderPreview_refusals(String label, String channel, String status, String body, int expected, String code)
            throws Exception {
        UUID id = service.create(owner, new CreateCampaignRequest(channel, "Preview", null, null,
                "Subject", null, "Body", null)).id();
        if (!"draft".equals(status)) service.forceStatusForTest(id, status);

        var res = mvc.perform(post("/api/v1/marketing/campaigns/{id}/render-preview", id).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
        if (code != null) res.andExpect(jsonPath("$.error.code").value(code));
        else res.andExpect(jsonPath("$.subject").value("Subject"));
    }

    /** Previews are metered per user: past the bucket the next one is a 429. */
    @Test
    void renderPreview_pastItsBucket_is429() throws Exception {
        UUID id = draft(owner, null, null).id();
        limiter.limit("render-preview", 1);
        String path = "/api/v1/marketing/campaigns/" + id + "/render-preview";

        mvc.perform(post(path).with(auth(owner)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post(path).with(auth(owner)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(limiter.calls()).containsOnly(
                new com.imin.iminapi.support.RecordingRateLimiter.Call("render-preview", owner.userId().toString()));
    }

    /** Each email row carries its own sent/opened/clicked counts; SMS, drafts and a send that left nothing carry none. */
    @Test
    void listCarriesEachEmailCampaignsSentOpenedAndClickedCounts() throws Exception {
        Instant at = Instant.parse("2026-07-11T10:05:00Z");
        UUID emailA = sentCampaign("email", "sent");
        row(emailA, "delivered", at, at);
        row(emailA, "delivered", at, null);
        row(emailA, "sent", null, null);
        row(emailA, "pending", null, null);
        UUID sms = sentCampaign("sms", "sent");
        row(sms, "delivered", at, at);
        UUID draft = draft(owner, null, null).id();
        UUID emailB = sentCampaign("email", "sent");
        row(emailB, "bounced", null, null);
        row(emailB, "unsubscribed", at, null);
        UUID sentNothing = sentCampaign("email", "failed");
        row(sentNothing, "failed", null, null);

        String json = mvc.perform(get("/api/v1/marketing/campaigns").with(auth(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> r : om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<
                List<Map<String, Object>>>() {})) {
            byId.put((String) r.get("id"), r);
        }

        assertThat(counts(byId, emailA)).containsExactly(3, 2, 1);
        assertThat(counts(byId, emailB)).containsExactly(2, 1, 0);
        assertThat(counts(byId, sms)).as("sms").containsOnlyNulls();
        assertThat(counts(byId, draft)).as("draft").containsOnlyNulls();
        assertThat(counts(byId, sentNothing)).as("email that sent nothing").containsOnlyNulls();
    }

    private static List<Object> counts(Map<String, Map<String, Object>> byId, UUID id) {
        Map<String, Object> r = byId.get(id.toString());
        assertThat(r).as("list row " + id).isNotNull().containsKeys("sentCount", "openedCount", "clickedCount");
        return java.util.Arrays.asList(r.get("sentCount"), r.get("openedCount"), r.get("clickedCount"));
    }

    private UUID sentCampaign(String channel, String status) {
        UUID id = service.create(owner, new CreateCampaignRequest(channel, "Counts " + channel, null, null,
                null, null, null, null)).id();
        service.forceStatusForTest(id, status);
        return id;
    }

    private void row(UUID campaignId, String status, Instant openedAt, Instant clickedAt) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setStatus(status);
        r.setOpenedAt(openedAt);
        r.setClickedAt(clickedAt);
        recipients.save(r);
    }

    /** A mis-click must leave a full minute to Undo: the stored time is never sooner than now + 60 s. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "no scheduledAt,          ",
            "ten seconds ahead,       10",
            "thirty seconds past,     -30"})
    void sendNow_waitsOutTheUndoWindow(String label, Long offsetSeconds) throws Exception {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");   // 12:00 Paris
        orgTimezone(owner, "Europe/Paris");
        clock.setInstant(now);
        UUID id = readyCampaign(owner);
        UUID control = readyCampaign(owner);
        String body = offsetSeconds == null ? "{}" : "{\"scheduledAt\":\"" + now.plusSeconds(offsetSeconds) + "\"}";

        send(owner, id, body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scheduledAt").value(now.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.secondsUntilScheduled").value(60))
                .andExpect(jsonPath("$.quietUntil").value(nullValue()))
                .andExpect(jsonPath("$.timeZone").value("Europe/Paris"));
        jdbc.update("UPDATE campaigns SET status = 'scheduled', scheduled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minusSeconds(5)), control);

        assertThat(campaigns.findByIdAndOrgId(id, owner.orgId()).orElseThrow().getScheduledAt())
                .isEqualTo(now.plusSeconds(60));
        assertThat(dispatcher.claimDueCampaignIds(now.plusSeconds(30))).contains(control).doesNotContain(id);
        assertThat(dispatcher.claimDueCampaignIds(now.plusSeconds(61))).contains(id);
    }

    /** At 23:00 the screen must say when the mail really starts, in the org's zone, not that it is sending. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("quietHoursCases")
    void sendDuringQuietHours_saysWhenItStarts(String label, String timezone, String nowAt,
                                               String expectedUntil, String expectedZone) throws Exception {
        orgTimezone(owner, timezone);
        clock.setInstant(Instant.parse(nowAt));
        UUID id = readyCampaign(owner);

        var res = send(owner, id, "{}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.timeZone").value(expectedZone));
        if (expectedUntil == null) {
            res.andExpect(jsonPath("$.quietUntil").value(nullValue()));
        } else {
            res.andExpect(jsonPath("$.quietUntil").value(expectedUntil));
        }
    }

    static Stream<Arguments> quietHoursCases() {
        return Stream.of(
                Arguments.of("23:30 Paris waits for the next morning", "Europe/Paris",
                        "2026-07-14T21:30:00Z", "2026-07-15T07:00:00Z", "Europe/Paris"),
                // 22:30Z is already 15 July in Paris, so a UTC computation names the wrong day and hour.
                Arguments.of("00:30 Paris on a different calendar day than UTC", "Europe/Paris",
                        "2026-07-14T22:30:00Z", "2026-07-15T07:00:00Z", "Europe/Paris"),
                Arguments.of("noon Paris is not quiet", "Europe/Paris", "2026-07-14T10:00:00Z", null, "Europe/Paris"),
                Arguments.of("no org zone falls back to UTC", "", "2026-07-14T22:30:00Z",
                        "2026-07-15T09:00:00Z", "UTC"));
    }

    /** Undo puts the campaign back in drafts, editable, with nothing sent and the dispatcher never claiming it. */
    @Test
    void unschedule_returnsTheCampaignToDraftAndNothingIsSent() throws Exception {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        orgTimezone(owner, "Europe/Paris");
        clock.setInstant(now);
        UUID id = readyCampaign(owner);
        UUID control = readyCampaign(owner);
        send(owner, id, "{}").andExpect(status().isAccepted());
        send(owner, control, "{}").andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/marketing/campaigns/{id}/unschedule", id).with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.scheduledAt").value(nullValue()));

        assertThat(dispatcher.claimDueCampaignIds(now.plusSeconds(61))).contains(control).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_recipients WHERE campaign_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CAMPAIGN_SEND_UNDONE' "
                + "AND target_id = ?", Integer.class, id)).isEqualTo(1);
        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", id).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Edited after undo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Edited after undo"));
    }

    /** Undo only exists for a scheduled first attempt, for an admin, on the caller's own campaign. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "sending,                      sending,   0, own,   OWNER,  409, INVALID_STATE",
            "sent,                         sent,      0, own,   OWNER,  409, INVALID_STATE",
            "failed,                       failed,    0, own,   OWNER,  409, INVALID_STATE",
            "draft,                        draft,     0, own,   OWNER,  409, INVALID_STATE",
            "scheduled again by a retry,   scheduled, 1, own,   OWNER,  409, INVALID_STATE",
            "a member,                     scheduled, 0, own,   MEMBER, 403, FORBIDDEN",
            "another org's campaign,       scheduled, 0, other, OWNER,  404, NOT_FOUND"})
    void unschedule_refusals(String label, String status, int attempts, String whose, String role,
                             int expected, String code) throws Exception {
        AuthPrincipal campaignOwner = "own".equals(whose) ? owner : otherOwner;
        UUID id = draft(campaignOwner, null, null).id();
        if (!"draft".equals(status)) service.forceStatusForTest(id, status);
        jdbc.update("UPDATE campaigns SET attempts = ?, scheduled_at = CASE WHEN status = 'scheduled' "
                + "THEN now() + interval '1 hour' END WHERE id = ?", attempts, id);
        AuthPrincipal caller = new AuthPrincipal(owner.userId(), owner.orgId(), UserRole.valueOf(role), UUID.randomUUID());

        mvc.perform(post("/api/v1/marketing/campaigns/{id}/unschedule", id).with(auth(caller)))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.error.code").value(code));

        assertThat(campaigns.findByIdAndOrgId(id, campaignOwner.orgId())).get()
                .extracting(Campaign::getStatus).isEqualTo(status);
    }

    /** An old SMS draft becomes a sendable email once its channel can be patched. */
    @Test
    void patchChannel_turnsAnSmsDraftIntoASendableEmail() throws Exception {
        flips.set(marketingProps, "fromAddress", "contact@imin.support");
        UUID id = service.create(owner, new CreateCampaignRequest("sms", "Old text", null, null,
                null, null, null, null)).id();

        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", id).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"fax\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
        mvc.perform(patch("/api/v1/marketing/campaigns/{id}", id).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"subject\":\"Hello\",\"bodyMd\":\"Body\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("email"));

        mvc.perform(get("/api/v1/marketing/campaigns/{id}", id).with(auth(owner)))
                .andExpect(jsonPath("$.channel").value("email"));
        mvc.perform(post("/api/v1/marketing/campaigns/{id}/test-send", id).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
    }

    private void orgTimezone(AuthPrincipal p, String timezone) {
        jdbc.update("UPDATE organizations SET timezone = ? WHERE id = ?", timezone, p.orgId());
    }

    private UUID readyCampaign(AuthPrincipal p) {
        return service.create(p, new CreateCampaignRequest("email", "Launch night",
                segmentOf(p.orgId(), "subscribed"), null, "Subject", null, "Body", null)).id();
    }

    private org.springframework.test.web.servlet.ResultActions send(AuthPrincipal p, UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/marketing/campaigns/{id}/send", id).with(auth(p))
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
