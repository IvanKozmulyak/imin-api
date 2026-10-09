package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The claim query's audience-plan holds (sends switch, legal identity) on real Postgres (H2 accepts more than PG does). */
@IminIntegrationTest
class CampaignClaimPostgresTest {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    // The claim is global (LIMIT 100 here): rows this old sort ahead of other tests' dated campaigns. The defence
    // against leftovers is the CampaignRows cleanup rule, not this order: NULLS FIRST puts null-scheduled ones first.
    private static final Instant ANCIENT = NOW.minus(3650, ChronoUnit.DAYS);

    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired IminFixtures fx;

    private final List<UUID> orgIds = new ArrayList<>();
    private Campaign plan;
    private Campaign manual;

    @BeforeEach
    void seed() {
        UUID orgId = org("PG Claim SAS", fx.email("legal"));
        plan = campaign(orgId, "audience_plan");
        manual = campaign(orgId, "manual");
    }

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private UUID org(String legalName, String legalContact) {
        Organization o = fx.org();
        o.setTimezone("UTC");
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        UUID id = orgs.save(o).getId();
        orgIds.add(id);
        return id;
    }

    private Campaign campaign(UUID orgId, String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("c");
        c.setStatus("scheduled");
        c.setOrigin(origin);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setScheduledAt(ANCIENT);
        c.setCreatedAt(NOW);
        c.setUpdatedAt(NOW);
        return campaigns.save(c);
    }

    private List<UUID> claim(boolean sendsEnabled) {
        return claim(sendsEnabled, false);
    }

    private List<UUID> claim(boolean sendsEnabled, boolean legalIdentityAllCampaigns) {
        return tx.execute(st -> campaigns.claimDue(NOW, NOW.minus(5, ChronoUnit.MINUTES), sendsEnabled,
                        legalIdentityAllCampaigns, 100)
                .stream().map(Campaign::getId).toList());
    }

    @Test
    void sendsOff_claimsOnlyManual() {
        assertThat(claim(false)).contains(manual.getId()).doesNotContain(plan.getId());
    }

    @Test
    void sendsOn_claimsBoth() {
        assertThat(claim(true)).contains(plan.getId(), manual.getId());
    }

    @Test
    void sendsOn_audiencePlanOfOrgWithoutLegalName_isNotClaimed() {
        Campaign held = campaign(org(null, fx.email("legal")), "audience_plan");

        assertThat(claim(true)).doesNotContain(held.getId()).contains(plan.getId(), manual.getId());
    }

    @Test
    void sendsOn_audiencePlanOfOrgWithBlankContact_isNotClaimed_butItsManualIs() {
        UUID noContact = org("X SAS", "  ");
        Campaign held = campaign(noContact, "audience_plan");
        Campaign otherManual = campaign(noContact, "manual");

        assertThat(claim(true)).doesNotContain(held.getId()).contains(otherManual.getId());
    }

    @Test
    void legalIdentityAllCampaigns_manualAndMomentumOfOrgWithoutIdentity_areNotClaimed() {
        UUID noIdentity = org(null, null);
        Campaign heldManual = campaign(noIdentity, "manual");
        Campaign heldMomentum = campaign(noIdentity, "momentum");

        assertThat(claim(false, true)).contains(manual.getId())
                .doesNotContain(plan.getId(), heldManual.getId(), heldMomentum.getId());
        assertThat(claim(false, false)).contains(heldManual.getId(), heldMomentum.getId());
    }

    @Test
    void legalIdentityAllCampaigns_sendsOn_claimsBothOfOrgWithIdentity() {
        assertThat(claim(true, true)).contains(plan.getId(), manual.getId());
    }
}
