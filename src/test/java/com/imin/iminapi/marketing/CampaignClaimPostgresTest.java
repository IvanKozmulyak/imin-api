package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The claim query's audience-plan holds (sends switch, legal identity) on real Postgres (H2 accepts more than PG does). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignClaimPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private Campaign plan;
    private Campaign manual;
    private UUID orgId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from campaign_recipients");
        jdbc.update("delete from campaigns");
        orgId = org("PG Claim SAS", "legal@pgc.test");
        plan = campaign(orgId, "audience_plan");
        manual = campaign(orgId, "manual");
    }

    private UUID org(String legalName, String legalContact) {
        Organization o = new Organization();
        o.setName("PG Claim Org");
        o.setSlug("pgc-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("pgc@test.com");
        o.setCountry("FR");
        o.setTimezone("UTC");
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        return orgs.save(o).getId();
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
        c.setScheduledAt(NOW.minus(1, ChronoUnit.MINUTES));
        c.setCreatedAt(NOW);
        c.setUpdatedAt(NOW);
        return campaigns.save(c);
    }

    private List<UUID> claim(boolean sendsEnabled) {
        return tx.execute(st -> campaigns.claimDue(NOW, NOW.minus(5, ChronoUnit.MINUTES), sendsEnabled)
                .stream().map(Campaign::getId).toList());
    }

    @Test
    void runsOnPostgres() {
        assertThat(jdbc.queryForObject("select version()", String.class)).containsIgnoringCase("PostgreSQL");
    }

    @Test
    void sendsOff_claimsOnlyManual() {
        assertThat(claim(false)).containsExactly(manual.getId());
    }

    @Test
    void sendsOn_claimsBoth() {
        assertThat(claim(true)).containsExactlyInAnyOrder(plan.getId(), manual.getId());
    }

    @Test
    void sendsOn_audiencePlanOfOrgWithoutLegalName_isNotClaimed() {
        Campaign held = campaign(org(null, "legal@x.test"), "audience_plan");

        assertThat(claim(true)).doesNotContain(held.getId()).contains(plan.getId(), manual.getId());
    }

    @Test
    void sendsOn_audiencePlanOfOrgWithBlankContact_isNotClaimed_butItsManualIs() {
        UUID noContact = org("X SAS", "  ");
        Campaign held = campaign(noContact, "audience_plan");
        Campaign otherManual = campaign(noContact, "manual");

        assertThat(claim(true)).doesNotContain(held.getId()).contains(otherManual.getId());
    }
}
