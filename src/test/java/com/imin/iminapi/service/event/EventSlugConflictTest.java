package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code uq_events_org_slug} translation, against the real constraint (events-9/17).
 *
 * <p>{@code Event} uses {@code GenerationType.UUID}, an in-VM pre-insert generator, so
 * {@code events.save(e)} assigns the id without emitting SQL and Hibernate defers the
 * INSERT/UPDATE. The violation therefore used to surface at commit — outside the try
 * blocks in {@code createDraft} and {@code patch} — and the
 * {@code ApiException.duplicate("slug", …)} translation was unreachable in production:
 * the request still 409'd, but through {@code GlobalExceptionHandler.handleDataIntegrity}
 * with a generic message and no {@code fields} map, so the wizard could not attach the
 * error to the slug input. The unit tests certified the catch only by stubbing
 * {@code save} to throw, which is exactly the test double that hid the gap. This drives the
 * {@link EventService} bean, so the transaction commits as it does in production.
 */
@IminIntegrationTest
class EventSlugConflictTest {

    @Autowired EventService sut;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Slug Test Org");
        org.setSlug("slug-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("slug-org@example.com");
        org.setCountry("DE");
        org = organizations.save(org);

        User owner = new User();
        owner.setEmail("slug-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, List.of(principal.orgId()));
    }

    private static EventPatchRequest withSlug(String slug) {
        return new EventPatchRequest(null, slug, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void createDraft_withATakenSlug_is409WithTheSlugField() {
        sut.createDraft(principal, withSlug("sold-out-night"));

        assertThatThrownBy(() -> sut.createDraft(principal, withSlug("sold-out-night")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo(ErrorCode.DUPLICATE);
                    assertThat(api.fields()).containsKey("slug");
                });
    }

    @Test
    void patch_toATakenSlug_is409WithTheSlugField() {
        sut.createDraft(principal, withSlug("sold-out-night"));
        UUID other = sut.createDraft(principal, withSlug("second-night")).id();

        assertThatThrownBy(() -> sut.patch(principal, other, null, withSlug("sold-out-night")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo(ErrorCode.DUPLICATE);
                    assertThat(api.fields()).containsKey("slug");
                });
    }
}
