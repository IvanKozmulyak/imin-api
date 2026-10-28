package com.imin.iminapi.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.ModerationHold;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.push.ExpoPushSender;
import com.imin.iminapi.push.PushProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.NotifyReleaseSender;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import com.stripe.StripeClient;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A sales hold closes every way a buyer can start a purchase or learn about one, and only until it is lifted. */
@IminIntegrationTest
class SalesSuspensionEnforcementTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired TicketTierRepository tiers;
    @Autowired ModerationHoldRepository holds;
    @Autowired ModerationProperties props;
    @Autowired PropertyFlips flips;
    @Autowired StripeClient stripeClient;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;

    private final ObjectMapper json = new ObjectMapper();
    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    /** Letters only, so city and genre key normalisation keeps it. */
    private String tag;

    @BeforeEach
    void setUp() {
        org = org();
        owner = fx.owner(org);
        tag = randomLetters();
    }

    @AfterEach
    void tearDown() {
        for (UUID id : orgIds) jdbc.update("DELETE FROM moderation_holds WHERE org_id = ?", id);
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    // ── T10 ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"quote", "hosted-paid", "hosted-free", "native"})
    void every_purchase_entry_point_refuses_a_suspended_event(String entry) throws Exception {
        Event e = onSaleEvent(org);
        TicketTier t = fx.tier(e, "hosted-free".equals(entry) ? 0 : 2500, 50);
        hold(org.getId(), e.getId(), true, false);

        String path = switch (entry) {
            case "quote" -> "quote";
            case "native" -> "payment-intent";
            default -> "checkout";
        };
        String body = "{\"tierId\":\"" + t.getId() + "\",\"quantity\":1"
                + ("hosted-free".equals(entry) ? ",\"email\":\"" + fx.email("free") + "\"" : "") + "}";

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/" + path)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SALES_SUSPENDED"))
                .andExpect(jsonPath("$.error.message").value("Ticket sales for this event are paused"));

        verifyNoInteractions(stripeClient);
        assertThat(jdbc.queryForObject("SELECT reserved + sold FROM ticket_tiers WHERE id = ?", Integer.class, t.getId()))
                .as("no inventory taken").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_reservations WHERE tier_id = ?", Integer.class,
                t.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE event_id = ?", Integer.class, e.getId()))
                .as("no free tickets issued").isZero();
    }

    // ── T11 ─────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void listing_and_facets_leave_out_suspended_events() throws Exception {
        Organization other = org();
        Event suspended = listed(org, "held" + tag);          // event-level sales hold
        Event payoutsOnly = listed(org, "open" + tag);        // payouts-only hold: still sells
        Event orgWide = listed(other, "held" + tag);          // org-level sales hold on another org
        ModerationHold eventHold = hold(org.getId(), suspended.getId(), true, false);
        hold(org.getId(), payoutsOnly.getId(), false, true);
        ModerationHold orgHold = hold(other.getId(), null, true, false);

        assertThat(listedIds()).containsExactly(payoutsOnly.getId().toString());
        assertThat(cityCount()).as("the city chip counts only the buyable event").isEqualTo(1);
        assertThat(genres()).contains("open" + tag).doesNotContain("held" + tag);

        holds.lift(eventHold.getId(), UUID.randomUUID(), null, null, clock.instant());
        holds.lift(orgHold.getId(), UUID.randomUUID(), null, null, clock.instant());

        assertThat(listedIds()).as("lifted holds no longer hide anything").containsExactlyInAnyOrder(
                suspended.getId().toString(), payoutsOnly.getId().toString(), orgWide.getId().toString());
        assertThat(cityCount()).isEqualTo(3);
        assertThat(genres()).contains("open" + tag, "held" + tag);
    }

    private Set<String> listedIds() throws Exception {
        JsonNode page = json.readTree(mvc.perform(get("/api/v1/public/events").param("city", "City" + tag))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Set<String> ids = new HashSet<>();
        page.get("items").forEach(i -> ids.add(i.get("id").asText()));
        return ids;
    }

    private long cityCount() throws Exception {
        long count = 0;
        for (JsonNode c : json.readTree(mvc.perform(get("/api/v1/public/events/cities"))
                .andReturn().getResponse().getContentAsString())) {
            if (("City" + tag).equals(c.get("city").asText())) count = c.get("eventCount").asLong();
        }
        return count;
    }

    private Set<String> genres() throws Exception {
        Set<String> genres = new HashSet<>();
        json.readTree(mvc.perform(get("/api/v1/public/events/genres")).andReturn().getResponse().getContentAsString())
                .forEach(g -> genres.add(g.asText()));
        return genres;
    }

    // ── T12 ─────────────────────────────────────────────────────────────────────────

    @Test
    void detail_page_reports_sales_suspended_with_no_tiers_until_lifted() throws Exception {
        Event e = onSaleEvent(org);
        TicketTier t = fx.tier(e, 2500, 50);
        ModerationHold h = hold(org.getId(), null, true, false);

        for (String unavailable : List.of("false", "true")) {
            mvc.perform(get("/api/v1/public/events/" + e.getId()).param("includeUnavailable", unavailable))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.salesSuspended").value(true))
                    .andExpect(jsonPath("$.tiers").isEmpty());
        }

        holds.lift(h.getId(), UUID.randomUUID(), null, null, clock.instant());

        mvc.perform(get("/api/v1/public/events/" + e.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesSuspended").value(false))
                .andExpect(jsonPath("$.tiers[0].id").value(t.getId().toString()));
    }

    // ── T13 ─────────────────────────────────────────────────────────────────────────

    @Autowired NotifySubscriptionRepository subscriptions;

    @Autowired com.imin.iminapi.repository.NotifyReleaseRepository releases;
    @Autowired SuppressionRepository suppressions;
    @Autowired RecordingEmailService mail;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired EmailProperties emailProps;
    @Autowired ExpoPushSender push;
    @Autowired BuyerPushDeviceRepository pushDevices;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired BuyerNotificationPreferenceRepository pushPrefs;
    @Autowired UnsubscribeTokenService unsubscribeTokens;

    /** Built by hand: the bean's sweep is behind a 10 s ShedLock, so a second sweep in one test would be skipped. */
    @Test
    @Transactional
    void no_release_email_for_a_suspended_event_until_lifted() {
        NotifyReleaseSender sender = new NotifyReleaseSender(subscriptions, events, tiers, suppressions, mail,
                renderer, emailProps, clock, new PushProperties(), push, pushDevices, buyerEmails, pushPrefs,
                unsubscribeTokens, holds, releases, new com.imin.iminapi.service.event.NotifyProperties());
        Event e = onSaleEvent(org);
        fx.tier(e, 2500, 50);
        String address = fx.email("watcher");
        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail(address);
        subscriptions.save(s);
        ModerationHold h = hold(org.getId(), e.getId(), true, false);

        sender.sweep();

        assertThat(mail.sent()).noneMatch(m -> address.equals(m.to()));
        assertThat(subscriptions.findById(s.getId()).orElseThrow().getNotifiedAt()).as("kept pending").isNull();

        holds.lift(h.getId(), UUID.randomUUID(), null, null, clock.instant());
        sender.sweep();

        assertThat(mail.sent()).filteredOn(m -> address.equals(m.to())).hasSize(1);
        assertThat(subscriptions.findById(s.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    // ── T14 ─────────────────────────────────────────────────────────────────────────

    @Test
    void lifting_one_of_two_holds_keeps_sales_refused() throws Exception {
        User staff = fx.owner(org());
        flips.set(props, "staffUserIds", Set.of(staff.getId()));
        Event e = onSaleEvent(org);
        TicketTier t = fx.tier(e, 2500, 50);
        UUID orgHold = place(staff, org.getId(), null);
        UUID eventHold = place(staff, org.getId(), e.getId());
        String quote = "{\"tierId\":\"" + t.getId() + "\",\"quantity\":1}";

        lift(staff, orgHold);
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON).content(quote))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SALES_SUSPENDED"));

        lift(staff, eventHold);
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON).content(quote))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPriceMinor").value(2500));
    }

    // ── Marketing sends stop with the hold ──────────────────────────────────────────

    @Test
    void campaigns_under_a_hold_are_not_claimed_until_it_is_lifted() {
        Instant now = Instant.parse("2026-07-14T12:00:00Z");   // midday in a UTC org: never quiet hours
        org.setTimezone("UTC");
        orgs.save(org);
        Event held = onSaleEvent(org);
        Event free = onSaleEvent(org);
        Campaign forHeldEvent = campaign(org.getId(), held.getId());
        Campaign forOtherEvent = campaign(org.getId(), free.getId());
        ModerationHold eventHold = hold(org.getId(), held.getId(), true, false);

        assertThat(dispatcher.claimDueCampaignIds(now))
                .contains(forOtherEvent.getId())
                .doesNotContain(forHeldEvent.getId());

        ModerationHold orgHold = hold(org.getId(), null, false, true);   // any measure on the org stops every send
        assertThat(dispatcher.claimDueCampaignIds(now)).doesNotContain(forHeldEvent.getId(), forOtherEvent.getId());

        holds.lift(eventHold.getId(), UUID.randomUUID(), null, null, clock.instant());
        holds.lift(orgHold.getId(), UUID.randomUUID(), null, null, clock.instant());
        assertThat(dispatcher.claimDueCampaignIds(now)).contains(forHeldEvent.getId(), forOtherEvent.getId());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private Organization org() {
        Organization o = fx.org();
        orgIds.add(o.getId());
        return o;
    }

    private Event onSaleEvent(Organization o) {
        User creator = o.getId().equals(org.getId()) ? owner : fx.owner(o);
        Event e = fx.event(o, creator, EventStatus.LIVE, clock.instant().plus(30, ChronoUnit.DAYS));
        e.setPublishedAt(clock.instant().minusSeconds(3600));
        return events.save(e);
    }

    private Event listed(Organization o, String genre) {
        Event e = onSaleEvent(o);
        e.setGenre(genre);
        e.setVenueCity("City" + tag);
        e.setVenueCountry("DE");
        e = events.save(e);
        fx.tier(e, 2500, 50);
        return e;
    }

    private ModerationHold hold(UUID orgId, UUID eventId, boolean sales, boolean payouts) {
        ModerationHold h = new ModerationHold();
        h.setId(UUID.randomUUID());
        h.setOrgId(orgId);
        h.setEventId(eventId);
        h.setSuspendSales(sales);
        h.setHoldPayouts(payouts);
        h.setGround("reported_event");
        h.setFacts("fixture");
        h.setPlacedBy(UUID.randomUUID());
        h.setPlacedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return holds.saveAndFlush(h);
    }

    private UUID place(User staff, UUID orgId, UUID eventId) throws Exception {
        String body = "{\"orgId\":\"" + orgId + "\"," + (eventId == null ? "" : "\"eventId\":\"" + eventId + "\",")
                + "\"suspendSales\":true,\"holdPayouts\":false,\"ground\":\"fraud\",\"facts\":\"x\"}";
        String out = mvc.perform(post("/api/v1/internal/moderation/holds").with(auth(fx.principal(staff)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(out).at("/hold/id").asText());
    }

    private void lift(User staff, UUID holdId) throws Exception {
        mvc.perform(post("/api/v1/internal/moderation/holds/" + holdId + "/lift").with(auth(fx.principal(staff)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private Campaign campaign(UUID orgId, UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setEventId(eventId);
        c.setChannel("email");
        c.setName("c");
        c.setStatus("scheduled");
        c.setSubject("S");
        c.setBodyMd("B");
        c.setScheduledAt(Instant.parse("2016-07-14T12:00:00Z"));
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }

    private static String randomLetters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return sb.toString();
    }
}
