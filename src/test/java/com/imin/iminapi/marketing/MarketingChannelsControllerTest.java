package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.marketing.email.DnsRecordStatus;
import com.imin.iminapi.marketing.email.ResendDomainsClient;
import com.imin.iminapi.marketing.email.SendingDomainDns;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import com.imin.iminapi.marketing.service.ComplaintRateBreaker;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for GET /api/v1/marketing/channels. Real data across TWO orgs,
 * driven through MockMvc as org A. Asserts that the guardrail numbers are REAL (they match
 * the enforcing config/constants and the org's actual send + complaint rows), that org
 * scoping holds (org B's sends/complaints/opt-ins never leak into A's numbers), and that a
 * complaint-paused org reports paused.
 */
@IminIntegrationTest
class MarketingChannelsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired ProviderEventRepository providerEvents;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.imin.iminapi.marketing.email.MarketingEmailProperties emailProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;

    /** The shared fake: absent DNS by default (the honest degrade); one test stubs a present snapshot. */
    @Autowired ResendDomainsClient domainsClient;

    private UUID orgA;
    private UUID orgB;
    private Authentication authA;
    private final List<UUID> createdConsumers = new ArrayList<>();

    @BeforeEach
    void seed() {
        // Default: Resend domains API returns nothing usable → email.dns absent (honest degrade).
        when(domainsClient.sendingDomainDns()).thenReturn(Optional.empty());

        orgA = newOrg("chan-a");
        orgB = newOrg("chan-b");

        User owner = new User();
        owner.setEmail(fx.email("owner"));
        owner.setOrgId(orgA);
        owner.setRole(UserRole.OWNER);
        UUID ownerId = users.save(owner).getId();

        AuthPrincipal pA = new AuthPrincipal(ownerId, orgA, UserRole.OWNER, UUID.randomUUID());
        authA = new UsernamePasswordAuthenticationToken(
                pA, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        // ---- org A ----
        // 3 sends inside the rolling 24h window; 1 more at 10 days old (in 30d, not in 24h);
        // 1 at 40 days old (outside both); 1 still 'pending' (never sent → counts nowhere).
        Campaign cA = newCampaign(orgA);
        newRecipient(cA.getId(), "sent", hoursAgo(1));
        newRecipient(cA.getId(), "delivered", hoursAgo(2));
        newRecipient(cA.getId(), "clicked", hoursAgo(23));
        newRecipient(cA.getId(), "delivered", daysAgo(10));
        newRecipient(cA.getId(), "delivered", daysAgo(40));
        newRecipient(cA.getId(), "pending", hoursAgo(1));

        // 4 delivered + 1 complaint → org-wide complaint rate 0.25.
        for (int i = 0; i < 4; i++) providerEvent(cA.getId(), ProviderEvent.TYPE_DELIVERED);
        providerEvent(cA.getId(), ProviderEvent.TYPE_COMPLAINED);

        // 2 SMS opt-ins with a phone (+1 opted-in without a phone → not counted).
        newMembership(orgA, phone(), "subscribed");
        newMembership(orgA, phone(), "subscribed");
        newMembership(orgA, null, "subscribed");
        newMembership(orgA, phone(), "never");   // has phone, no consent → not counted

        // ---- org B (MUST NOT leak into A's numbers) ----
        Campaign cB = newCampaign(orgB);
        newRecipient(cB.getId(), "sent", hoursAgo(1));
        newRecipient(cB.getId(), "sent", hoursAgo(2));
        providerEvent(cB.getId(), ProviderEvent.TYPE_DELIVERED);
        providerEvent(cB.getId(), ProviderEvent.TYPE_COMPLAINED);
        newMembership(orgB, phone(), "subscribed");
    }

    @AfterEach
    void tearDown() {
        wipeOrg(orgA);
        wipeOrg(orgB);
        for (UUID consumerId : createdConsumers) {
            jdbc.update("delete from consumers where consumer_id = ?", consumerId);
        }
        createdConsumers.clear();
    }

    @Test
    void surfacesRealOrgScopedGuardrailNumbers() throws Exception {
        mvc.perform(get("/api/v1/marketing/channels").with(authentication(authA)))
                .andExpect(status().isOk())
                // ---- email identity: reflects imin.marketing.* config verbatim ----
                .andExpect(jsonPath("$.email.provider").value("resend"))
                .andExpect(jsonPath("$.email.oneClickUnsubscribe").value(true))
                // ---- guardrails: config-sourced, matching the values the send path enforces ----
                // Spec §8/§10: 10,000 sends per org per rolling 24h, 2-day frequency floor.
                // These pin the configured DEFAULTS — if they fail, either the default moved
                // or an env override leaked into the test profile. Both are worth knowing.
                .andExpect(jsonPath("$.email.guardrails.dailyCap").value(10000))
                .andExpect(jsonPath("$.email.guardrails.frequencyFloorHours").value(48))
                .andExpect(jsonPath("$.email.guardrails.autoPauseThreshold")
                        .value(ComplaintRateBreaker.THRESHOLD))
                .andExpect(jsonPath("$.email.guardrails.autoPauseMinVolume")
                        .value((int) ComplaintRateBreaker.MIN_VOLUME))
                // ---- live counts: org A only ----
                // 3 sends inside 24h ('sent','delivered','clicked'); the 10d/40d rows and the
                // pending row are excluded, and org B's 2 sends must not leak in.
                .andExpect(jsonPath("$.email.guardrails.sentLast24h").value(3))
                // 4 within 30d (the three above + the 10d row); the 40d row excluded.
                .andExpect(jsonPath("$.email.sentLast30d").value(4))
                // 1 complaint ÷ 4 delivered = 0.25 — org B's 1/1 must not leak in.
                .andExpect(jsonPath("$.email.guardrails.complaintCount").value(1))
                .andExpect(jsonPath("$.email.guardrails.deliveredCount").value(4))
                .andExpect(jsonPath("$.email.guardrails.complaintRate").value(0.25))
                // not paused, so pausedAt is null
                .andExpect(jsonPath("$.email.guardrails.paused").value(false))
                .andExpect(jsonPath("$.email.guardrails.pausedAt").doesNotExist())
                // ---- quiet hours: the window QuietHours actually enforces ----
                .andExpect(jsonPath("$.email.quietHours.startLocal").value("22:00"))
                .andExpect(jsonPath("$.email.quietHours.endLocal").value("09:00"))
                .andExpect(jsonPath("$.email.quietHours.timezone").value("UTC"))
                .andExpect(jsonPath("$.email.quietHours.enforced").value(true))
                // ---- sms: opt-ins real; sending honestly not built ----
                .andExpect(jsonPath("$.sms.sendingEnabled").value(false))
                .andExpect(jsonPath("$.sms.optedInPhones").value(2))
                // ---- sms: INTENDED first-release policy (config-sourced), NOT live guardrails.
                //      sendingEnabled=false + sendWindow.enforced=false above/below flag them as such.
                .andExpect(jsonPath("$.sms.senderId").value("IMIN"))
                .andExpect(jsonPath("$.sms.provider").value("Bird"))
                .andExpect(jsonPath("$.sms.region").value("+380"))
                .andExpect(jsonPath("$.sms.firstReleaseRecipientCap").value(200))
                .andExpect(jsonPath("$.sms.unlockThresholdPhones").value(500))
                .andExpect(jsonPath("$.sms.sendWindow.startLocal").value("09:00"))
                .andExpect(jsonPath("$.sms.sendWindow.endLocal").value("20:00"))
                .andExpect(jsonPath("$.sms.sendWindow.enforced").value(false))
                // ---- DNS absent when Resend can't be read truthfully (mock returns empty) ----
                .andExpect(jsonPath("$.email.dns").doesNotExist())
                // ---- fields we refuse to invent must not appear ----
                .andExpect(jsonPath("$.email.reputation").doesNotExist())
                .andExpect(jsonPath("$.sms.reputation").doesNotExist())
                .andExpect(jsonPath("$.sms.encoding").doesNotExist());
    }

    @Test
    void fromHeaderIsTheOrgsOwnFromViaImin() throws Exception {
        flips.set(emailProps, "fromAddress", "contact@imin.support");
        flips.set(emailProps, "fromName", "Alex");
        String orgName = orgs.findById(orgA).orElseThrow().getName();
        mvc.perform(get("/api/v1/marketing/channels").with(authentication(authA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.fromAddress").value("contact@imin.support"))
                .andExpect(jsonPath("$.email.fromName").value("Alex"))
                .andExpect(jsonPath("$.email.fromHeader")
                        .value("\"" + orgName + " via IMIN\" <contact@imin.support>"));
    }

    @Test
    void pausedOrgReportsPaused() throws Exception {
        Instant pausedAt = Instant.now().minusSeconds(120);
        Organization o = orgs.findById(orgA).orElseThrow();
        o.setMarketingPausedAt(pausedAt);
        orgs.save(o);

        mvc.perform(get("/api/v1/marketing/channels").with(authentication(authA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.guardrails.paused").value(true))
                .andExpect(jsonPath("$.email.guardrails.pausedAt").exists());
    }

    /**
     * When the Resend domains API answers, the SPF/DKIM/DMARC snapshot is surfaced verbatim under
     * {@code email.dns}. DMARC null (Resend provisioned none) stays absent — not defaulted.
     */
    @Test
    void surfacesResendDnsSnapshotWhenAvailable() throws Exception {
        when(domainsClient.sendingDomainDns()).thenReturn(Optional.of(new SendingDomainDns(
                "imin.support",
                DnsRecordStatus.VERIFIED,
                DnsRecordStatus.VERIFIED,
                null,                          // Resend reported no DMARC record
                Instant.now())));

        mvc.perform(get("/api/v1/marketing/channels").with(authentication(authA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.dns.domain").value("imin.support"))
                .andExpect(jsonPath("$.email.dns.spf").value("VERIFIED"))
                .andExpect(jsonPath("$.email.dns.dkim").value("VERIFIED"))
                .andExpect(jsonPath("$.email.dns.dmarc").doesNotExist())
                .andExpect(jsonPath("$.email.dns.checkedAt").exists());
    }

    /**
     * Org scoping from the other side: an org with NO sends of its own reports zeroes even
     * though org A's rows exist in the same tables.
     */
    @Test
    void otherOrgSeesOnlyItsOwnNumbers() throws Exception {
        User ownerB = new User();
        ownerB.setEmail(fx.email("owner"));
        ownerB.setOrgId(orgB);
        ownerB.setRole(UserRole.OWNER);
        UUID ownerBId = users.save(ownerB).getId();
        Authentication authB = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(ownerBId, orgB, UserRole.OWNER, UUID.randomUUID()),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        mvc.perform(get("/api/v1/marketing/channels").with(authentication(authB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.guardrails.sentLast24h").value(2))   // B's own 2 sends
                .andExpect(jsonPath("$.email.guardrails.complaintCount").value(1))
                .andExpect(jsonPath("$.email.guardrails.deliveredCount").value(1))
                .andExpect(jsonPath("$.email.guardrails.complaintRate").value(1.0))
                .andExpect(jsonPath("$.sms.optedInPhones").value(1));
    }

    @Test
    void requiresAuth() throws Exception {
        mvc.perform(get("/api/v1/marketing/channels")).andExpect(status().isUnauthorized());
    }

    // ---- fixtures ----

    private static Instant hoursAgo(long h) { return Instant.now().minusSeconds(h * 3_600L); }
    private static Instant daysAgo(long d) { return Instant.now().minusSeconds(d * 86_400L); }

    private UUID newOrg(String name) {
        Organization o = fx.org();
        o.setName(name);
        o.setCountry("IE");
        return orgs.save(o).getId();
    }

    private static String phone() {
        return "+35387" + (1_000_000 + (int) (Math.random() * 8_999_999));
    }

    private Campaign newCampaign(UUID orgId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Camp " + UUID.randomUUID().toString().substring(0, 6));
        c.setStatus("sent");
        c.setOrigin("manual");
        c.setCreatedBy(UUID.randomUUID());
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    /** A recipient row; lastEventAt is what the rolling-window send counts key off. */
    private void newRecipient(UUID campaignId, String status, Instant lastEventAt) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setEmail(fx.email("rcpt"));
        r.setStatus(status);
        r.setLastEventAt(lastEventAt);
        recipients.save(r);
    }

    private void providerEvent(UUID campaignId, String type) {
        ProviderEvent e = new ProviderEvent();
        e.setId(UUID.randomUUID());
        e.setProvider(ProviderEvent.PROVIDER_RESEND);
        e.setProviderEventId("svix_" + UUID.randomUUID());
        e.setCampaignId(campaignId);
        e.setType(type);
        e.setCreatedAt(Instant.now());
        providerEvents.save(e);
    }

    private void newMembership(UUID orgId, String phone, String smsStatus) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(fx.email("chan"));
        c = consumers.save(c);
        createdConsumers.add(c.getConsumerId());
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        if (phone != null) m.setPhoneE164(phone);
        m.setSmsConsentStatus(smsStatus);
        if ("subscribed".equals(smsStatus)) m.setSmsConsentBasis("explicit");
        memberships.save(m);
    }

    /** Removes only this test's own rows, child-first, scoped to one org id. */
    private void wipeOrg(UUID orgId) {
        CampaignRows.delete(jdbc, List.of(orgId));
        jdbc.update("delete from memberships where org_id = ?", orgId);
        jdbc.update("delete from users where org_id = ?", orgId);
        jdbc.update("delete from organizations where id = ?", orgId);
    }
}
