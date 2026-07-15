package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for GET /api/v1/marketing/hub-metrics. Real H2 data across TWO orgs,
 * driven through MockMvc as org A. Asserts the DTO shape AND org scoping: org B's members /
 * suggestions / on-sale events / SMS opt-ins / campaigns never leak into A's counts.
 *
 * <p>Follows the {@code CrossOrgScopingTest} pattern — generated ids + the
 * {@code authentication()} MockMvc post-processor (same principal shape the production
 * {@code BearerTokenAuthFilter} produces), so no fixed-id merge hazard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class MarketingHubControllerTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired EventRepository events;
    @Autowired CampaignRepository campaigns;
    @Autowired FunnelEventRepository funnel;
    @Autowired JdbcTemplate jdbc;

    private UUID orgA;
    private UUID orgB;
    private Authentication authA;

    @BeforeEach
    void seed() {
        wipe();

        orgA = newOrg("hub-a");
        orgB = newOrg("hub-b");

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgA);
        owner.setRole(UserRole.OWNER);
        UUID ownerId = users.save(owner).getId();

        AuthPrincipal pA = new AuthPrincipal(ownerId, orgA, UserRole.OWNER, UUID.randomUUID());
        authA = new UsernamePasswordAuthenticationToken(
                pA, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        // ---- org A data ----
        // 3 subscribed+explicit memberships (all sendable) + 1 no-basis (excluded) → total 4.
        newMembership(orgA, "subscribed", "explicit", null, "never");
        newMembership(orgA, "subscribed", "explicit", null, "never");
        newMembership(orgA, "subscribed", "explicit", "+353871234567", "subscribed"); // sendable + sms
        newMembership(orgA, "subscribed", null, null, "never");                        // no lawful basis → excluded

        // 2 live 'suggested' Momentum suggestions (+1 non-suggested).
        UUID eventA = newOnSaleEvent(orgA, ownerId);
        newSuggestion(orgA, eventA, "launch_push", "suggested");
        newSuggestion(orgA, eventA, "urgency_72h", "suggested");
        newSuggestion(orgA, eventA, "slump", "dismissed"); // not waiting

        // 1 campaign in the last 30d with 2 attributed checkout sessions.
        Campaign c = newCampaign(orgA, Instant.now().minusSeconds(3_600L));
        beacon(eventA, FunnelEvent.STAGE_CHECKOUT_START, "anonA", c.getId().toString());
        beacon(eventA, FunnelEvent.STAGE_CHECKOUT_START, "anonB", c.getId().toString());
        // A campaign OLDER than 30d — its attribution must NOT be counted.
        Campaign old = newCampaign(orgA, Instant.now().minusSeconds(60L * 86_400L));
        beacon(eventA, FunnelEvent.STAGE_CHECKOUT_START, "anonOld", old.getId().toString());

        // ---- org B data (MUST NOT leak into A's counts) ----
        newMembership(orgB, "subscribed", "explicit", "+353870000000", "subscribed");
        newMembership(orgB, "subscribed", "explicit", null, "never");
        UUID eventB = newOnSaleEvent(orgB, ownerId);
        newSuggestion(orgB, eventB, "launch_push", "suggested");
        Campaign cB = newCampaign(orgB, Instant.now().minusSeconds(3_600L));
        beacon(eventB, FunnelEvent.STAGE_CHECKOUT_START, "anonX", cB.getId().toString());
    }

    @AfterEach
    void tearDown() { wipe(); }

    @Test
    void returnsRealOrgScopedMetrics() throws Exception {
        mvc.perform(get("/api/v1/marketing/hub-metrics").with(authentication(authA)))
                .andExpect(status().isOk())
                // shape + values — org A only
                .andExpect(jsonPath("$.totalContacts").value(4))       // 4 memberships in org A
                .andExpect(jsonPath("$.sendableEmail").value(3))       // 3 subscribed+explicit; 1 no-basis excluded
                .andExpect(jsonPath("$.smsPhones").value(1))           // one sms subscriber with a phone
                .andExpect(jsonPath("$.momentumWaiting").value(2))     // 2 'suggested'; 'dismissed' excluded
                .andExpect(jsonPath("$.momentumWatching").value(1))    // 1 on-sale event for org A
                .andExpect(jsonPath("$.attributedPurchases").value(2)) // anonA+anonB from the 30d campaign only
                .andExpect(jsonPath("$.attributedRevMinor").value(0))  // no per-campaign revenue source → 0
                // sender identity: reflects imin.marketing.* config verbatim. The test profile
                // leaves the from-address blank, so sendingEnabled is honestly false here — the
                // MarketingHubServiceTest proves it flips true once a from-address is configured.
                .andExpect(jsonPath("$.emailFrom").value(""))
                .andExpect(jsonPath("$.emailFromName").value(""))
                .andExpect(jsonPath("$.sendingEnabled").value(false));
    }

    @Test
    void requiresAuth() throws Exception {
        mvc.perform(get("/api/v1/marketing/hub-metrics")).andExpect(status().isUnauthorized());
    }

    // ---- fixtures ----

    private UUID newOrg(String slugPrefix) {
        Organization o = new Organization();
        o.setName(slugPrefix);
        o.setSlug(slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("hello-" + UUID.randomUUID() + "@test.example");
        o.setCountry("IE");
        return orgs.save(o).getId();
    }

    private void newMembership(UUID orgId, String consentStatus, String consentBasis,
                               String phone, String smsStatus) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("hub-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus(consentStatus);
        m.setConsentBasis(consentBasis);
        if (phone != null) m.setPhoneE164(phone);
        m.setSmsConsentStatus(smsStatus);
        if ("subscribed".equals(smsStatus)) m.setSmsConsentBasis("explicit");
        memberships.save(m);
    }

    private UUID newOnSaleEvent(UUID orgId, UUID createdBy) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("On sale night");
        e.setSlug("on-sale-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plusSeconds(14L * 86_400L)); // future
        e.setOnSaleAt(Instant.now().minusSeconds(86_400L));      // on sale
        e.setCreatedBy(createdBy);
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private void newSuggestion(UUID orgId, UUID eventId, String trigger, String status) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setEventId(eventId);
        s.setTriggerType(trigger);
        s.setStatus(status);
        s.setMetricsSnapshot("{}");
        s.setDraftPayload("{\"subject\":\"Hi\"}");
        s.setSuggestedAt(Instant.now());
        suggestions.save(s);
    }

    private Campaign newCampaign(UUID orgId, Instant createdAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Camp " + UUID.randomUUID().toString().substring(0, 6));
        c.setStatus("draft");
        c.setOrigin("manual");
        c.setCreatedBy(UUID.randomUUID());
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(createdAt);
        return campaigns.save(c);
    }

    private void beacon(UUID eventId, String stage, String anon, String utmCampaign) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(eventId);
        fe.setStage(stage);
        fe.setAnonId(anon);
        fe.setUtmCampaign(utmCampaign);
        funnel.save(fe);
    }

    /**
     * Reset the tables this test touches, child rows first. memberships/consumers/campaigns
     * extend the bare {@code Repository<>} marker (no {@code deleteAll()}), so they are cleared
     * via JdbcTemplate — the same delete-by-SQL approach the sibling dispatcher tests use.
     */
    private void wipe() {
        jdbc.update("delete from event_funnel_events");
        jdbc.update("delete from momentum_suggestions");
        jdbc.update("delete from campaigns");
        jdbc.update("delete from memberships");
        jdbc.update("delete from consumers");
        jdbc.update("delete from events");
        jdbc.update("delete from users");
        jdbc.update("delete from organizations");
    }
}
