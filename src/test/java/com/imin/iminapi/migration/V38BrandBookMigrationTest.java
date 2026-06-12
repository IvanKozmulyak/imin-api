package com.imin.iminapi.migration;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class V38BrandBookMigrationTest {

    @Autowired OrganizationRepository orgs;
    @PersistenceContext EntityManager em;

    @Test
    void v38_columns_apply_with_correct_defaults() {
        // An org saved with no brand fields touched must read back the SQL defaults:
        // empty accent list and logoOnPosters = true (the column default).
        Organization o = new Organization();
        o.setName("Defaults Org");
        o.setSlug("defaults-org-" + System.nanoTime());
        o.setContactEmail("a@b.com");
        o.setCountry("DE");
        Organization saved = orgs.saveAndFlush(o);
        orgs.flush();

        Organization reloaded = orgs.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBrandName()).isNull();
        assertThat(reloaded.getBrandLogoUrl()).isNull();
        assertThat(reloaded.getBrandAccentColors()).isEqualTo(List.of());
        assertThat(reloaded.isBrandLogoOnPosters()).isTrue();
    }

    @Test
    void populated_accent_list_round_trips_through_StringListJsonConverter_in_order() {
        // The OrgBrandService unit test mocks the repository, so it never exercises the real
        // StringListJsonConverter ↔ TEXT-column round-trip. This is the only test that does it for a
        // NON-EMPTY ordered palette — exactly as V33's stripe_requirements_* lists are stored as
        // JSON-in-TEXT and read back via the same converter (mind the H2/PG JSON-as-TEXT note).
        Organization o = new Organization();
        o.setName("Populated Org");
        o.setSlug("populated-org-" + System.nanoTime());
        o.setContactEmail("a@b.com");
        o.setCountry("DE");
        o.setBrandName("Tortuga Collective");
        o.setBrandLogoUrl("https://cdn.example/orgs/x/brand/logo-ab12cd34.png");
        o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899", "#f6c04a")));
        o.setBrandLogoOnPosters(false);
        Organization saved = orgs.saveAndFlush(o);

        // Evict so the read comes from the DB column through the converter, not the persistence cache.
        orgs.flush();
        em.clear();

        Organization reloaded = orgs.findById(saved.getId()).orElseThrow();
        // Order is priority (index 0 = primary) — must survive the JSON-array TEXT round-trip exactly.
        assertThat(reloaded.getBrandAccentColors()).containsExactly("#ec4899", "#f6c04a");
        assertThat(reloaded.getBrandName()).isEqualTo("Tortuga Collective");
        assertThat(reloaded.getBrandLogoUrl()).isEqualTo("https://cdn.example/orgs/x/brand/logo-ab12cd34.png");
        assertThat(reloaded.isBrandLogoOnPosters()).isFalse();

        // And the []-default still holds for a sibling row that never touched the column.
        Organization bare = new Organization();
        bare.setName("Bare Org");
        bare.setSlug("bare-org-" + System.nanoTime());
        bare.setContactEmail("c@d.com");
        bare.setCountry("DE");
        UUID bareId = orgs.saveAndFlush(bare).getId();
        em.clear();
        assertThat(orgs.findById(bareId).orElseThrow().getBrandAccentColors()).isEqualTo(List.of());
    }
}
