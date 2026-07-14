package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class MomentumRepositoryTest {

    @Autowired
    MomentumSuggestionRepository repo;

    @Autowired
    DataSource dataSource;

    // Momentum @SpringBootTest classes share one H2 context; wipe momentum_suggestions in
    // both @BeforeEach and @AfterEach (audience convention, AudienceSendGateConsentSuppressionTest
    // .java:71-81,540-555) so leaked 'suggested' rows never inflate cross-org list counts or get
    // visited by MomentumEvaluator.expireStale's GLOBAL findByStatus("suggested") in a later test.
    @BeforeEach
    @AfterEach
    void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from momentum_suggestions");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }

    private MomentumSuggestion make(UUID orgId, UUID eventId, String trigger, String status) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setEventId(eventId);
        s.setTriggerType(trigger);
        s.setStatus(status);
        s.setMetricsSnapshot("{}");
        s.setDraftPayload("{}");
        s.setSuggestedAt(Instant.now());
        return s;
    }

    @Test
    void findsLiveSuggestionByEventAndTrigger() {
        UUID org = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        repo.save(make(org, event, "launch_push", "suggested"));
        repo.save(make(org, event, "slump", "dismissed"));

        Optional<MomentumSuggestion> live =
                repo.findByEventIdAndTriggerTypeAndStatus(event, "launch_push", "suggested");
        assertThat(live).isPresent();

        Optional<MomentumSuggestion> none =
                repo.findByEventIdAndTriggerTypeAndStatus(event, "slump", "suggested");
        assertThat(none).isEmpty();
    }

    @Test
    void findsMostRecentForCooldown() {
        UUID org = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        MomentumSuggestion old = make(org, event, "slump", "dismissed");
        old.setSuggestedAt(Instant.now().minusSeconds(86_400L * 3));
        repo.save(old);

        Optional<MomentumSuggestion> recent =
                repo.findTopByEventIdAndTriggerTypeOrderBySuggestedAtDesc(event, "slump");
        assertThat(recent).isPresent();
        assertThat(recent.get().getStatus()).isEqualTo("dismissed");
    }

    @Test
    void listsByOrgAndStatus() {
        UUID org = UUID.randomUUID();
        repo.save(make(org, UUID.randomUUID(), "launch_push", "suggested"));
        repo.save(make(org, UUID.randomUUID(), "slump", "suggested"));
        repo.save(make(org, UUID.randomUUID(), "sold_out", "approved"));

        assertThat(repo.findByOrgIdAndStatusOrderBySuggestedAtDesc(org, "suggested")).hasSize(2);
        assertThat(repo.findByOrgIdAndStatusOrderBySuggestedAtDesc(org, "approved")).hasSize(1);
    }
}
