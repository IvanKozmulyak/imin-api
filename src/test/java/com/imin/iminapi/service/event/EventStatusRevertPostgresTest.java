package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.dto.event.PromoCodeCreateRequest;
import com.imin.iminapi.dto.event.PromoCodePatchRequest;
import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.MediaStorage;
import com.imin.iminapi.stripe.StripeProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/** Event load-then-save paths racing the LIVE→PAST sweep, on Postgres 17 (READ COMMITTED lock-wait re-check). */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class EventStatusRevertPostgresTest {

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

    @MockitoBean StripeProductService stripeProductService;
    @MockitoSpyBean MediaStorage storage;

    @Autowired EventService eventService;
    @Autowired TicketTierService tierService;
    @Autowired PromoCodeService promoCodeService;
    @Autowired MediaUploadService mediaUploadService;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired PromoCodeRepository promos;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    enum Writer { TIER_CREATE, TIER_PATCH, TIER_DELETE, PROMO_CREATE, PROMO_PATCH, PROMO_DELETE, MEDIA_UPLOAD, MEDIA_DELETE }

    private UUID orgId;
    private AuthPrincipal principal;
    private UUID eventId;
    private UUID tierId;
    private UUID promoId;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("Status revert");
        o.setSlug("esr-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("esr@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("esr-" + UUID.randomUUID() + "@example.test");
        u.setFirstName("Ada");
        u.setOrgId(orgId);
        u.setRole(UserRole.MEMBER);
        principal = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.MEMBER, UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(principal.userId());
        e.setName("Night");
        e.setSlug("esr-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCurrency("EUR");
        e.setStartsAt(now.minus(5, ChronoUnit.HOURS));
        e.setEndsAt(now.minus(1, ChronoUnit.HOURS));
        e.setPublishedAt(now.minus(2, ChronoUnit.DAYS));
        e = events.save(e);
        eventId = e.getId();
        e.setPosterUrl("https://test-media.invalid/events/" + eventId + "/poster-x.png");
        events.save(e);

        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("Free");
        t.setPriceMinor(0);
        t.setQuantity(10);
        tierId = tiers.save(t).getId();

        PromoCode pc = new PromoCode();
        pc.setEventId(eventId);
        pc.setCode("EARLY");
        pc.setDiscountPct(10);
        pc.setMaxUses(100);
        promoId = promos.save(pc).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM audit_logs WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM promo_codes WHERE event_id IN (SELECT id FROM events WHERE org_id = ?)", orgId);
        jdbc.update("DELETE FROM ticket_tiers WHERE event_id IN (SELECT id FROM events WHERE org_id = ?)", orgId);
        jdbc.update("DELETE FROM events WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM users WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void savePathWaitingOnAnUncommittedSweep_leavesTheEventPast(Writer writer) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch swept = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> sweep = pool.submit(() -> tx.executeWithoutResult(s -> {
                assertThat(events.markLivePast(now(), now())).isEqualTo(1);
                swept.countDown();
                await(release);
            }));
            assertThat(swept.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> write = pool.submit(() -> run(writer));
            Thread.sleep(500);
            assertThat(write.isDone()).as("the writer waits for the sweep's row lock").isFalse();

            release.countDown();
            sweep.get(10, TimeUnit.SECONDS);
            Object result = write.get(10, TimeUnit.SECONDS);

            assertThat(status()).isEqualTo("PAST");
            assertEffect(writer, result);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void sweepWaitingOnASavePath_stillMarksThePast(Writer writer) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> write = pool.submit(() -> tx.execute(s -> {
                Object r = run(writer);
                written.countDown();
                await(release);
                return r;
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> sweep = pool.submit(() -> events.markLivePast(now(), now()));
            Thread.sleep(500);
            assertThat(sweep.isDone()).as("the sweep waits for the writer's row lock").isFalse();

            release.countDown();
            Object result = write.get(10, TimeUnit.SECONDS);
            assertThat(sweep.get(10, TimeUnit.SECONDS)).isEqualTo(1);

            assertThat(status()).isEqualTo("PAST");
            assertEffect(writer, result);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void unpublishWaitingOnAnUncommittedSweep_is409AndStaysPast() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch swept = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> sweep = pool.submit(() -> tx.executeWithoutResult(s -> {
                assertThat(events.markLivePast(now(), now())).isEqualTo(1);
                swept.countDown();
                await(release);
            }));
            assertThat(swept.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> unpublish = pool.submit(() -> eventService.unpublish(principal, eventId));
            Thread.sleep(500);
            assertThat(unpublish.isDone()).as("unpublish waits for the sweep's row lock").isFalse();

            release.countDown();
            sweep.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> unpublish.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .extracting(Throwable::getCause)
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_STATE);
                    });
            assertThat(status()).isEqualTo("PAST");
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void sweepWaitingOnAnUnpublish_leavesTheDraft() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch unpublished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> unpublish = pool.submit(() -> tx.executeWithoutResult(s -> {
                eventService.unpublish(principal, eventId);
                unpublished.countDown();
                await(release);
            }));
            assertThat(unpublished.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> sweep = pool.submit(() -> events.markLivePast(now(), now()));
            Thread.sleep(500);
            assertThat(sweep.isDone()).as("the sweep waits for the unpublish's row lock").isFalse();

            release.countDown();
            unpublish.get(10, TimeUnit.SECONDS);
            assertThat(sweep.get(10, TimeUnit.SECONDS)).isEqualTo(0);
            assertThat(status()).isEqualTo("DRAFT");
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void sweepCommitsWhileAnUploadIsStoringTheObject() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch putEntered = new CountDownLatch(1);
        CountDownLatch releasePut = new CountDownLatch(1);
        doAnswer(inv -> {
            putEntered.countDown();
            await(releasePut);
            return inv.callRealMethod();
        }).when(storage).put(anyString(), any(), any());
        try {
            Future<MediaUploadResponse> upload = pool.submit(() -> mediaUploadService.upload(
                    principal, eventId, MediaKind.POSTER, realPng(40, 50), "image/png", "p.png"));
            assertThat(putEntered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(pool.submit(() -> events.markLivePast(now(), now())).get(5, TimeUnit.SECONDS))
                    .as("the sweep does not wait for the storage put").isEqualTo(1);

            releasePut.countDown();
            MediaUploadResponse result = upload.get(10, TimeUnit.SECONDS);
            assertThat(posterUrl()).isEqualTo(result.url());
            assertThat(status()).isEqualTo("PAST");
        } finally {
            releasePut.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void sweepCommitsWhileADeleteIsRemovingTheObject() throws Exception {
        storage.put("events/" + eventId + "/poster-x.png", new byte[1], "image/png");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch deleteEntered = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        doAnswer(inv -> {
            deleteEntered.countDown();
            await(releaseDelete);
            return inv.callRealMethod();
        }).when(storage).delete(anyString());
        try {
            Future<?> delete = pool.submit(() -> mediaUploadService.delete(principal, eventId, MediaKind.POSTER));
            assertThat(deleteEntered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(pool.submit(() -> events.markLivePast(now(), now())).get(5, TimeUnit.SECONDS))
                    .as("the sweep does not wait for the storage delete").isEqualTo(1);

            releaseDelete.countDown();
            delete.get(10, TimeUnit.SECONDS);
            assertThat(posterUrl()).isNull();
            assertThat(status()).isEqualTo("PAST");
            verify(storage).delete("events/" + eventId + "/poster-x.png");
        } finally {
            releaseDelete.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    private Object run(Writer w) {
        return switch (w) {
            case TIER_CREATE -> tierService.create(principal, eventId,
                    new TicketTierCreateRequest("Late", 0, 5, null, null, null, null));
            case TIER_PATCH -> tierService.patch(principal, eventId, tierId,
                    new TicketTierPatchRequest("Renamed", null, null, null, null, null, null, null, null));
            case TIER_DELETE -> {
                tierService.delete(principal, eventId, tierId);
                yield null;
            }
            case PROMO_CREATE -> promoCodeService.create(principal, eventId,
                    new PromoCodeCreateRequest("LATE", 10, 100, null));
            case PROMO_PATCH -> promoCodeService.patch(principal, eventId, promoId,
                    new PromoCodePatchRequest(null, 20, null, null));
            case PROMO_DELETE -> {
                promoCodeService.delete(principal, eventId, promoId);
                yield null;
            }
            case MEDIA_UPLOAD -> mediaUploadService.upload(principal, eventId, MediaKind.POSTER,
                    realPng(40, 50), "image/png", "p.png");
            case MEDIA_DELETE -> {
                mediaUploadService.delete(principal, eventId, MediaKind.POSTER);
                yield null;
            }
        };
    }

    private void assertEffect(Writer w, Object result) {
        switch (w) {
            case TIER_CREATE -> assertThat(count("ticket_tiers")).isEqualTo(2);
            case TIER_PATCH -> assertThat(jdbc.queryForObject(
                    "SELECT name FROM ticket_tiers WHERE id = ?", String.class, tierId)).isEqualTo("Renamed");
            case TIER_DELETE -> assertThat(count("ticket_tiers")).isZero();
            case PROMO_CREATE -> assertThat(count("promo_codes")).isEqualTo(2);
            case PROMO_PATCH -> assertThat(jdbc.queryForObject(
                    "SELECT discount_pct FROM promo_codes WHERE id = ?", Integer.class, promoId)).isEqualTo(20);
            case PROMO_DELETE -> assertThat(count("promo_codes")).isZero();
            case MEDIA_UPLOAD -> assertThat(posterUrl()).isEqualTo(((MediaUploadResponse) result).url());
            case MEDIA_DELETE -> assertThat(posterUrl()).isNull();
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE event_id = ?", Integer.class, eventId);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId);
    }

    private String posterUrl() {
        return jdbc.queryForObject("SELECT poster_url FROM events WHERE id = ?", String.class, eventId);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] realPng(int w, int h) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
