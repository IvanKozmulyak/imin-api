package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PredictionRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
 * {@code save} to throw, which is exactly the test double that hid the gap.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventSlugConflictTest {

    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired PromoCodeRepository promos;
    @Autowired PredictionRepository predictions;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;

    EventService sut;
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        sut = new EventService(events, tiers, promos, predictions, new EventValidator(),
                new IfMatchSupport(), mock(TicketTierService.class), mock(StripeConnectService.class));

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
}
