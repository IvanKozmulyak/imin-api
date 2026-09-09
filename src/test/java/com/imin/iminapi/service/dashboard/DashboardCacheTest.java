package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.TicketTierService;
import com.imin.iminapi.stripe.StripeProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard read is cached for 30s (CacheConfig). Two things have to hold for
 * that to be safe, and neither did:
 *
 * <ul>
 *   <li>an organizer write must invalidate it (infra-4) — the @CacheEvict sites keyed
 *       on the bare org id while @Cacheable wrote {@code orgId|cycle|business}, so the
 *       evict removed a key nobody ever wrote;</li>
 *   <li>the cached payload must not be user-specific (infra-5) — the greeting name is,
 *       and the key is org-scoped, so the second colleague to load the dashboard was
 *       greeted with the first one's name or the local part of their email.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class DashboardCacheTest {

    @Autowired DashboardService dashboard;
    @Autowired TicketTierService tierService;
    @Autowired CacheManager caches;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired AuditLogRepository auditLogs;

    /** The tier write is otherwise a live Stripe product call. */
    @MockitoBean StripeProductService stripeProductService;

    private Organization org;
    private User owner;
    private Event event;
    private TicketTier ga;
    private AuthPrincipal ownerPrincipal;

    @BeforeEach
    void setUp() {
        wipe();

        org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("ada-" + UUID.randomUUID() + "@example.com");
        owner.setFirstName("Ada");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Night");
        event.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        ga = new TicketTier();
        ga.setEventId(event.getId());
        ga.setName("GA");
        ga.setPriceMinor(1500);
        ga.setQuantity(100);
        ga.setSortOrder(0);
        ga.setEnabled(true);
        ga = tiers.save(ga);

        ownerPrincipal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        var cache = caches.getCache("dashboard");
        if (cache != null) cache.clear();
        auditLogs.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    /** infra-4: the write must be visible on the next read, not 30 seconds later. */
    @Test
    void a_tier_change_is_visible_on_the_next_dashboard_read() {
        DashboardResponse before = dashboard.build(ownerPrincipal, DashboardPeriod.D30, DashboardPeriod.D30);
        assertThat(before.now().ticketsTotal()).isEqualTo(100);

        tierService.patch(ownerPrincipal, event.getId(), ga.getId(),
                new TicketTierPatchRequest(null, null, 250, null, null, null, null, null, null));

        DashboardResponse after = dashboard.build(ownerPrincipal, DashboardPeriod.D30, DashboardPeriod.D30);
        assertThat(after.now().ticketsTotal())
                .as("@CacheEvict on the tier write must actually evict the cached dashboard")
                .isEqualTo(250);
    }

    /**
     * infra-5: TeamService.invite creates members with an empty first name, so
     * displayFirstName falls back to the local part of the email — which made the
     * colleague-name leak a colleague-email-address leak for exactly the accounts
     * most likely to hit it.
     */
    @Test
    void a_colleague_does_not_inherit_the_first_users_greeting() {
        User invited = new User();
        invited.setEmail("grace." + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        invited.setFirstName("");
        invited.setOrgId(org.getId());
        invited.setRole(UserRole.MEMBER);
        invited = users.save(invited);
        AuthPrincipal member =
                new AuthPrincipal(invited.getId(), org.getId(), UserRole.MEMBER, UUID.randomUUID());

        DashboardResponse first = dashboard.build(ownerPrincipal, DashboardPeriod.D30, DashboardPeriod.D30);
        assertThat(first.greeting().name()).isEqualTo("Ada");

        DashboardResponse second = dashboard.build(member, DashboardPeriod.D30, DashboardPeriod.D30);
        String expected = invited.getEmail().substring(0, invited.getEmail().indexOf('@'));
        assertThat(second.greeting().name())
                .as("the second user in an org must not be greeted with a colleague's name")
                .isEqualTo(expected);
    }
}
