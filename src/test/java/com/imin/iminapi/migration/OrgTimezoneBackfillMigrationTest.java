package com.imin.iminapi.migration;

import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.util.CountryTimeZones;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-runs the exact {@code V192__org_timezone_backfill.sql} UPDATE over seeded orgs on the shared Postgres,
 * inside a rolled-back transaction. Zones are read back via JDBC.
 */
@IminIntegrationTest
@Transactional
class OrgTimezoneBackfillMigrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired jakarta.persistence.EntityManager em;

    private String sql() throws Exception {
        try (var in = new ClassPathResource("db/migration/V192__org_timezone_backfill.sql").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    private UUID org(String country, String timezone) {
        UUID id = fx.org().getId();
        em.flush();
        jdbc.update("UPDATE organizations SET country = ?, timezone = ? WHERE id = ?", country, timezone, id);
        return id;
    }

    private String tzOf(UUID id) {
        return jdbc.queryForObject("SELECT timezone FROM organizations WHERE id = ?", String.class, id);
    }

    @Test
    void moves_utc_orgs_to_their_country_zone_and_leaves_the_rest() throws Exception {
        Map<String, UUID> mapped = new LinkedHashMap<>();
        CountryTimeZones.all().keySet().forEach(c -> mapped.put(c, org(c, "UTC")));
        UUID blank = org("FR", "");
        UUID unmapped = org("ZZ", "UTC");
        UUID chosen = org("FR", "Europe/Lisbon");
        UUID noCountry = org(null, "UTC");

        jdbc.execute(sql());

        mapped.forEach((country, id) ->
                assertThat(tzOf(id)).as(country).isEqualTo(CountryTimeZones.zoneFor(country).orElseThrow()));
        assertThat(tzOf(blank)).isEqualTo("Europe/Paris");
        assertThat(tzOf(unmapped)).isEqualTo("UTC");
        assertThat(tzOf(chosen)).isEqualTo("Europe/Lisbon");
        assertThat(tzOf(noCountry)).isEqualTo("UTC");
    }
}
