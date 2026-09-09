package com.imin.iminapi.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * One Caffeine cache, {@code dashboard}, behind {@code DashboardService.build}.
 *
 * <p>Eviction contract: the @Cacheable key is {@code orgId|cyclePeriod|businessPeriod},
 * so there are up to 16 live entries per org and no evict site can name them all. The
 * organizer write paths (EventService, TicketTierService, PromoCodeService) used to
 * evict {@code key = "#p.orgId().toString()"} — a key nothing ever wrote, so publishing
 * an event or changing a tier left the dashboard on pre-change numbers for the full
 * 30s TTL while the annotation implied immediacy. They now evict {@code allEntries},
 * which is cheap: one instance, 10_000 entries max, 30s TTL, and organizer writes are
 * rare compared with dashboard reads.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("dashboard");
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .recordStats());
        return mgr;
    }
}
