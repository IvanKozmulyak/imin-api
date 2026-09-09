package com.imin.iminapi.service.org;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * data-2: removing a member who has created an event must work.
 *
 * <p>The unit suite mocks {@link UserRepository}, so the FK that made this
 * permanently 400 was never exercised there — which is why it shipped. This one
 * uses real persistence and a real flush, so a regression to {@code users.delete}
 * fails here with the 23503 the organizer used to see.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class TeamRemovalSoftDeleteTest {

    @Autowired TeamService teamService;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired AuthSessionRepository sessions;

    private UUID orgId;
    private UUID ownerId;
    private UUID memberId;
    private UUID eventId;
    private AuthPrincipal owner;

    @BeforeEach
    void seed() {
        Organization o = new Organization();
        o.setName("Soft Delete Org");
        o.setSlug("soft-delete-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("owner@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();

        User ownerRow = new User();
        ownerRow.setOrgId(orgId);
        ownerRow.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        ownerRow.setRole(UserRole.OWNER);
        ownerId = users.save(ownerRow).getId();

        User memberRow = new User();
        memberRow.setOrgId(orgId);
        memberRow.setEmail("member-" + UUID.randomUUID() + "@example.test");
        memberRow.setRole(UserRole.MEMBER);
        memberId = users.save(memberRow).getId();

        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(memberId); // the FK that used to make removal impossible
        e.setName("Their event");
        e.setSlug("their-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCurrency("EUR");
        eventId = events.save(e).getId();

        owner = new AuthPrincipal(ownerId, orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void clear() {
        sessions.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void removing_the_creator_of_an_event_succeeds_and_keeps_the_event() {
        AuthSession s = new AuthSession();
        s.setUserId(memberId);
        s.setTokenHash("hash-" + UUID.randomUUID());
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        UUID sessionId = sessions.save(s).getId();

        teamService.remove(owner, memberId);

        // Gone from the roster…
        assertThat(teamService.list(owner))
                .extracting(dto -> dto.id())
                .doesNotContain(memberId)
                .contains(ownerId);
        // …but the row survives, so the event's provenance still resolves…
        User after = users.findById(memberId).orElseThrow();
        assertThat(after.getDisabledAt()).isNotNull();
        assertThat(events.findById(eventId)).isPresent();
        assertThat(events.findById(eventId).orElseThrow().getCreatedBy()).isEqualTo(memberId);
        // …and every session they held is revoked, which is the actual revocation.
        assertThat(sessions.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void removing_a_member_who_created_nothing_deletes_the_row() {
        User spare = new User();
        spare.setOrgId(orgId);
        spare.setEmail("spare-" + UUID.randomUUID() + "@example.test");
        spare.setRole(UserRole.MEMBER);
        UUID spareId = users.save(spare).getId();

        teamService.remove(owner, spareId);

        assertThat(users.findById(spareId)).isEmpty();
    }
}
