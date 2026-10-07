package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@IminIntegrationTest
// Read-only service: rollback keeps these LIVE PUBLIC events out of every other class's global listing.
@Transactional
class PublicEventServiceTest {

    /** Fixed "now" for all flag-computation tests: 2026-06-01T12:00:00Z */
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired PublicEventService publicEventService;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired TicketTierRepository ticketTierRepository;
    @Autowired UserRepository userRepository;
    @Autowired MutableClock clock;

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);

        org = new Organization();
        // Do NOT set the ID — let @GeneratedValue(strategy = UUID) generate it;
        // setting it manually causes JPA to call merge() on a detached entity.
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID());
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
    Event publishedLiveEvent() {
        Event e = new Event();
        // Do NOT manually set ID — let @GeneratedValue generate it
        e.setOrgId(org.getId());
        e.setName("Great Event");
        e.setSlug("great-event-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCreatedBy(owner.getId()); // FK to users(id)
        e.setCurrency("EUR");
        return e;
    }

    Event saved(Event e) {
        return eventRepository.save(e);
    }

    TicketTier tier(UUID eventId, String name, int priceMinor, int quantity, int sold,
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

    private static void assertNotFound(Throwable ex) {
        assertThat(ex).isInstanceOf(ApiException.class);
        ApiException apiEx = (ApiException) ex;
        assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(apiEx.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // -----------------------------------------------------------------------
    // Reachable: published + public, whatever its lifecycle (share-link case)
    // -----------------------------------------------------------------------
    static Stream<Arguments> reachable() {
        return Stream.of(
                Arguments.of(EventStatus.LIVE, "live"),
                Arguments.of(EventStatus.PAST, "past"),
                Arguments.of(EventStatus.CANCELLED, "cancelled"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reachable")
    void returnsEvent_whenPublishedAndPublic(EventStatus status, String wire) {
        Event e = publishedLiveEvent();
        e.setStatus(status);
        e = saved(e);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.id()).isEqualTo(e.getId());
        assertThat(r.name()).isEqualTo("Great Event");
        assertThat(r.slug()).isEqualTo(e.getSlug());
        assertThat(r.status()).isEqualTo(wire);
        assertThat(r.organization().name()).isEqualTo("Test Org");
        assertThat(r.organization().slug()).isEqualTo(org.getSlug());
        assertThat(r.tiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // No-leak 404
    // -----------------------------------------------------------------------
    static Stream<Arguments> notPublic() {
        return Stream.of(
                // An unpublished event keeps its publishedAt, so only the status clause hides it.
                Arguments.of("draft", (Consumer<Event>) e -> e.setStatus(EventStatus.DRAFT)),
                Arguments.of("private", (Consumer<Event>) e -> e.setVisibility(EventVisibility.PRIVATE)),
                Arguments.of("soft-deleted", (Consumer<Event>) e -> e.setDeletedAt(NOW.minusSeconds(60))),
                // Defensive: LIVE with a null publishedAt.
                Arguments.of("publishedAt null", (Consumer<Event>) e -> e.setPublishedAt(null)),
                Arguments.of("unknown id", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notPublic")
    void throws404_whenNotPubliclyVisible(String name, Consumer<Event> hide) {
        UUID id;
        if (hide == null) {
            id = UUID.randomUUID();
        } else {
            Event e = publishedLiveEvent();
            hide.accept(e);
            id = saved(e).getId();
        }

        assertThatThrownBy(() -> publicEventService.get(id))
                .satisfies(PublicEventServiceTest::assertNotFound);
    }

    // -----------------------------------------------------------------------
    // Default detail: only on-sale tiers, ordered by sort_order
    // -----------------------------------------------------------------------
    static Stream<Arguments> hiddenTiers() {
        return Stream.of(
                Arguments.of("disabled excluded, enabled ordered by sort_order",
                        (Function<PublicEventServiceTest, Event>) t -> {
                            Event e = t.saved(t.publishedLiveEvent());
                            t.tier(e.getId(), "Tier A", 1000, 100, 0, true, 10, null);
                            t.tier(e.getId(), "Tier B", 2000, 100, 0, false, 20, null);
                            t.tier(e.getId(), "Tier C", 3000, 100, 0, true, 5, null);
                            return e;
                        }, List.of("Tier C", "Tier A")),
                Arguments.of("sold out", (Function<PublicEventServiceTest, Event>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setOnSaleAt(NOW.minusSeconds(3600));
                    e = t.saved(e);
                    t.tier(e.getId(), "Sold Out", 500, 50, 50, true, 0, null);
                    return e;
                }, List.of()),
                Arguments.of("closed by tier", (Function<PublicEventServiceTest, Event>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setOnSaleAt(NOW.minusSeconds(3600));
                    e = t.saved(e);
                    t.tier(e.getId(), "Closed Tier", 500, 50, 0, true, 0, NOW.minusSeconds(60));
                    return e;
                }, List.of()),
                Arguments.of("closed by event", (Function<PublicEventServiceTest, Event>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setOnSaleAt(NOW.minusSeconds(7200));
                    e.setSaleClosesAt(NOW.minusSeconds(60));
                    e = t.saved(e);
                    t.tier(e.getId(), "Tier 1", 500, 50, 0, true, 0, null);
                    t.tier(e.getId(), "Tier 2", 500, 50, 0, true, 1, null);
                    return e;
                }, List.of()),
                Arguments.of("not yet open", (Function<PublicEventServiceTest, Event>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setOnSaleAt(NOW.plusSeconds(3600));
                    e = t.saved(e);
                    t.tier(e.getId(), "Future Tier", 1000, 50, 0, true, 0, null);
                    return e;
                }, List.of()),
                Arguments.of("event cancelled", (Function<PublicEventServiceTest, Event>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setStatus(EventStatus.CANCELLED);
                    e.setOnSaleAt(NOW.minusSeconds(3600));
                    e = t.saved(e);
                    t.tier(e.getId(), "Normal Tier", 500, 50, 0, true, 0, null);
                    return e;
                }, List.of()),
                Arguments.of("event past", (Function<PublicEventServiceTest, Event>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setStatus(EventStatus.PAST);
                    e.setOnSaleAt(NOW.minusSeconds(3600));
                    e = t.saved(e);
                    t.tier(e.getId(), "Normal Tier", 500, 50, 0, true, 0, null);
                    return e;
                }, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hiddenTiers")
    void tiersFiltered_toWhatIsOnSale(String name, Function<PublicEventServiceTest, Event> arrange,
                                      List<String> visible) {
        Event e = arrange.apply(this);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).extracting(PublicTierDto::name).containsExactlyElementsOf(visible);
    }

    // -----------------------------------------------------------------------
    // Mixed tiers under a live event → only the on-sale tier is returned
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
    // Detail tiers carry the all-in price
    //
    // priceMinor alone is not a lawful first display price (Code conso. L112-1,
    // CRD Art.6(1)(e)) and it made the same event read cheaper on its own page
    // than on the listing card, whose priceFromMinor has always been all-in.
    // A free tier is free: no fee on a €0 net total, matching QuoteService.
    // -----------------------------------------------------------------------
    @ParameterizedTest(name = "{0} → {1}")
    @org.junit.jupiter.params.provider.CsvSource({
            // 5% of 2500 = 125, plus the 99 flat per-ticket fee.
            "2500, 2724",
            "0, 0"
    })
    void detailTiers_carryTheAllInPrice(int priceMinor, long allIn) {
        Event e = eventRepository.save(publishedLiveEvent());
        tier(e.getId(), "GA", priceMinor, 100, 0, true, 10, null);

        PublicEventResponse r = publicEventService.get(e.getId());

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).priceMinor()).isEqualTo(priceMinor);
        assertThat(r.tiers().get(0).priceAllInMinor()).isEqualTo(allIn);
    }

    // -----------------------------------------------------------------------
    // includeUnavailable tests
    // -----------------------------------------------------------------------
    static Stream<Arguments> unavailable() {
        return Stream.of(
                Arguments.of("sold out", NOW.minusSeconds(3600), 50, null, true, false),
                Arguments.of("closed", NOW.minusSeconds(3600), 0, NOW.minusSeconds(60), false, true),
                Arguments.of("not yet open", NOW.plusSeconds(3600), 0, null, false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailable")
    void includeUnavailable_true_returnsTheTierWithItsFlags(String name, Instant onSaleAt, int sold,
                                                            Instant saleClosesAt, boolean soldOut, boolean closed) {
        Event e = publishedLiveEvent();
        e.setOnSaleAt(onSaleAt);
        e = eventRepository.save(e);

        tier(e.getId(), "Unavailable", 500, 50, sold, true, 0, saleClosesAt);

        PublicEventResponse r = publicEventService.get(e.getId(), true);

        assertThat(r.tiers()).hasSize(1);
        assertThat(r.tiers().get(0).name()).isEqualTo("Unavailable");
        assertThat(r.tiers().get(0).onSale()).isFalse();
        assertThat(r.tiers().get(0).soldOut()).isEqualTo(soldOut);
        assertThat(r.tiers().get(0).closed()).isEqualTo(closed);
        if (soldOut) assertThat(r.tiers().get(0).remaining()).isZero();
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
                .satisfies(PublicEventServiceTest::assertNotFound);
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
    // venue coordinates (V80)
    // -----------------------------------------------------------------------
    static Stream<Arguments> coordinates() {
        return Stream.of(
                Arguments.of("geocoded", 52.5111d, 13.4432d),
                // The default state: geocoding is off by default, so the buyer page must still
                // render the address and its maps deep link off these strings alone.
                Arguments.of("never geocoded", null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coordinates")
    void venue_carries_the_stored_coordinates(String name, Double latitude, Double longitude) {
        Event e = publishedLiveEvent();
        e.setVenueName("Berghain");
        e.setVenueStreet("Am Wriezener Bahnhof");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10243");
        e.setVenueCountry("DE");
        e.setVenueLatitude(latitude);
        e.setVenueLongitude(longitude);
        e = eventRepository.save(e);

        PublicEventResponse resp = publicEventService.get(e.getId());
        assertThat(resp.venue().latitude()).isEqualTo(latitude);
        assertThat(resp.venue().longitude()).isEqualTo(longitude);
        assertThat(resp.venue().city()).isEqualTo("Berlin");
    }
}
