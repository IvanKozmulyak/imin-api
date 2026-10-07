package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.dashboard.DashboardPeriod;
import com.imin.iminapi.service.dashboard.DashboardService;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the service through its Spring bean so @Transactional and @CacheEvict are live. */
@IminIntegrationTest
class DraftEventDeletionServiceTest {

    @Autowired DraftEventDeletionService sut;
    @Autowired EventService eventService;
    @Autowired DashboardService dashboard;
    @Autowired CacheManager caches;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository auditLogs;
    @Autowired AuditRows audit;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private UUID orgA;
    private UUID userA;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        clearDashboardCache();
        orgA = org("a");
        userA = user(orgA, "Ada");
        principal = new AuthPrincipal(userA, orgA, UserRole.MEMBER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        clearDashboardCache();
        for (UUID id : orgIds) {
            jdbc.update("DELETE FROM audit_logs WHERE org_id = ?", id);
            jdbc.update("DELETE FROM orders WHERE org_id = ?", id);
            jdbc.update("DELETE FROM events WHERE org_id = ?", id);
            jdbc.update("DELETE FROM users WHERE org_id = ?", id);
            jdbc.update("DELETE FROM organizations WHERE id = ?", id);
        }
    }

    @Test
    void unknown_id_is_404() {
        assertApiError(() -> sut.deleteDraft(principal, UUID.randomUUID()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    }

    @Test
    void soft_deleted_id_is_404() {
        UUID id = event(orgA, userA, EventStatus.DRAFT, null, Instant.now().minusSeconds(60));

        assertApiError(() -> sut.deleteDraft(principal, id), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    }

    @Test
    void other_orgs_draft_is_404_and_stays() {
        UUID orgB = org("b");
        UUID foreign = event(orgB, user(orgB, "Bob"), EventStatus.DRAFT, null, null);

        assertApiError(() -> sut.deleteDraft(principal, foreign), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);

        assertThat(deletedAt(foreign)).isNull();
    }

    @Test
    void live_event_is_409() {
        UUID live = event(orgA, userA, EventStatus.LIVE, null, null);

        assertApiError(() -> sut.deleteDraft(principal, live), HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "Only a draft can be deleted");
        assertThat(deletedAt(live)).isNull();
    }

    @Test
    void draft_that_was_published_before_is_409() {
        UUID unpublished = event(orgA, userA, EventStatus.DRAFT, Instant.now().minusSeconds(3600), null);

        assertApiError(() -> sut.deleteDraft(principal, unpublished), HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "A draft that was published before cannot be deleted");
        assertThat(deletedAt(unpublished)).isNull();
    }

    @Test
    void draft_with_an_order_is_409() {
        UUID draft = event(orgA, userA, EventStatus.DRAFT, null, null);
        order(draft);

        assertApiError(() -> sut.deleteDraft(principal, draft), HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "A draft with orders cannot be deleted");
        assertThat(deletedAt(draft)).isNull();
    }

    @Test
    void lost_race_at_the_guarded_update_is_409_with_no_delete_and_no_audit() {
        UUID draft = event(orgA, userA, EventStatus.DRAFT, null, null);
        // Stands in for a publish that committed between the checks and the guarded UPDATE.
        try (var fault = PgFaults.skipUpdates(jdbc, "events", "id", draft)) {
            assertApiError(() -> sut.deleteDraft(principal, draft), HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only a draft that was never published can be deleted");
        }

        assertThat(deletedAt(draft)).isNull();
        assertThat(audit.forOrg(orgA)).noneMatch(a -> AuditActions.EVENT_DELETED.equals(a.getAction()));
    }

    @Test
    void never_published_draft_is_soft_deleted_audited_and_hidden() {
        UUID draft = event(orgA, userA, EventStatus.DRAFT, null, null);

        sut.deleteDraft(principal, draft);

        assertThat(deletedAt(draft)).isNotNull();
        AuditLog row = audit.assertRecorded(orgA, AuditActions.EVENT_DELETED, "event", draft);
        assertThat(audit.forOrg(orgA)).hasSize(1);
        assertThat(row.getSummary()).isEqualTo("Deleted draft \"Night\"");
        assertApiError(() -> eventService.detail(principal, draft), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        PageResponse<EventDto> drafts = eventService.list(principal, EventStatus.DRAFT, 1, 20);
        assertThat(drafts.items()).extracting(EventDto::id).doesNotContain(draft);
    }

    @Test
    void untitled_draft_is_audited_as_untitled() {
        UUID draft = event(orgA, userA, EventStatus.DRAFT, null, null);
        jdbc.update("UPDATE events SET name = '' WHERE id = ?", draft);

        sut.deleteDraft(principal, draft);

        assertThat(auditLogs.findByOrgIdOrderByOccurredAtDesc(orgA, PageRequest.of(0, 1)).getContent())
                .singleElement()
                .extracting(AuditLog::getSummary)
                .isEqualTo("Deleted draft \"Untitled\"");
    }

    @Test
    void delete_evicts_the_cached_dashboard() {
        UUID draft = event(orgA, userA, EventStatus.DRAFT, null, null);
        DashboardResponse before = dashboard.build(principal, DashboardPeriod.D30, DashboardPeriod.D30);
        assertThat(before.activity()).isEmpty();

        sut.deleteDraft(principal, draft);

        DashboardResponse after = dashboard.build(principal, DashboardPeriod.D30, DashboardPeriod.D30);
        assertThat(after.activity())
                .as("@CacheEvict on deleteDraft must evict the cached dashboard")
                .extracting(DashboardResponse.Activity::label)
                .contains("Deleted draft \"Night\"");
    }

    private void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                HttpStatus status, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.code()).isEqualTo(code);
        });
    }

    /** The message pins which guard refused; the lost-race branch would otherwise mask a missing one. */
    private void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                HttpStatus status, ErrorCode code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.code()).isEqualTo(code);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }

    private void clearDashboardCache() {
        var cache = caches.getCache("dashboard");
        if (cache != null) cache.clear();
    }

    private Instant deletedAt(UUID id) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT deleted_at FROM events WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    private UUID org(String tag) {
        Organization o = new Organization();
        o.setName("Draft delete " + tag);
        o.setSlug("dds-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("dds@example.test");
        o.setCountry("DE");
        UUID id = orgs.save(o).getId();
        orgIds.add(id);
        return id;
    }

    private UUID user(UUID orgId, String firstName) {
        User u = new User();
        u.setEmail("dds-" + UUID.randomUUID() + "@example.test");
        u.setFirstName(firstName);
        u.setOrgId(orgId);
        u.setRole(UserRole.MEMBER);
        return users.save(u).getId();
    }

    private UUID event(UUID orgId, UUID userId, EventStatus status, Instant publishedAt, Instant deletedAt) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Night");
        e.setSlug("dds-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setCurrency("EUR");
        e.setPublishedAt(publishedAt);
        e.setDeletedAt(deletedAt);
        return events.save(e).getId();
    }

    private void order(UUID eventId) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(orgA);
        o.setEmail("buyer@example.com");
        o.setTotalMinor(0);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        orders.save(o);
    }
}
