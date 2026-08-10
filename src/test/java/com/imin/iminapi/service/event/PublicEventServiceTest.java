package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PublicEventService.class, PublicListingConfig.class, PublicEventServiceTest.FixedClockConfig.class})
class PublicEventServiceTest {

    /** Fixed "now" for all flag-computation tests: 2026-06-01T12:00:00Z */
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        /** PublicEventService now reads the buyer fee rates for the listing's priceFrom. */
        @Bean
        com.imin.iminapi.stripe.StripeProperties stripeProperties() {
            return new com.imin.iminapi.stripe.StripeProperties();
        }
    }

    @Autowired PublicEventService publicEventService;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired TicketTierRepository ticketTierRepository;
    @Autowired UserRepository userRepository;

    // Shared org + user used in all tests (created per-test via @BeforeEach + @Transactional rollback)
    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        org = new Organization();
        // Do NOT set the ID — let @GeneratedValue(strategy = UUID) generate it;
        // setting it manually causes JPA to call merge() on a detached entity.
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = organizationRepository.save(org);

        // events.created_by FK requires a real users row
        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = userRepository.save(owner);
    }

    // -----------------------------------------------------------------------
    // Helper: build a minimal publishable event (PUBLIC, LIVE, published_at set)
    // -----------------------------------------------------------------------
    private Event publishedLiveEvent() {
        Event e = new Event();
        // Do NOT manually set ID — let @GeneratedValue generate it
        e.setOrgId(org.getId());
        e.setName("Great Event");
        e.setSlug("great-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCreatedBy(owner.getId()); // FK to users(id)
        e.setCurrency("EUR");
        return e;
    }

    private TicketTier tier(UUID eventId, String name, int priceMinor, int quantity, int sold,
                            boolean enabled, int sortOrder, Instant saleClosesAt) {
        TicketTier tier = new TicketTier();
        tier.setEventId(eventId);
        tier.setName(name);
        tier.setPriceMinor(priceMinor);
        tier.setQuantity(quantity);
        tier.setSold(sold);
        tier.setEnabled(enabled);
        tier.setSortOrder(sortOrder);
        tier.setSaleClosesAt(saleClosesAt);
        return ticketTierRepository.save(tier);
    }

    // -----------------------------------------------------------------------
    // 1. Happy path: published + public + live
    // -----------------------------------------------------------------------
    @Test
    void returnsEvent_whenPublishedPublicLive() {
        Event e = eventRepository.save(publishedLiveEvent());

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.id()).isEqualTo(e.getId());
        assertThat(r.name()).isEqualTo("Great Event");
        assertThat(r.slug()).isEqualTo(e.getSlug());
        assertThat(r.status()).isEqualTo("live");
        assertThat(r.organization().name()).isEqualTo("Test Org");
        assertThat(r.organization().slug()).isEqualTo(org.getSlug());
        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 2. Past event stays reachable (share-link case)
    // -----------------------------------------------------------------------
    @Test
    void returnsEvent_whenStatusPast() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.PAST);
        eventRepository.save(e);

        PublicEventResponse r = publicEventService.get(e.getId());
        assertThat(r.status()).isEqualTo("past");
    }

    // -----------------------------------------------------------------------
    // 3. Cancelled event stays reachable (share-link case)
    // -----------------------------------------------------------------------
    @Test
    void returnsEvent_whenStatusCancelled() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.CANCELLED);
        eventRepository.save(e);

        PublicEventResponse r = publicEventService.get(e.getId());
        assertThat(r.status()).isEqualTo("cancelled");
    }

    // -----------------------------------------------------------------------
    // 4. Draft → 404
    // -----------------------------------------------------------------------
    @Test
    void throws404_whenStatusDraft() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.DRAFT);
        e.setPublishedAt(null); // drafts have no publishedAt
        eventRepository.save(e);

        assertThatThrownBy(() -> publicEventService.get(e.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // -----------------------------------------------------------------------
    // 5. Private visibility → 404
    // -----------------------------------------------------------------------
    @Test
    void throws404_whenVisibilityPrivate() {
        Event e = publishedLiveEvent();
        e.setVisibility(EventVisibility.PRIVATE);
        eventRepository.save(e);

        assertThatThrownBy(() -> publicEventService.get(e.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // -----------------------------------------------------------------------
    // 6. Soft-deleted → 404
    // -----------------------------------------------------------------------
    @Test
    void throws404_whenSoftDeleted() {
        Event e = publishedLiveEvent();
        e.setDeletedAt(NOW.minusSeconds(60));
        eventRepository.save(e);

        assertThatThrownBy(() -> publicEventService.get(e.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // -----------------------------------------------------------------------
    // 7. publishedAt is null + LIVE → 404 (defensive)
    // -----------------------------------------------------------------------
    @Test
    void throws404_whenPublishedAtNull() {
        Event e = publishedLiveEvent();
        e.setPublishedAt(null);
        eventRepository.save(e);

        assertThatThrownBy(() -> publicEventService.get(e.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // -----------------------------------------------------------------------
    // 8. Unknown id → 404
    // -----------------------------------------------------------------------
    @Test
    void throws404_whenIdMissing() {
        assertThatThrownBy(() -> publicEventService.get(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // -----------------------------------------------------------------------
    // 9. Disabled tiers are excluded; enabled tiers are ordered by sort_order
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_disabledExcluded() {
        Event e = eventRepository.save(publishedLiveEvent());
        tier(e.getId(), "Tier A", 1000, 100, 0, true,  10, null);
        tier(e.getId(), "Tier B", 2000, 100, 0, false, 20, null); // disabled — must be excluded
        tier(e.getId(), "Tier C", 3000, 100, 0, true,  5,  null);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).hasSize(2);
        assertThat(r.tiers().get(0).name()).isEqualTo("Tier C"); // sort_order=5
        assertThat(r.tiers().get(1).name()).isEqualTo("Tier A"); // sort_order=10
    }

    // -----------------------------------------------------------------------
    // 10. Sold-out tier — hidden from public listing
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_soldOutExcluded() {
        Event e = eventRepository.save(publishedLiveEvent());
        e.setOnSaleAt(NOW.minusSeconds(3600));
        eventRepository.save(e);

        tier(e.getId(), "Sold Out", 500, 50, 50, true, 0, null); // sold==quantity

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 11. Tier with saleClosesAt < now → closed, hidden from public listing
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_closedByTierExcluded() {
        Event e = eventRepository.save(publishedLiveEvent());
        e.setOnSaleAt(NOW.minusSeconds(3600));
        eventRepository.save(e);

        Instant closeTime = NOW.minusSeconds(60); // already past
        tier(e.getId(), "Closed Tier", 500, 50, 0, true, 0, closeTime);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 12. Event saleClosesAt < now → all tiers closed, hidden
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_closedByEventExcluded() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.minusSeconds(7200));
        e.setSaleClosesAt(NOW.minusSeconds(60)); // event sale window closed
        e = eventRepository.save(e);

        tier(e.getId(), "Tier 1", 500, 50, 0, true, 0, null);
        tier(e.getId(), "Tier 2", 500, 50, 0, true, 1, null);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 13. Event onSaleAt > now → tiers not yet open, hidden
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_notYetOpenExcluded() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.plusSeconds(3600)); // opens in the future
        e = eventRepository.save(e);

        tier(e.getId(), "Future Tier", 1000, 50, 0, true, 0, null);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 14. Cancelled event → all tiers hidden
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_eventCancelledExcluded() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.CANCELLED);
        e.setOnSaleAt(NOW.minusSeconds(3600)); // window open
        e = eventRepository.save(e);

        tier(e.getId(), "Normal Tier", 500, 50, 0, true, 0, null); // plenty of inventory

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.status()).isEqualTo("cancelled");
        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 15. Past event → all tiers hidden
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_eventPastExcluded() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.PAST);
        e.setOnSaleAt(NOW.minusSeconds(3600));
        e = eventRepository.save(e);

        tier(e.getId(), "Normal Tier", 500, 50, 0, true, 0, null);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.status()).isEqualTo("past");
        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 16. Mixed tiers under a live event → only the on-sale tier is returned
    // -----------------------------------------------------------------------
    @Test
    void tiersFiltered_mixedSet_onlyOnSaleReturned() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.minusSeconds(3600));
        e = eventRepository.save(e);

        tier(e.getId(), "Open Tier",       500, 50, 0,  true, 0, null);            // on sale
        tier(e.getId(), "Sold-Out Tier",   500, 50, 50, true, 1, null);            // hidden
        tier(e.getId(), "Closed Tier",     500, 50, 0,  true, 2, NOW.minusSeconds(60)); // hidden
        tier(e.getId(), "Disabled Tier",   500, 50, 0,  false, 3, null);           // hidden

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).name()).isEqualTo("Open Tier");
        assertThat(r.tiers().get(0).onSale()).isTrue();
    }

    // -----------------------------------------------------------------------
    // includeUnavailable tests
    // -----------------------------------------------------------------------

    @Test
    void includeUnavailable_true_returnsSoldOutTierWithSoldOutFlag() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.minusSeconds(3600));
        e = eventRepository.save(e);

        tier(e.getId(), "Sold Out", 500, 50, 50, true, 0, null);

        PublicEventResponse r = publicEventService.get(e.getId(), true);

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).name()).isEqualTo("Sold Out");
        assertThat(r.tiers().get(0).soldOut()).isTrue();
        assertThat(r.tiers().get(0).onSale()).isFalse();
        assertThat(r.tiers().get(0).remaining()).isZero();
    }

    @Test
    void includeUnavailable_true_returnsClosedTierWithClosedFlag() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.minusSeconds(3600));
        e = eventRepository.save(e);

        tier(e.getId(), "Closed Tier", 500, 50, 0, true, 0, NOW.minusSeconds(60));

        PublicEventResponse r = publicEventService.get(e.getId(), true);

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).name()).isEqualTo("Closed Tier");
        assertThat(r.tiers().get(0).closed()).isTrue();
        assertThat(r.tiers().get(0).onSale()).isFalse();
    }

    @Test
    void includeUnavailable_true_returnsNotYetOpenTier() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.plusSeconds(3600)); // not yet open
        e = eventRepository.save(e);

        tier(e.getId(), "Future Tier", 1000, 50, 0, true, 0, null);

        PublicEventResponse r = publicEventService.get(e.getId(), true);

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).name()).isEqualTo("Future Tier");
        assertThat(r.tiers().get(0).onSale()).isFalse();
        assertThat(r.tiers().get(0).soldOut()).isFalse();
        assertThat(r.tiers().get(0).closed()).isFalse();
    }

    @Test
    void includeUnavailable_true_stillExcludesDisabledTiers() {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.minusSeconds(3600));
        e = eventRepository.save(e);

        tier(e.getId(), "Open Tier",     500, 50, 0,  true,  0, null);
        tier(e.getId(), "Disabled Tier", 500, 50, 0,  false, 1, null); // disabled — still hidden

        PublicEventResponse r = publicEventService.get(e.getId(), true);

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).name()).isEqualTo("Open Tier");
    }

    @Test
    void includeUnavailable_true_returnsAllUnavailableTiersOnPastEvent() {
        // Past event is still publicly reachable (share-links / SEO). With includeUnavailable=true
        // the FE wants to render its tier roster as greyed-out rows.
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.PAST);
        e.setOnSaleAt(NOW.minusSeconds(7200));
        e = eventRepository.save(e);

        tier(e.getId(), "GA",  500, 50, 10, true, 0, null);
        tier(e.getId(), "VIP", 1500, 20, 5,  true, 1, null);

        PublicEventResponse r = publicEventService.get(e.getId(), true);

        assertThat(r.status()).isEqualTo("past");
        assertThat(r.tiers()).hasSize(2);
        assertThat(r.tiers()).allSatisfy(t -> assertThat(t.onSale()).isFalse());
    }

    @Test
    void includeUnavailable_true_throws404_whenEventNotPublic() {
        // Event-level filter (draft/private/deleted) must still 404 — includeUnavailable
        // only controls tier visibility, not event visibility.
        Event e = publishedLiveEvent();
        e.setVisibility(EventVisibility.PRIVATE);
        e = eventRepository.save(e);

        UUID eventId = e.getId();
        assertThatThrownBy(() -> publicEventService.get(eventId, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    @Test
    void includeUnavailable_false_preservesLegacyBehavior() {
        // Sanity: explicit false matches the no-arg overload (sold-out hidden).
        Event e = publishedLiveEvent();
        e.setOnSaleAt(NOW.minusSeconds(3600));
        e = eventRepository.save(e);

        tier(e.getId(), "Sold Out", 500, 50, 50, true, 0, null);

        PublicEventResponse r = publicEventService.get(e.getId(), false);

        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // includeOngoing — listing predicate (companion item 4)
    // -----------------------------------------------------------------------

    private com.imin.iminapi.service.event.PublicEventListQuery listQuery(
            Instant from, boolean includeOngoing) {
        return new com.imin.iminapi.service.event.PublicEventListQuery(
                from, null, null, null, null, null, null, null,
                false, includeOngoing, 1, 20);
    }

    @Test
    void list_excludesOngoingEvent_whenIncludeOngoingFalse() {
        // Event started 1h ago, ends 1h from now => doors-open. from = now.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(3600));
        e.setEndsAt(NOW.plusSeconds(3600));
        eventRepository.save(e);

        var result = publicEventService.list(listQuery(NOW, false));

        assertThat(result.items()).isEmpty();
    }

    @Test
    void list_includesOngoingEvent_whenIncludeOngoingTrue() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(3600));
        e.setEndsAt(NOW.plusSeconds(3600));
        eventRepository.save(e);

        var result = publicEventService.list(listQuery(NOW, true));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(e.getId());
    }

    @Test
    void list_dropsFinishedEvent_evenWhenIncludeOngoingTrue() {
        // Started and ended in the past => truly finished, must drop in both modes.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(7200));
        e.setEndsAt(NOW.minusSeconds(3600));
        eventRepository.save(e);

        assertThat(publicEventService.list(listQuery(NOW, true)).items()).isEmpty();
        assertThat(publicEventService.list(listQuery(NOW, false)).items()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // soldOut / lowStock — listing availability flags (companion item 5)
    // -----------------------------------------------------------------------

    @Test
    void list_soldOut_whenEveryEnabledTierRemainingIsZero() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA",  500, 50, 50, true, 0, null);  // remaining 0
        tier(e.getId(), "VIP", 1500, 20, 20, true, 1, null); // remaining 0

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).soldOut()).isTrue();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }

    @Test
    void list_lowStock_whenTotalRemainingBelowThresholdButNotSoldOut() {
        // Default threshold = 10. Total remaining = 4 < 10, > 0 => lowStock.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA",  500, 50, 48, true, 0, null);  // remaining 2
        tier(e.getId(), "VIP", 1500, 20, 18, true, 1, null); // remaining 2

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items().get(0).soldOut()).isFalse();
        assertThat(result.items().get(0).lowStock()).isTrue();
    }

    @Test
    void list_neitherFlag_whenAmpleRemaining() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA", 500, 100, 0, true, 0, null); // remaining 100

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items().get(0).soldOut()).isFalse();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }

    @Test
    void list_disabledTiersExcludedFromAvailability() {
        // The only tier with remaining stock is disabled => event reads as sold out.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA",       500, 50, 50,  true,  0, null); // enabled, remaining 0
        tier(e.getId(), "Disabled", 500, 50, 0,   false, 1, null); // disabled, ignored

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items().get(0).soldOut()).isTrue();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }

    @Test
    void list_notSoldOut_whenEventHasNoEnabledTiers() {
        // No enabled tiers => no inventory rows => neither flag set (cannot be "sold out").
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).soldOut()).isFalse();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }
}
