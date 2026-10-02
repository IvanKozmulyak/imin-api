package com.imin.iminapi.stripe;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.stripe.StripeClient;
import com.stripe.model.Product;
import com.stripe.param.ProductCreateParams;
import com.stripe.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sweep through the Spring bean: a lost sync is healed by the queue, under ShedLock. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class TierStripeSyncSweeperSpringTest {

    private static final String LOCK = "tier_stripe_sync_sweep";
    /** The sweep lock is rewound here before each tick; a real acquisition must move it past. */
    private static final Instant LOCK_REWOUND_TO = Instant.parse("2020-01-01T00:00:00Z");

    @MockitoBean StripeClient stripeClient;

    @Autowired TierStripeSyncSweeper sweeper;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private UUID tierId;

    @BeforeEach
    void setUp() throws Exception {
        releaseSweepLock();
        ProductService products = mock(ProductService.class);
        when(stripeClient.products()).thenReturn(products);
        Product created = new Product();
        created.setId("prod_sweep");
        created.setDefaultPrice("price_sweep");
        when(products.create(any(ProductCreateParams.class))).thenReturn(created);

        Organization o = new Organization();
        o.setName("Sweep heal");
        o.setSlug("tss-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("tss@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("tss-" + UUID.randomUUID() + "@example.test");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Sweep night");
        e.setSlug("tss-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCurrency("EUR");
        e.setPublishedAt(Instant.now());
        UUID eventId = events.save(e).getId();

        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(1500);
        t.setQuantity(100);
        tierId = tiers.save(t).getId();
        jdbc.update("UPDATE ticket_tiers SET stripe_sync_attempts = 2 WHERE id = ?", tierId);
        jdbc.update("UPDATE events SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), eventId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ticket_tiers WHERE event_id IN (SELECT id FROM events WHERE org_id = ?)", orgId);
        jdbc.update("DELETE FROM events WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM users WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    void sweep_healsUnsyncedTierOfSettledLiveEvent_resetsBackoff() throws Exception {
        // The H2 database is shared by every context, so earlier tests may leave candidates ahead of
        // this one; each pass backs off the 25 it claims, so a few passes always reach it.
        for (int pass = 0; pass < 40 && attempts() == 2; pass++) {
            releaseSweepLock();
            sweeper.sweep();
        }

        Map<String, Object> row = Map.of();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            row = jdbc.queryForMap("SELECT stripe_product_id, stripe_price_id, stripe_sync_attempts, "
                    + "stripe_sync_next_at FROM ticket_tiers WHERE id = ?", tierId);
            if ("prod_sweep".equals(row.get("stripe_product_id"))) break;
            Thread.sleep(50);
        }
        assertThat(row.get("stripe_product_id")).isEqualTo("prod_sweep");
        assertThat(row.get("stripe_price_id")).isEqualTo("price_sweep");
        assertThat(((Number) row.get("stripe_sync_attempts")).intValue()).isZero();
        assertThat(row.get("stripe_sync_next_at")).isNull();
    }

    @Test
    void sweep_runsUnderShedLock() {
        sweeper.sweep();

        Long stamped = jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = ? AND locked_at > ?",
                Long.class, LOCK, Timestamp.from(LOCK_REWOUND_TO));
        assertThat(stamped).isEqualTo(1L);
    }

    private int attempts() {
        return jdbc.queryForObject("SELECT stripe_sync_attempts FROM ticket_tiers WHERE id = ?",
                Integer.class, tierId);
    }

    /** lockAtLeastFor would hold the sweep lock for the rest of this shared context; expire it instead. */
    private void releaseSweepLock() {
        Timestamp rewound = Timestamp.from(LOCK_REWOUND_TO);
        jdbc.update("UPDATE shedlock SET lock_until = ?, locked_at = ? WHERE name = ?", rewound, rewound, LOCK);
    }
}
