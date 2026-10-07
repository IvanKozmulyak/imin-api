package com.imin.iminapi.service.audit;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.EventService;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EventService} mutations leave the persisted audit row an auditor reads: action, target event,
 * actor and summary, written by the real {@link AuditLogger}; a mutation that did not happen leaves none.
 */
@IminIntegrationTest
class EventServiceAuditIntegrationTest {

    @Autowired EventService events;
    @Autowired EventRepository eventRepo;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private Organization org;
    private User owner;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
        principal = fx.principal(owner);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, List.of(org.getId()));
    }

    enum Mutation { CREATE_DRAFT, PUBLISH, UNPUBLISH, PATCH_NAME }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void each_mutation_persists_its_audit_row_on_the_event(Mutation mutation) {
        UUID eventId;
        String action;
        String name;
        switch (mutation) {
            case CREATE_DRAFT -> {
                name = "My Show";
                eventId = events.createDraft(principal, named(name)).id();
                action = AuditActions.EVENT_CREATED;
            }
            case PUBLISH -> {
                Event e = publishable(EventStatus.DRAFT);
                name = e.getName();
                events.publish(principal, e.getId());
                eventId = e.getId();
                action = AuditActions.EVENT_PUBLISHED;
            }
            case UNPUBLISH -> {
                Event e = publishable(EventStatus.LIVE);
                name = e.getName();
                events.unpublish(principal, e.getId());
                eventId = e.getId();
                action = AuditActions.EVENT_UNPUBLISHED;
            }
            default -> {
                Event e = publishable(EventStatus.DRAFT);
                name = "Renamed";
                events.patch(principal, e.getId(), null, named(name));
                eventId = e.getId();
                action = AuditActions.EVENT_UPDATED;
            }
        }

        AuditLog row = audit.assertRecorded(org.getId(), action, "event", eventId);
        assertThat(row.getActorId()).isEqualTo(owner.getId());
        assertThat(row.getSummary()).contains(name);
    }

    @Test
    void createDraft_withNoName_summaryFallsBackToUntitled() {
        UUID eventId = events.createDraft(principal, named(null)).id();

        assertThat(audit.assertRecorded(org.getId(), AuditActions.EVENT_CREATED, "event", eventId).getSummary())
                .contains("Untitled");
    }

    enum NoOp { PUBLISH_FAILING_VALIDATION, PATCH_WITH_NO_FIELDS }

    @ParameterizedTest
    @EnumSource(NoOp.class)
    void a_mutation_that_did_not_happen_leaves_no_audit_row(NoOp noOp) {
        Event e = publishable(EventStatus.DRAFT);
        if (noOp == NoOp.PUBLISH_FAILING_VALIDATION) {
            e.setName("");
            eventRepo.save(e);
            assertThatThrownBy(() -> events.publish(principal, e.getId())).isInstanceOf(ApiException.class);
        } else {
            events.patch(principal, e.getId(), null, named(null));
        }

        assertThat(audit.forOrg(org.getId())).noneMatch(r -> e.getId().equals(r.getTargetId()));
    }

    /** A free event with every field publish validation requires; no tiers, so no Stripe readiness. */
    private Event publishable(EventStatus status) {
        Event e = fx.event(org, owner, status, clock.instant().plus(Duration.ofDays(14)));
        e.setVenueStreet("12 Main");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10115");
        e.setDescription("d");
        if (status == EventStatus.LIVE) e.setPublishedAt(clock.instant().minus(Duration.ofHours(1)));
        return eventRepo.save(e);
    }

    private static EventPatchRequest named(String name) {
        return new EventPatchRequest(name, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
