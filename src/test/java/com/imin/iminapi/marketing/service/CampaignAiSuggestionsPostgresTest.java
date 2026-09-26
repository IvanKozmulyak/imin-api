package com.imin.iminapi.marketing.service;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A duplicate fingerprint must not abort the rest of the batch on real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignAiSuggestionsPostgresTest {

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

    @Autowired CampaignAiSuggestions suggestions;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aDuplicateInTheBatch_isSkipped_andTheRestOfTheBatchIsStored() {
        UUID id = campaign();
        // Another request already stored this text.
        jdbc.update("INSERT INTO campaign_ai_suggestions (campaign_id, part, text_sha256) VALUES (?, ?, ?)",
                id, CampaignAiSuggestions.SUBJECT, CampaignAiSuggestions.fingerprint("Taken"));

        suggestions.record(id, CampaignAiSuggestions.SUBJECT, List.of("First", "Taken", "Last"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_ai_suggestions WHERE campaign_id = ?",
                Integer.class, id)).isEqualTo(3);
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.SUBJECT, "First")).isTrue();
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.SUBJECT, "Taken")).isTrue();
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.SUBJECT, "Last")).isTrue();
    }

    private UUID campaign() {
        Organization o = new Organization();
        o.setName("PG AI Org");
        o.setSlug("pga-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("pga@test.com");
        o.setCountry("FR");
        o.setTimezone("UTC");
        UUID orgId = orgs.save(o).getId();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("c");
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }
}
