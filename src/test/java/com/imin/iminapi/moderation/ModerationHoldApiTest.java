package com.imin.iminapi.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.ModerationHold;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.payout.PayoutRun;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.payout.PayoutRunStatus;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The staff surface for moderation holds: who may use it, what a hold records, and how a lift behaves. */
@IminIntegrationTest
class ModerationHoldApiTest {

    private static final String HOLDS = "/api/v1/internal/moderation/holds";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired ModerationProperties props;
    @Autowired StripeProperties stripeProps;
    @Autowired PropertyFlips flips;
    @Autowired ModerationHoldRepository holds;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired OrganizationRepository orgs;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired CampaignRepository campaigns;
    @Autowired RecordingEmailService mail;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    private final ObjectMapper json = new ObjectMapper();
    private final List<UUID> orgIds = new ArrayList<>();
    private Organization target;
    private User targetOwner;
    private Event targetEvent;
    private User staff;
    private User otherStaff;

    @BeforeEach
    void setUp() {
        target = org();
        targetOwner = fx.owner(target);
        targetEvent = fx.event(target, targetOwner, EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        Organization imin = org();
        staff = fx.owner(imin);
        otherStaff = fx.owner(imin);
    }

    @AfterEach
    void tearDown() {
        for (UUID id : orgIds) jdbc.update("DELETE FROM moderation_holds WHERE org_id = ?", id);
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    // ── T1 ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "staff list {0}, caller {1} → {2}")
    @CsvSource({
            "blank,  staff,     404",
            "listed, organizer, 404",
            "listed, gate,      404",
            "listed, staff,     200"})
    void only_listed_staff_reach_the_moderation_endpoints(String list, String caller, int expected) throws Exception {
        if ("listed".equals(list)) staffList(staff);
        ModerationHold existing = savedHold(target.getId(), null, true, false);
        AuthPrincipal p = switch (caller) {
            case "staff" -> fx.principal(staff);
            case "organizer" -> fx.principal(targetOwner);
            default -> AuthPrincipal.forGate(target.getId(), UUID.randomUUID());
        };
        int before = holds.findByOrgIdOrderByPlacedAtDesc(target.getId()).size();

        ResultActions placed = mvc.perform(post(HOLDS).with(auth(p)).contentType(MediaType.APPLICATION_JSON)
                .content(placeBody(target.getId(), targetEvent.getId(), true, false, "fraud", "Chargebacks", false)));
        ResultActions listed = mvc.perform(get(HOLDS).param("orgId", target.getId().toString()).with(auth(p)));
        ResultActions lifted = mvc.perform(post(HOLDS + "/" + existing.getId() + "/lift").with(auth(p))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        if (expected == 404) {
            placed.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            listed.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            lifted.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            assertThat(holds.findByOrgIdOrderByPlacedAtDesc(target.getId())).as("nothing placed").hasSize(before);
            assertThat(holds.findById(existing.getId()).orElseThrow().getLiftedAt()).as("nothing lifted").isNull();
        } else {
            placed.andExpect(status().isCreated());
            listed.andExpect(status().isOk()).andExpect(jsonPath("$[0].orgId").value(target.getId().toString()));
            lifted.andExpect(status().isOk());
            assertThat(holds.findById(existing.getId()).orElseThrow().getLiftedBy()).isEqualTo(staff.getId());
        }
    }

    // ── T2 ──────────────────────────────────────────────────────────────────────────

    @Test
    void staff_place_an_event_hold_recording_who_why_and_when() throws Exception {
        staffList(staff);
        manualPayouts(true, true);
        Instant at = Instant.parse("2026-10-12T22:30:00.123456Z");
        clock.setInstant(at);

        String body = mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody(target.getId(), targetEvent.getId(), true, true, "reported_event",
                                "  Reported as a fake lineup  ", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hold.scope").value("event"))
                .andExpect(jsonPath("$.hold.active").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).at("/hold/id").asText());

        ModerationHold h = holds.findById(id).orElseThrow();
        assertThat(h.getOrgId()).isEqualTo(target.getId());
        assertThat(h.getEventId()).isEqualTo(targetEvent.getId());
        assertThat(h.isSuspendSales()).isTrue();
        assertThat(h.isHoldPayouts()).isTrue();
        assertThat(h.getGround()).isEqualTo("reported_event");
        assertThat(h.getFacts()).isEqualTo("Reported as a fake lineup");
        assertThat(h.isReasonWithheld()).isFalse();
        assertThat(h.getPlacedBy()).isEqualTo(staff.getId());
        assertThat(h.getPlacedByEmail()).isEqualTo(staff.getEmail());
        assertThat(h.getPlacedAt()).isEqualTo(at);
        assertThat(h.getLiftedAt()).isNull();

        JsonNode list = json.readTree(mvc.perform(get(HOLDS).param("orgId", target.getId().toString())
                        .with(auth(fx.principal(staff))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("placedBy").asText()).isEqualTo(staff.getId().toString());
        assertThat(list.get(0).get("placedAt").asText()).isEqualTo(at.toString());
        assertThat(list.get(0).get("ground").asText()).isEqualTo("reported_event");
        assertThat(list.get(0).has("noticeSentAt")).isTrue();
    }

    // ── T3 ──────────────────────────────────────────────────────────────────────────

    @Test
    void an_event_of_another_org_is_404_and_writes_nothing() throws Exception {
        staffList(staff);
        Organization other = org();
        Event othersEvent = fx.event(other, fx.owner(other), EventStatus.LIVE, clock.instant().plusSeconds(86_400));

        mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody(target.getId(), othersEvent.getId(), true, false, "fraud", "typo", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Event not found"));

        assertThat(holds.findByOrgIdOrderByPlacedAtDesc(target.getId())).isEmpty();
        assertThat(holds.findByOrgIdOrderByPlacedAtDesc(other.getId())).isEmpty();
    }

    // ── T4 ──────────────────────────────────────────────────────────────────────────

    @Test
    void a_hold_is_lifted_once_and_keeps_its_first_lifter() throws Exception {
        staffList(staff, otherStaff);
        ModerationHold h = savedHold(target.getId(), null, true, true);
        Instant first = Instant.parse("2026-10-13T08:00:00Z");
        clock.setInstant(first);

        mvc.perform(post(HOLDS + "/" + h.getId() + "/lift").with(auth(fx.principal(staff)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Report withdrawn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liftedBy").value(staff.getId().toString()))
                .andExpect(jsonPath("$.active").value(false));

        clock.setInstant(first.plusSeconds(60));
        mvc.perform(post(HOLDS + "/" + h.getId() + "/lift").with(auth(fx.principal(otherStaff)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"second\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        ModerationHold after = holds.findById(h.getId()).orElseThrow();
        assertThat(after.getLiftedBy()).isEqualTo(staff.getId());
        assertThat(after.getLiftedByEmail()).isEqualTo(staff.getEmail());
        assertThat(after.getLiftedAt()).isEqualTo(first);
        assertThat(after.getLiftNote()).isEqualTo("Report withdrawn");
    }

    // ── T5 ──────────────────────────────────────────────────────────────────────────

    @Test
    void organizer_sees_own_active_holds_and_no_reason_when_withheld() throws Exception {
        ModerationHold orgHold = savedHold(target.getId(), null, true, false);   // placed 2026-10-01
        ModerationHold withheld = hold(target.getId(), targetEvent.getId(), false, true, true,
                Instant.parse("2026-10-05T09:00:00Z"));
        ModerationHold lifted = savedHold(target.getId(), null, false, true);
        holds.lift(lifted.getId(), UUID.randomUUID(), null, null, Instant.parse("2026-10-06T09:00:00Z"));
        Organization other = org();
        savedHold(other.getId(), null, true, true);

        mvc.perform(get("/api/v1/org/suspension").with(auth(fx.principal(targetOwner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds.length()").value(2))
                .andExpect(jsonPath("$.holds[0].id").value(withheld.getId().toString()))
                .andExpect(jsonPath("$.holds[0].scope").value("event"))
                .andExpect(jsonPath("$.holds[0].eventId").value(targetEvent.getId().toString()))
                .andExpect(jsonPath("$.holds[0].eventName").value(targetEvent.getName()))
                .andExpect(jsonPath("$.holds[0].sales").value(false))
                .andExpect(jsonPath("$.holds[0].payouts").value(true))
                .andExpect(jsonPath("$.holds[0].reasonWithheld").value(true))
                .andExpect(jsonPath("$.holds[0].ground").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.holds[0].reason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.holds[0].placedAt").value("2026-10-05T09:00:00Z"))
                .andExpect(jsonPath("$.holds[1].id").value(orgHold.getId().toString()))
                .andExpect(jsonPath("$.holds[1].scope").value("org"))
                .andExpect(jsonPath("$.holds[1].eventName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.holds[1].ground").value("aup_breach"))
                .andExpect(jsonPath("$.holds[1].reason").value("seed"))
                .andExpect(jsonPath("$.holds[1].reasonWithheld").value(false))
                .andExpect(jsonPath("$.holds[1].appealEmail").value("hello@imin.wtf"));

        mvc.perform(get("/api/v1/org/suspension").with(auth(AuthPrincipal.forGate(target.getId(), UUID.randomUUID()))))
                .andExpect(status().isNotFound());
    }

    // ── T6 ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} hold, run {1} on {2} event, payouts {3} → {4}")
    @CsvSource({
            "event, none,      held,  true,  ''",
            "event, PLANNED,   held,  true,  payout_in_flight",
            "event, SUBMITTED, held,  true,  payout_in_flight",
            "event, RETRYING,  held,  true,  payout_in_flight",
            "event, PAID,      held,  true,  ''",
            "event, SUBMITTED, other, true,  ''",
            "org,   SUBMITTED, other, true,  payout_in_flight",
            "org,   none,      held,  true,  ''",
            "event, SUBMITTED, held,  false, ''"})
    void placing_a_payout_hold_warns_when_a_payout_is_already_moving(String scope, String run, String runOn,
                                                                     boolean payouts, String expected)
            throws Exception {
        staffList(staff);
        manualPayouts(true, true);
        Event other = fx.event(target, targetOwner, EventStatus.PAST, clock.instant().minusSeconds(86_400));
        if (!"none".equals(run)) payoutRun("held".equals(runOn) ? targetEvent : other, PayoutRunStatus.valueOf(run));

        String body = mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody(target.getId(), "org".equals(scope) ? null : targetEvent.getId(),
                                !payouts, payouts, "fraud", "x", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        List<String> warnings = new ArrayList<>();
        json.readTree(body).get("warnings").forEach(w -> warnings.add(w.asText()));
        assertThat(warnings).containsExactlyElementsOf(expected.isEmpty() ? List.of() : List.of(expected));
    }

    @ParameterizedTest(name = "platform manual {0}, org manual {1}")
    @CsvSource({"false, true", "true, false"})
    void a_payout_hold_is_refused_while_stripe_pays_out_on_its_own(boolean globalManual, boolean orgManual)
            throws Exception {
        staffList(staff);
        manualPayouts(globalManual, orgManual);

        mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody(target.getId(), targetEvent.getId(), true, true, "fraud", "x", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString(
                        "docs/ops/moderation-holds.md")));
        assertThat(holds.findByOrgIdOrderByPlacedAtDesc(target.getId())).as("nothing placed").isEmpty();

        // A sales-only hold promises nothing about payouts, so it is still allowed.
        mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody(target.getId(), targetEvent.getId(), true, false, "fraud", "x", false)))
                .andExpect(status().isCreated());
    }

    // ── request validation ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "orgId   | {\"suspendSales\":true,\"ground\":\"fraud\",\"facts\":\"x\"}",
            "measures| {\"orgId\":\"ORG\",\"suspendSales\":false,\"holdPayouts\":false,\"ground\":\"fraud\",\"facts\":\"x\"}",
            "ground  | {\"orgId\":\"ORG\",\"suspendSales\":true,\"ground\":\"rude\",\"facts\":\"x\"}",
            "facts   | {\"orgId\":\"ORG\",\"suspendSales\":true,\"ground\":\"fraud\",\"facts\":\"   \"}",
            "facts   | {\"orgId\":\"ORG\",\"suspendSales\":true,\"ground\":\"fraud\",\"facts\":\"LONG\"}",
            "body    | {not json"})
    void an_invalid_hold_is_400_naming_the_field_and_writes_nothing(String field, String body) throws Exception {
        staffList(staff);

        mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("ORG", target.getId().toString()).replace("LONG", "f".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());

        assertThat(holds.findByOrgIdOrderByPlacedAtDesc(target.getId())).isEmpty();
    }

    @Test
    void a_lift_note_over_1000_characters_is_400_and_lifts_nothing() throws Exception {
        staffList(staff);
        ModerationHold h = savedHold(target.getId(), null, true, false);

        mvc.perform(post(HOLDS + "/" + h.getId() + "/lift").with(auth(fx.principal(staff)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"" + "n".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.note").exists());

        assertThat(holds.findById(h.getId()).orElseThrow().getLiftedAt()).isNull();
    }

    @Test
    void a_non_staff_caller_gets_404_whatever_the_request_holds() throws Exception {
        staffList(staff);
        AuthPrincipal organizer = fx.principal(targetOwner);

        mvc.perform(post(HOLDS).with(auth(organizer)).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isNotFound());
        mvc.perform(post(HOLDS + "/not-a-uuid/lift").with(auth(organizer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isNotFound());
        mvc.perform(get(HOLDS).param("orgId", "not-a-uuid").with(auth(organizer)))
                .andExpect(status().isNotFound());
    }

    // ── T7 ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "withheld {0}, another hold active {1}, {2} hold, {3} campaign")
    @CsvSource({
            "false, false, org,   scheduled",
            "false, true,  org,   scheduled",
            "true,  false, org,   scheduled",
            "true,  true,  org,   scheduled",
            "false, false, org,   dead-failed",
            "false, false, event, scheduled"})
    void placing_and_lifting_email_the_organizer(boolean withheld, boolean otherActive, String scope, String campaign)
            throws Exception {
        boolean eventScope = "event".equals(scope);
        staffList(staff);
        targetOwner.setLocale("fr");
        users.save(targetOwner);
        target.setTimezone("Europe/Paris");
        orgs.save(target);
        if (otherActive) savedHold(target.getId(), targetEvent.getId(), false, true);
        if ("dead-failed".equals(campaign)) {
            deadFailedCampaign(targetEvent);   // 3 attempts spent: the dispatcher never sends it again
        } else {
            scheduledCampaign(targetEvent);
        }
        // 22:30 UTC is already 13 October in Paris.
        clock.setInstant(Instant.parse("2026-10-12T22:30:00Z"));
        String to = target.getContactEmail();

        String placed = mvc.perform(post(HOLDS).with(auth(fx.principal(staff))).contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody(target.getId(), eventScope ? targetEvent.getId() : null, true, false, "fraud",
                                "Faux line-up signalé", withheld)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(placed).at("/hold/id").asText());
        AsyncDrain.drain(ticketEmailExecutor);

        RecordingEmailService.SentEmail notice = only(to);
        String scopeName = eventScope ? targetEvent.getName() : target.getName();
        assertThat(notice.subject()).isEqualTo("La vente de billets est suspendue – " + scopeName);
        assertThat(notice.text())
                .contains(eventScope ? "Cela concerne " + targetEvent.getName() + "." : "Cela concerne tous vos événements.")
                .contains("Depuis le 13 octobre 2026")
                .contains("Les acheteurs ne peuvent pas lancer de nouvel achat.")
                .contains("Les campagnes e-mail programmées sont suspendues tant que ce blocage est en place")
                .contains("Cette décision a été prise par une personne d'imin.")
                .contains("Pour la contester, écrivez à hello@imin.wtf dans les 14 jours suivant notre notification");
        assertThat(notice.html()).contains("hello@imin.wtf");
        if (withheld) {
            assertThat(notice.text())
                    .contains("imin ne peut pas vous en donner le motif, car la loi ou une autorité ne le permet pas.")
                    .doesNotContain("Faux line-up").doesNotContain("Suspicion de fraude");
            assertThat(notice.html()).doesNotContain("Faux line-up");
        } else {
            assertThat(notice.text())
                    .contains("Motif\u00a0: Suspicion de fraude")
                    .contains("Précisions d'imin\u00a0: Faux line-up signalé")
                    .doesNotContain("ne peut pas vous en donner le motif");
        }
        assertThat(holds.findById(id).orElseThrow().getNoticeSentAt()).isEqualTo(Instant.parse("2026-10-12T22:30:00Z"));

        mail.clear();
        clock.setInstant(Instant.parse("2026-10-14T08:00:00Z"));
        mvc.perform(post(HOLDS + "/" + id + "/lift").with(auth(fx.principal(staff)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        AsyncDrain.drain(ticketEmailExecutor);

        RecordingEmailService.SentEmail liftNotice = only(to);
        assertThat(liftNotice.subject()).isEqualTo("Mesure levée – " + scopeName);
        assertThat(liftNotice.text())
                .contains("imin a levé cette mesure.")
                .contains("Mesure\u00a0: La vente de billets est suspendue")
                .contains("La vente peut reprendre, sauf si une autre mesure s'applique encore.");
        String others = "D'autres mesures restent en place sur votre compte.";
        String campaigns = "Les campagnes suspendues vont maintenant partir comme prévu";
        if (otherActive) {
            assertThat(liftNotice.text()).as("told the rest still applies").contains(others);
        } else {
            assertThat(liftNotice.text()).as("not told of measures that do not exist").doesNotContain(others);
        }
        if (!otherActive && "scheduled".equals(campaign)) {
            assertThat(liftNotice.text()).as("the campaign is about to send").contains(campaigns);
        } else {
            assertThat(liftNotice.text()).as("nothing the dispatcher would send").doesNotContain(campaigns);
        }
        assertThat(holds.findById(id).orElseThrow().getLiftNoticeSentAt()).isEqualTo(Instant.parse("2026-10-14T08:00:00Z"));
    }

    private RecordingEmailService.SentEmail only(String to) {
        List<RecordingEmailService.SentEmail> sent = mail.sent().stream().filter(m -> to.equals(m.to())).toList();
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private Organization org() {
        Organization o = fx.org();
        orgIds.add(o.getId());
        return o;
    }

    private void manualPayouts(boolean global, boolean org) {
        flips.set(stripeProps, "payoutScheduleManual", global);
        target.setStripePayoutScheduleManual(org);
        target.setStripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        target = orgs.save(target);
    }

    private void scheduledCampaign(Event e) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(e.getOrgId());
        c.setEventId(e.getId());
        c.setChannel("email");
        c.setName("c");
        c.setStatus("scheduled");
        c.setSubject("S");
        c.setBodyMd("B");
        c.setScheduledAt(Instant.now().plusSeconds(30L * 86_400));   // never due inside this test
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
    }

    private void deadFailedCampaign(Event e) {
        scheduledCampaign(e);
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 3 WHERE org_id = ?", e.getOrgId());
    }

    private void staffList(User... users) {
        flips.set(props, "staffUserIds", Set.copyOf(Arrays.stream(users).map(User::getId).toList()));
    }

    private ModerationHold savedHold(UUID orgId, UUID eventId, boolean sales, boolean payouts) {
        return hold(orgId, eventId, sales, payouts, false, Instant.parse("2026-10-01T10:00:00Z"));
    }

    private ModerationHold hold(UUID orgId, UUID eventId, boolean sales, boolean payouts, boolean withheld,
                                Instant placedAt) {
        ModerationHold h = new ModerationHold();
        h.setId(UUID.randomUUID());
        h.setOrgId(orgId);
        h.setEventId(eventId);
        h.setSuspendSales(sales);
        h.setHoldPayouts(payouts);
        h.setGround("aup_breach");
        h.setFacts("seed");
        h.setReasonWithheld(withheld);
        h.setPlacedBy(UUID.randomUUID());
        h.setPlacedAt(placedAt);
        return holds.save(h);
    }

    private void payoutRun(Event e, PayoutRunStatus status) {
        PayoutRun r = new PayoutRun();
        r.setOrgId(e.getOrgId());
        r.setEventId(e.getId());
        r.setStripeAccountId(target.getStripeAccountId());
        r.setAmountMinor(1_000L);
        r.setCurrency("eur");
        r.setStatus(status);
        r.setIdempotencyKey("evt:" + e.getId() + ":attempt:1:" + UUID.randomUUID());
        payoutRuns.save(r);
    }

    private static String placeBody(UUID orgId, UUID eventId, boolean sales, boolean payouts, String ground,
                                    String facts, boolean withheld) {
        return "{\"orgId\":\"" + orgId + "\","
                + (eventId == null ? "" : "\"eventId\":\"" + eventId + "\",")
                + "\"suspendSales\":" + sales + ",\"holdPayouts\":" + payouts + ","
                + "\"ground\":\"" + ground + "\",\"facts\":\"" + facts + "\",\"reasonWithheld\":" + withheld + "}";
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
