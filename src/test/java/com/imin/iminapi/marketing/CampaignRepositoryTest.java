package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignRepositoryTest {

    @Autowired CampaignRepository repo;
    @Autowired DataSource dataSource;

    @BeforeEach
    void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from campaigns");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private Campaign draft(UUID org, String name) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org);
        c.setChannel("email");
        c.setName(name);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }

    @Test
    void save_defaults_status_to_draft_and_is_org_scoped() {
        Campaign saved = repo.save(draft(ORG, "Launch night"));
        assertThat(saved.getStatus()).isEqualTo("draft");
        assertThat(saved.getOrigin()).isEqualTo("manual");
        assertThat(saved.getAttempts()).isZero();

        Campaign found = repo.findByIdAndOrgId(saved.getId(), ORG).orElseThrow();
        assertThat(found.getName()).isEqualTo("Launch night");

        // cross-org isolation: same id, wrong org -> empty
        assertThat(repo.findByIdAndOrgId(saved.getId(), OTHER_ORG)).isEmpty();
    }

    @Test
    void list_returns_only_this_orgs_campaigns_newest_first() {
        repo.save(draft(ORG, "A"));
        repo.save(draft(OTHER_ORG, "B-other"));
        Campaign c = draft(ORG, "C");
        c.setCreatedAt(Instant.now().plusSeconds(5));
        repo.save(c);

        List<Campaign> mine = repo.listByOrg(ORG, null, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(mine).extracting(Campaign::getName).containsExactly("C", "A");
    }
}
