package com.imin.iminapi.predictor.research;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static ResearchCache.Key key(UUID org, String city) {
        return new ResearchCache.Key(org, city, "FR", "house & techno", null, LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 10, 24), DAY);
    }

    @Test
    void entryOlderThan24HoursIsGone() {
        WebResearchServiceTest.MutableClock clock = new WebResearchServiceTest.MutableClock();
        ResearchCache cache = new ResearchCache(clock);
        UUID org = UUID.randomUUID();
        cache.put(key(org, "Paris"), new ResearchCache.Entry(List.of(), T0));

        clock.now = T0.plus(Duration.ofHours(24));
        assertThat(cache.get(key(org, "Paris"))).isPresent();
        clock.now = T0.plus(Duration.ofHours(24)).plusSeconds(1);
        assertThat(cache.get(key(org, "Paris"))).isEmpty();
    }

    @Test
    void keyFoldsCaseAndAccentsAndSeparatesOrgs() {
        ResearchCache cache = new ResearchCache(new WebResearchServiceTest.MutableClock());
        UUID org = UUID.randomUUID();
        cache.put(key(org, "Orléans"), new ResearchCache.Entry(List.of(), T0));

        assertThat(cache.get(key(org, " orleans "))).isPresent();
        assertThat(cache.get(key(UUID.randomUUID(), "Orléans"))).isEmpty();
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedPastTheLimit() {
        ResearchCache cache = new ResearchCache(new WebResearchServiceTest.MutableClock());
        UUID org = UUID.randomUUID();
        for (int i = 0; i < ResearchCache.MAX_ENTRIES; i++) {
            cache.put(key(org, "city" + i), new ResearchCache.Entry(List.of(), T0));
        }
        cache.get(key(org, "city0"));   // touched, so city1 is now the eldest

        cache.put(key(org, "one more"), new ResearchCache.Entry(List.of(), T0));

        assertThat(cache.size()).isEqualTo(ResearchCache.MAX_ENTRIES);
        assertThat(cache.get(key(org, "city0"))).isPresent();
        assertThat(cache.get(key(org, "city1"))).isEmpty();
    }
}
