package com.imin.iminapi.service.event;

import com.imin.iminapi.controller.event.EventController;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.support.IminIntegrationTest;
import com.stripe.StripeClient;
import com.stripe.model.v2.core.Account;
import com.stripe.net.ApiResource;
import com.stripe.param.v2.core.AccountRetrieveParams;
import com.stripe.service.V2Services;
import com.stripe.service.v2.CoreService;
import com.stripe.service.v2.core.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Publishing a paid event refreshes Stripe without holding the event row lock, on Postgres. */
@IminIntegrationTest
class EventPublishStripeLockPostgresTest {

    private static final String PUBLISH_THREAD = "publish-under-test";

    @Autowired StripeClient stripeClient;
    @Autowired EventController eventController;
    @Autowired EventService eventService;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private UUID orgId;
    private String accountId;
    private AuthPrincipal principal;
    private UUID eventId;
    private final CountDownLatch syncEntered = new CountDownLatch(1);
    private final CountDownLatch gate = new CountDownLatch(1);
    private final AccountService accountService = mock(AccountService.class);

    @BeforeEach
    void setUp() throws Exception {
        accountId = "acct_pub_" + UUID.randomUUID().toString().substring(0, 8);
        Organization o = new Organization();
        o.setName("Publish lock");
        o.setSlug("pub-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("pub@example.test");
        o.setCountry("DE");
        o.setStripeAccountId(accountId);
        o.setStripeConnectState(StripeConnectState.ONBOARDING);
        o.setStripePayoutsEnabled(false);
        o.setStripeConnectStatusUpdatedAt(null);
        o.setStripeLivemode(null);
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("pub-" + UUID.randomUUID() + "@example.test");
        u.setFirstName("Ada");
        u.setOrgId(orgId);
        u.setRole(UserRole.MEMBER);
        principal = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.MEMBER, UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(principal.userId());
        e.setName("Night");
        e.setSlug("pub-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCurrency("EUR");
        e.setStartsAt(now.plus(10, ChronoUnit.DAYS));
        e.setEndsAt(now.plus(10, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS));
        e.setVenueStreet("12 Main");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10115");
        e.setDescription("A night out");
        eventId = events.save(e).getId();
        jdbc.update("INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, reserved, "
                + "enabled, sort_order) VALUES (?, ?, 'GA', 1500, 100, 0, 0, true, 0)", UUID.randomUUID(), eventId);

        // The real mirror calls Stripe here; the StripeClient fake is reset after every test.
        V2Services v2 = mock(V2Services.class);
        CoreService core = mock(CoreService.class);
        when(stripeClient.v2()).thenReturn(v2);
        when(v2.core()).thenReturn(core);
        when(core.accounts()).thenReturn(accountService);
        // Blocks only the publish under test; a sweeper tick on another thread returns at once.
        doAnswer(inv -> {
            if (!PUBLISH_THREAD.equals(Thread.currentThread().getName())) return account("active");
            syncEntered.countDown();
            if (!gate.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("gate timed out");
            return account("active");
        }).when(accountService).retrieve(eq(accountId), any(AccountRetrieveParams.class));
    }

    /** A v2 Account whose recipient transfers capability has {@code status}; "active" projects ACTIVE. */
    private Account account(String status) {
        return ApiResource.GSON.fromJson("""
                {
                  "id": "%s",
                  "configuration": { "recipient": { "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "%s" } }
                  } } },
                  "requirements": { "summary": {}, "entries": [] }
                }
                """.formatted(accountId, status), Account.class);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM audit_logs WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM ticket_tiers WHERE event_id IN (SELECT id FROM events WHERE org_id = ?)", orgId);
        jdbc.update("DELETE FROM events WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM users WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @Timeout(60)
    void publish_stripeBlocked_eventLockFree() throws Exception {
        ExecutorService publishPool = Executors.newSingleThreadExecutor(r -> new Thread(r, PUBLISH_THREAD));
        ExecutorService otherPool = Executors.newSingleThreadExecutor();
        try {
            Future<EventDto> publish = publishPool.submit(() -> eventController.publish(principal, eventId));
            assertThat(syncEntered.await(15, TimeUnit.SECONDS)).as("publish reached the Stripe refresh").isTrue();

            new TransactionTemplate(txManager).executeWithoutResult(s ->
                    jdbc.queryForObject("SELECT id FROM events WHERE id = ? FOR UPDATE NOWAIT", UUID.class, eventId));

            Future<EventDto> autosave = otherPool.submit(() -> eventService.patch(principal, eventId, null,
                    new EventPatchRequest("Renamed", null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null)));
            autosave.get(5, TimeUnit.SECONDS);
            assertThat(publish.isDone()).as("publish still waits on Stripe").isFalse();

            gate.countDown();
            assertThat(publish.get(10, TimeUnit.SECONDS).status()).isEqualTo("live");

            Map<String, Object> row = jdbc.queryForMap("SELECT status, name FROM events WHERE id = ?", eventId);
            assertThat(row.get("status")).isEqualTo("LIVE");
            assertThat(row.get("name")).isEqualTo("Renamed");
        } finally {
            gate.countDown();
            publishPool.shutdownNow();
            otherPool.shutdownNow();
            publishPool.awaitTermination(15, TimeUnit.SECONDS);
            otherPool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(60)
    void publish_orgStaysOnboarding_refusedWithoutAStripeCallUnderTheLock() throws Exception {
        // A fresh timestamp keeps the Connect sweeper off this org; getStatus still refreshes a non-ACTIVE org.
        jdbc.update("UPDATE organizations SET stripe_connect_status_updated_at = now() WHERE id = ?", orgId);
        AtomicInteger publishThreadCalls = new AtomicInteger();
        // Stripe still says ONBOARDING: the first call changes nothing, a second one would block.
        doAnswer(inv -> {
            if (!PUBLISH_THREAD.equals(Thread.currentThread().getName())) return account("restricted");
            if (publishThreadCalls.incrementAndGet() > 1 && !gate.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate timed out");
            }
            return account("restricted");
        }).when(accountService).retrieve(eq(accountId), any(AccountRetrieveParams.class));

        ExecutorService publishPool = Executors.newSingleThreadExecutor(r -> new Thread(r, PUBLISH_THREAD));
        try {
            Future<EventDto> publish = publishPool.submit(() -> eventController.publish(principal, eventId));

            assertThatThrownBy(() -> publish.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(api.code()).isEqualTo(ErrorCode.STRIPE_NOT_READY);
                    });
            verify(accountService, times(1)).retrieve(eq(accountId), any(AccountRetrieveParams.class));
            assertThat(publishThreadCalls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId))
                    .isEqualTo("DRAFT");
        } finally {
            gate.countDown();
            publishPool.shutdownNow();
            publishPool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }
}
