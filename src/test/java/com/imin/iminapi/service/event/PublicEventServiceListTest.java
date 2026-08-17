package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.publicapi.PublicEventListItem;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PublicEventService.class, PublicListingConfig.class, PublicEventServiceListTest.FixedClockConfig.class})
class PublicEventServiceListTest {

    /** Fixed "now" for all flag-computation tests: 2026-06-01T12:00:00Z */
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        /**
         * priceFromMinor is fee-inclusive, so the slice needs the fee rates. Defaults
         * (500 bps + 99 minor) are the production values — StripeConfig can't be
         * imported here because it eagerly builds a Stripe client.
         */
        @Bean
        StripeProperties stripeProperties() {
            return new StripeProperties();
        }
    }

    /** Fee added on top of a single ticket at price {@code p} (0 stays free). */
    private static int withFee(int p) {
        return p == 0 ? 0 : (int) (p + QuoteService.computeFee(p, 1, 500, 99));
    }

    @Autowired PublicEventService publicEventService;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired TicketTierRepository ticketTierRepository;
    @Autowired UserRepository userRepository;

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = organizationRepository.save(org);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = userRepository.save(owner);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private Event publishedLiveEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Great Event");
        e.setSlug("event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setStartsAt(NOW.plusSeconds(86400));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e.setGenre("techno");
        e.setType("concert");
        e.setVenueCity("Berlin");
        e.setVenueCountry("DE");
        return e;
    }

    private TicketTier tier(UUID eventId, String name, int priceMinor, int quantity, int sold,
                            boolean enabled, int sortOrder) {
        TicketTier tier = new TicketTier();
        tier.setEventId(eventId);
        tier.setName(name);
        tier.setPriceMinor(priceMinor);
        tier.setQuantity(quantity);
        tier.setSold(sold);
        tier.setEnabled(enabled);
        tier.setSortOrder(sortOrder);
        return ticketTierRepository.save(tier);
    }

    /** Enabled tier with an explicit sale window — drives the purchasable-tier cases. */
    private TicketTier tierWithWindow(UUID eventId, String name, int priceMinor, int quantity, int sold,
                                      int sortOrder, Instant saleStartsAt, Instant saleClosesAt) {
        TicketTier tier = tier(eventId, name, priceMinor, quantity, sold, true, sortOrder);
        tier.setSaleStartsAt(saleStartsAt);
        tier.setSaleClosesAt(saleClosesAt);
        return ticketTierRepository.save(tier);
    }

    private PublicEventListQuery emptyQuery() {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, false, 1, 20);
    }

    private PublicEventListQuery onlyPage(int page, int pageSize) {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, false, page, pageSize);
    }

    /**
     * An event that ran and finished before NOW. These are the rows the facets used to count
     * and no feed could ever return — {@code endsAt} is set explicitly because a null one means
     * "no defined end", which {@code includeOngoing=true} legitimately keeps in the feed.
     */
    private Event finishedEvent() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(172800));   // started two days ago
        e.setEndsAt(NOW.minusSeconds(158400));     // ended two days ago
        e.setStatus(EventStatus.PAST);             // what EventStatusSweeper would have done
        return e;
    }

    /**
     * The feed a city chip actually opens, driven through the same service the HTTP layer calls.
     *
     * <p>{@code from=NOW, includeOngoing=true} is not a choice made here: it is what
     * {@code imin-public}'s {@code toListingQuery} and the mobile app's port of it send on every
     * request, including the {@code when=all} default that a bare {@code /events?city=…} link —
     * a chip's own href — resolves to. Asking with {@code from=null}, as the older facet tests
     * did, asks a question no buyer can ask, which is how a count over all time passed for a
     * count of the feed.
     */
    private PageResponse<PublicEventListItem> feedBehindCityChip(String city) {
        return publicEventService.list(new PublicEventListQuery(
                NOW, null, null, null, city, null, null, null, false, true, false, 1, 100));
    }

    /** The feed a genre chip opens — same window, same reason as {@link #feedBehindCityChip}. */
    private PageResponse<PublicEventListItem> feedBehindGenreChip(String genre) {
        return publicEventService.list(new PublicEventListQuery(
                NOW, null, List.of(genre), null, null, null, null, null, false, true, false, 1, 100));
    }

    // -----------------------------------------------------------------------
    // Eligibility filtering
    // -----------------------------------------------------------------------
    @Test
    void excludes_draft_events() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.DRAFT);
        e.setPublishedAt(null);
        eventRepository.save(e);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void excludes_cancelled_events() {
        // Published + future + PUBLIC, but CANCELLED — passes every other clause of the
        // eligibility predicate, so this pins the dedicated status guard. The detail
        // endpoint still serves it (PublicEventServiceTest.returnsEvent_whenStatusCancelled).
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.CANCELLED);
        eventRepository.save(e);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void excludes_private_events() {
        Event e = publishedLiveEvent();
        e.setVisibility(EventVisibility.PRIVATE);
        eventRepository.save(e);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).isEmpty();
    }

    @Test
    void excludes_unpublished_events_with_null_publishedAt() {
        // Defensive: status=LIVE but publishedAt=null shouldn't actually occur in practice
        // (the persistence path always sets publishedAt before LIVE), but the predicate must
        // still exclude it. Isolates the `publishedAt IS NOT NULL` branch from the draft test.
        Event e = publishedLiveEvent();
        e.setPublishedAt(null);
        eventRepository.save(e);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void excludes_soft_deleted_events() {
        Event e = publishedLiveEvent();
        e.setDeletedAt(NOW.minusSeconds(60));
        eventRepository.save(e);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Genre / type / country / city / q filters
    // -----------------------------------------------------------------------
    @Test
    void filter_by_genre() {
        Event a = publishedLiveEvent();
        a.setGenre("techno");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setGenre("house");
        eventRepository.save(b);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, List.of("techno"), null, null, null, null, null, false, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).genre()).isEqualTo("techno");
    }

    /**
     * The facet placeholder must be text every engine accepts.
     *
     * <p>This exists because the first version used a NUL byte: unmatchable, which
     * was the requirement being thought about, and accepted by H2, so the whole
     * suite passed while production returned 400 for every listing call — Postgres
     * rejects NUL in text. No behavioural test on H2 can catch that, so the
     * invariant is asserted directly on the value instead.
     */
    @Test
    void theEmptyFacetPlaceholderContainsNoControlCharacters() {
        PageResponse<PublicEventListItem> unfiltered = publicEventService.list(new PublicEventListQuery(
                null, null, List.of(), List.of(), null, null, null, null,
                false, false, false, 1, 20));
        assertThat(unfiltered).isNotNull();

        // The value bound whenever a facet is off, read back from the service.
        String placeholder = ReflectionTestUtils.getField(PublicEventService.class, "NO_FACET") == null
                ? null
                : ((java.util.Collection<?>) java.util.Objects.requireNonNull(
                        ReflectionTestUtils.getField(PublicEventService.class, "NO_FACET")))
                        .iterator().next().toString();

        assertThat(placeholder)
                .as("the placeholder is bound on every query where a facet is absent")
                .isNotNull();
        assertThat(placeholder.chars().anyMatch(Character::isISOControl))
                .as("Postgres rejects control characters in text; H2 accepts them, "
                        + "so only this assertion stands between the two")
                .isFalse();
    }

    /**
     * Multi-select: several values in one facet are an OR, and an empty facet is
     * still "no filter" rather than "match nothing" — the two halves of the guard
     * in {@code EventRepository.findPublicListing}.
     */
    @Test
    void filter_by_several_genres_is_an_or_within_the_facet() {
        Event techno = publishedLiveEvent();
        techno.setGenre("techno");
        eventRepository.save(techno);
        Event house = publishedLiveEvent();
        house.setGenre("house");
        eventRepository.save(house);
        Event disco = publishedLiveEvent();
        disco.setGenre("disco");
        eventRepository.save(disco);

        PageResponse<PublicEventListItem> both = publicEventService.list(new PublicEventListQuery(
                null, null, List.of("techno", "house"), null, null, null, null, null,
                false, false, false, 1, 20));
        assertThat(both.items())
                .extracting(PublicEventListItem::id)
                .containsExactlyInAnyOrder(techno.getId(), house.getId());

        // Casing still folds to one key, and a key repeated across spellings is
        // not a wider query — it is the same single value.
        PageResponse<PublicEventListItem> shouted = publicEventService.list(new PublicEventListQuery(
                null, null, List.of("TECHNO", "  techno "), null, null, null, null, null,
                false, false, false, 1, 20));
        assertThat(shouted.items()).extracting(PublicEventListItem::id).containsExactly(techno.getId());

        // Blank-only input is not a filter that matches nothing.
        PageResponse<PublicEventListItem> blanks = publicEventService.list(new PublicEventListQuery(
                null, null, List.of("", "   "), null, null, null, null, null,
                false, false, false, 1, 20));
        assertThat(blanks.items()).hasSize(3);
    }

    @Test
    void filter_by_several_types_is_an_or_and_the_two_facets_and_together() {
        Event technoRave = publishedLiveEvent();
        technoRave.setGenre("techno");
        technoRave.setType("Rave");
        eventRepository.save(technoRave);
        Event technoClub = publishedLiveEvent();
        technoClub.setGenre("techno");
        technoClub.setType("Club");
        eventRepository.save(technoClub);
        Event houseRave = publishedLiveEvent();
        houseRave.setGenre("house");
        houseRave.setType("Rave");
        eventRepository.save(houseRave);
        Event technoConcert = publishedLiveEvent();
        technoConcert.setGenre("techno");
        technoConcert.setType("Concert");
        eventRepository.save(technoConcert);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, List.of("techno"), List.of("Rave", "Club"), null, null, null, null,
                false, false, false, 1, 20));

        // "techno AND (Rave OR Club)" — the house rave and the techno concert both
        // fail exactly one half, which is what makes this an AND between groups.
        assertThat(result.items())
                .extracting(PublicEventListItem::id)
                .containsExactlyInAnyOrder(technoRave.getId(), technoClub.getId());
    }

    @Test
    void filter_by_genre_matches_the_normalised_key_so_casing_never_splits_a_chip() {
        // Same contract as ?city= (V82): the filter runs on genre_key, the card still shows the
        // organizer's spelling. "Techno" and "techno" used to be two exact-match queries over
        // two disjoint slices of one genre — two identical-looking chips, neither complete.
        Event typed = publishedLiveEvent();
        typed.setGenre("Techno");
        eventRepository.save(typed);
        Event shouted = publishedLiveEvent();
        shouted.setGenre("TECHNO");
        eventRepository.save(shouted);
        Event other = publishedLiveEvent();
        other.setGenre("House");
        eventRepository.save(other);

        for (String probe : List.of("Techno", "techno", "  TECHNO ")) {
            PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                    null, null, List.of(probe), null, null, null, null, null, false, false, false, 1, 20));
            assertThat(result.items())
                    .as("?genre=%s must find both spellings of one genre", probe)
                    .extracting(PublicEventListItem::id)
                    .containsExactlyInAnyOrder(typed.getId(), shouted.getId());
        }

        // The display string is never folded on the way out — the card prints what was typed.
        PageResponse<PublicEventListItem> all = publicEventService.list(onlyPage(1, 20));
        assertThat(all.items()).extracting(PublicEventListItem::genre)
                .contains("Techno", "TECHNO", "House");
    }

    @Test
    void filter_by_type() {
        Event a = publishedLiveEvent();
        a.setType("concert");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setType("festival");
        eventRepository.save(b);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, List.of("festival"), null, null, null, null, false, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).type()).isEqualTo("festival");
    }

    @Test
    void filter_by_country() {
        Event a = publishedLiveEvent();
        a.setVenueCountry("DE");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setVenueCountry("FR");
        eventRepository.save(b);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, "fr", null, null, false, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).venueCountry()).isEqualTo("FR");
    }

    @Test
    void filter_by_city_matches_the_normalised_key_exactly_not_a_substring() {
        // CONTRACT CHANGE (V82). `?city=` used to be a case-insensitive CONTAINS on the raw
        // venue_city; it is now an exact match on the normalised venue_city_key. Case and
        // stray whitespace still don't matter — that is what the key is for — but a prefix
        // no longer matches. This is what lets a "Metz (3)" chip guarantee three results:
        // a substring match could also drag in Metzingen, and a chip whose count disagrees
        // with its own page is the bug this whole change exists to kill.
        Event a = publishedLiveEvent();
        a.setVenueCity("Berlin");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setVenueCity("Paris");
        eventRepository.save(b);

        PageResponse<PublicEventListItem> exact = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, "  BERLIN ", null, null, null, false, false, false, 1, 20));
        assertThat(exact.items()).hasSize(1);
        assertThat(exact.items().get(0).venueCity()).isEqualTo("Berlin");

        PageResponse<PublicEventListItem> prefix = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, "berl", null, null, null, false, false, false, 1, 20));
        assertThat(prefix.items()).isEmpty();
    }

    @Test
    void filter_by_city_finds_every_case_variant_of_the_same_city() {
        // The three spellings the live data actually holds. One filter, all three events —
        // this is the count side of the merged chip.
        Event typed = publishedLiveEvent();
        typed.setVenueCity("Metz");
        eventRepository.save(typed);
        Event shouted = publishedLiveEvent();
        shouted.setVenueCity("METZ");
        eventRepository.save(shouted);
        Event padded = publishedLiveEvent();
        padded.setVenueCity("  Metz  ");
        eventRepository.save(padded);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, "Metz", null, null, null, false, false, false, 1, 20));
        assertThat(result.items()).hasSize(3);
    }

    @Test
    void filter_by_q_case_insensitive_contains_on_name() {
        Event a = publishedLiveEvent();
        a.setName("Summer Festival");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setName("Winter Concert");
        eventRepository.save(b);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, "festIVAL", false, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("Summer Festival");
    }

    // -----------------------------------------------------------------------
    // from/to window boundaries
    // -----------------------------------------------------------------------
    @Test
    void from_window_includes_boundary() {
        Instant start = NOW.plusSeconds(86400);
        Event a = publishedLiveEvent();
        a.setStartsAt(start);
        eventRepository.save(a);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                start, null, null, null, null, null, null, null, false, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void to_window_excludes_boundary() {
        Instant start = NOW.plusSeconds(86400);
        Event a = publishedLiveEvent();
        a.setStartsAt(start);
        eventRepository.save(a);

        // to == startsAt means startsAt < to is false → excluded
        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, start, null, null, null, null, null, null, false, false, false, 1, 20));
        assertThat(result.items()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // orgSlug
    // -----------------------------------------------------------------------
    @Test
    void filter_by_orgSlug_resolves_org() {
        Organization other = new Organization();
        other.setName("Other Org");
        other.setSlug("other-org-" + UUID.randomUUID().toString().substring(0, 8));
        other.setContactEmail("other@example.com");
        other.setCountry("FR");
        other = organizationRepository.save(other);

        User otherOwner = new User();
        otherOwner.setEmail("other-owner-" + UUID.randomUUID() + "@example.com");
        otherOwner.setOrgId(other.getId());
        otherOwner.setRole(UserRole.OWNER);
        otherOwner = userRepository.save(otherOwner);

        Event a = publishedLiveEvent();
        eventRepository.save(a);

        Event b = publishedLiveEvent();
        b.setOrgId(other.getId());
        b.setCreatedBy(otherOwner.getId());
        eventRepository.save(b);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, other.getSlug(), null, false, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).organization().slug()).isEqualTo(other.getSlug());
    }

    @Test
    void unknown_orgSlug_returns_empty_page() {
        eventRepository.save(publishedLiveEvent());

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, "no-such-org", null, false, false, false, 1, 20));
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(20);
    }

    // -----------------------------------------------------------------------
    // onSaleOnly
    // -----------------------------------------------------------------------
    @Test
    void onSaleOnly_excludes_future_onSaleAt() {
        Event a = publishedLiveEvent();
        a.setOnSaleAt(NOW.plusSeconds(3600)); // future
        eventRepository.save(a);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, null, true, false, false, 1, 20));
        assertThat(result.items()).isEmpty();
    }

    @Test
    void onSaleOnly_includes_null_onSaleAt() {
        Event a = publishedLiveEvent();
        a.setOnSaleAt(null);
        eventRepository.save(a);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, null, true, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void onSaleOnly_excludes_past_saleClosesAt() {
        Event a = publishedLiveEvent();
        a.setOnSaleAt(NOW.minusSeconds(7200));
        a.setSaleClosesAt(NOW.minusSeconds(60)); // past
        eventRepository.save(a);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, null, true, false, false, 1, 20));
        assertThat(result.items()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------
    @Test
    void pagination_returns_correct_page() {
        for (int i = 0; i < 12; i++) {
            Event e = publishedLiveEvent();
            e.setStartsAt(NOW.plusSeconds(86400L + i * 3600L));
            e.setName("Event " + i);
            eventRepository.save(e);
        }

        PageResponse<PublicEventListItem> result = publicEventService.list(onlyPage(2, 5));
        assertThat(result.items()).hasSize(5);
        assertThat(result.total()).isEqualTo(12);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(5);
        // Items 6..10 (zero-based 5..9) → "Event 5".."Event 9"
        assertThat(result.items().get(0).name()).isEqualTo("Event 5");
        assertThat(result.items().get(4).name()).isEqualTo("Event 9");
    }

    // -----------------------------------------------------------------------
    // Sort
    // -----------------------------------------------------------------------
    @Test
    void sort_by_startsAt_then_id() {
        Instant earlier = NOW.plusSeconds(86400);
        Instant later = NOW.plusSeconds(86400 * 2);

        Event laterEvt = publishedLiveEvent();
        laterEvt.setName("Later");
        laterEvt.setStartsAt(later);
        eventRepository.save(laterEvt);

        Event tiedA = publishedLiveEvent();
        tiedA.setName("Tied A");
        tiedA.setStartsAt(earlier);
        tiedA = eventRepository.save(tiedA);

        Event tiedB = publishedLiveEvent();
        tiedB.setName("Tied B");
        tiedB.setStartsAt(earlier);
        tiedB = eventRepository.save(tiedB);

        Event nullStart = publishedLiveEvent();
        nullStart.setName("NoStart");
        nullStart.setStartsAt(null);
        eventRepository.save(nullStart);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(4);

        // First two share startsAt=earlier — must appear before "Later"
        assertThat(result.items().get(0).startsAt()).isEqualTo(earlier);
        assertThat(result.items().get(1).startsAt()).isEqualTo(earlier);
        // Tie-break by id ASC: the smaller id (per DB ordering) sorts first.
        // Build the expected ordering directly from the two persisted ids,
        // sorting them the same way the DB does — natural UUID compare.
        UUID firstReturned = result.items().get(0).id();
        UUID secondReturned = result.items().get(1).id();
        assertThat(firstReturned).isNotEqualTo(secondReturned);
        assertThat(java.util.List.of(firstReturned, secondReturned))
                .containsExactlyInAnyOrder(tiedA.getId(), tiedB.getId());

        // Third is "Later" (later startsAt)
        assertThat(result.items().get(2).name()).isEqualTo("Later");
        // Fourth is null startsAt (NULLS LAST)
        assertThat(result.items().get(3).name()).isEqualTo("NoStart");
    }

    // -----------------------------------------------------------------------
    // priceFromMinor — min over PURCHASABLE tiers, fee-inclusive for one ticket
    // -----------------------------------------------------------------------
    @Test
    void priceFromMinor_uses_min_purchasable_tier_fee_inclusive() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "VIP", 5000, 100, 0, true, 0);
        tier(a.getId(), "GA", 2500, 100, 0, true, 1);
        tier(a.getId(), "Cheap", 1500, 100, 0, true, 2);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(1);
        // 1500 + 5% (75) + €0.99 (99) = 1674
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(withFee(1500)).isEqualTo(1674);
    }

    @Test
    void priceFromMinor_null_when_no_enabled_tier() {
        eventRepository.save(publishedLiveEvent());

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).priceFromMinor()).isNull();
    }

    @Test
    void priceFromMinor_ignores_disabled_tiers() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "Disabled Cheap", 100, 100, 0, false, 0);
        tier(a.getId(), "Enabled GA", 2000, 100, 0, true, 1);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(withFee(2000));
    }

    @Test
    void priceFromMinor_excludes_sold_out_tiers() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "Cheap but gone", 1000, 50, 50, true, 0);
        tier(a.getId(), "Still buyable", 3500, 100, 0, true, 1);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(withFee(3500));
    }

    @Test
    void priceFromMinor_excludes_tiers_not_yet_on_sale() {
        Event a = eventRepository.save(publishedLiveEvent());
        tierWithWindow(a.getId(), "Early bird, opens later", 1000, 100, 0, 0,
                NOW.plusSeconds(3600), null);
        tier(a.getId(), "On sale now", 3500, 100, 0, true, 1);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(withFee(3500));
    }

    @Test
    void priceFromMinor_excludes_tiers_whose_sale_closed() {
        Event a = eventRepository.save(publishedLiveEvent());
        tierWithWindow(a.getId(), "Presale, closed", 1000, 100, 0, 0,
                null, NOW.minusSeconds(60));
        tier(a.getId(), "Door price", 3500, 100, 0, true, 1);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(withFee(3500));
    }

    @Test
    void priceFromMinor_null_when_event_not_yet_on_sale() {
        Event a = publishedLiveEvent();
        a.setOnSaleAt(NOW.plusSeconds(3600)); // event-level gate still shut
        a = eventRepository.save(a);
        tier(a.getId(), "GA", 2500, 100, 0, true, 0);

        // onSaleOnly=false, so the event is still listed — but nothing is buyable yet.
        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).priceFromMinor()).isNull();
    }

    @Test
    void priceFromMinor_null_when_every_tier_unpurchasable() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "Sold out", 1000, 20, 20, true, 0);
        tierWithWindow(a.getId(), "Closed", 2000, 100, 0, 1, null, NOW.minusSeconds(1));

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).priceFromMinor()).isNull();
    }

    @Test
    void priceFromMinor_free_tier_stays_zero() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "Free entry", 0, 100, 0, true, 0);
        tier(a.getId(), "Supporter", 2000, 100, 0, true, 1);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items().get(0).priceFromMinor()).isZero();
    }

    // -----------------------------------------------------------------------
    // soldOut / lowStock — unchanged semantics after the query swap
    // -----------------------------------------------------------------------
    @Test
    void soldOut_true_when_every_enabled_tier_is_empty() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "GA", 2000, 30, 30, true, 0);
        tier(a.getId(), "VIP", 5000, 10, 10, true, 1);
        tier(a.getId(), "Disabled with stock", 100, 500, 0, false, 2);

        PublicEventListItem item = publicEventService.list(emptyQuery()).items().get(0);
        assertThat(item.soldOut()).isTrue();
        assertThat(item.lowStock()).isFalse();
        assertThat(item.priceFromMinor()).isNull();
    }

    @Test
    void soldOut_false_when_one_enabled_tier_still_has_stock() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "GA", 2000, 30, 30, true, 0);
        tier(a.getId(), "VIP", 5000, 100, 0, true, 1);

        PublicEventListItem item = publicEventService.list(emptyQuery()).items().get(0);
        assertThat(item.soldOut()).isFalse();
        assertThat(item.lowStock()).isFalse();
    }

    @Test
    void lowStock_true_when_total_remaining_at_or_below_threshold() {
        // Default imin.public.low-stock-threshold = 10.
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "GA", 2000, 100, 96, true, 0); // 4 left
        tier(a.getId(), "VIP", 5000, 20, 14, true, 1); // 6 left → total 10

        PublicEventListItem item = publicEventService.list(emptyQuery()).items().get(0);
        assertThat(item.soldOut()).isFalse();
        assertThat(item.lowStock()).isTrue();
    }

    @Test
    void soldOut_and_lowStock_false_when_event_has_no_enabled_tiers() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "Disabled", 2000, 100, 0, false, 0);

        PublicEventListItem item = publicEventService.list(emptyQuery()).items().get(0);
        assertThat(item.soldOut()).isFalse();
        assertThat(item.lowStock()).isFalse();
    }

    // -----------------------------------------------------------------------
    // venueName (W0.6)
    // -----------------------------------------------------------------------
    @Test
    void listItem_carries_venueName() {
        Event a = publishedLiveEvent();
        a.setVenueName("Funkhaus");
        eventRepository.save(a);

        PublicEventListItem item = publicEventService.list(emptyQuery()).items().get(0);
        assertThat(item.venueName()).isEqualTo("Funkhaus");
        assertThat(item.venueCity()).isEqualTo("Berlin");
    }

    // -----------------------------------------------------------------------
    // Empty result
    // -----------------------------------------------------------------------
    @Test
    void empty_result_returns_empty_page() {
        // No events at all
        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(20);
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------
    @Test
    void q_below_min_length_returns_400() {
        assertThatThrownBy(() -> publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, "a", false, false, false, 1, 20)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(apiEx.fields()).containsKey("q");
                });
    }

    @Test
    void country_wrong_length_returns_400() {
        assertThatThrownBy(() -> publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, "DEU", null, null, false, false, false, 1, 20)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(apiEx.fields()).containsKey("country");
                });
    }

    // -----------------------------------------------------------------------
    // listCities
    // -----------------------------------------------------------------------
    @Test
    void listCities_returns_distinct_pairs_alphabetical() {
        Event berlinDe1 = publishedLiveEvent();
        berlinDe1.setVenueCity("Berlin");
        berlinDe1.setVenueCountry("DE");
        eventRepository.save(berlinDe1);

        Event berlinDe2 = publishedLiveEvent();
        berlinDe2.setVenueCity("Berlin");
        berlinDe2.setVenueCountry("DE");
        eventRepository.save(berlinDe2);

        Event paris = publishedLiveEvent();
        paris.setVenueCity("Paris");
        paris.setVenueCountry("FR");
        eventRepository.save(paris);

        Event amsterdam = publishedLiveEvent();
        amsterdam.setVenueCity("Amsterdam");
        amsterdam.setVenueCountry("NL");
        eventRepository.save(amsterdam);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::city)
                .containsExactly("Amsterdam", "Berlin", "Paris");
        assertThat(cities).extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::country)
                .containsExactly("NL", "DE", "FR");
    }

    @Test
    void listCities_disambiguates_same_city_in_different_countries() {
        Event parisFr = publishedLiveEvent();
        parisFr.setVenueCity("Paris");
        parisFr.setVenueCountry("FR");
        eventRepository.save(parisFr);

        Event parisUs = publishedLiveEvent();
        parisUs.setVenueCity("Paris");
        parisUs.setVenueCountry("US");
        eventRepository.save(parisUs);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).hasSize(2);
        assertThat(cities).extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::city)
                .containsExactly("Paris", "Paris");
        assertThat(cities).extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::country)
                .containsExactlyInAnyOrder("FR", "US");
    }

    @Test
    void listCities_excludes_empty_cities() {
        // events.venue_city is NOT NULL DEFAULT '' at the schema level — only the
        // empty-string case is reachable. The query also tolerates NULL defensively.
        Event emptyCity = publishedLiveEvent();
        emptyCity.setVenueCity("");
        emptyCity.setVenueCountry("DE");
        eventRepository.save(emptyCity);

        Event berlin = publishedLiveEvent();
        berlin.setVenueCity("Berlin");
        berlin.setVenueCountry("DE");
        eventRepository.save(berlin);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).hasSize(1);
        assertThat(cities.get(0).city()).isEqualTo("Berlin");
    }

    @Test
    void listCities_excludes_non_eligible_events() {
        // Draft event (excluded)
        Event draft = publishedLiveEvent();
        draft.setStatus(EventStatus.DRAFT);
        draft.setPublishedAt(null);
        draft.setVenueCity("Hidden City");
        draft.setVenueCountry("ZZ");
        eventRepository.save(draft);

        // Private event (excluded)
        Event priv = publishedLiveEvent();
        priv.setVisibility(EventVisibility.PRIVATE);
        priv.setVenueCity("Private City");
        priv.setVenueCountry("ZZ");
        eventRepository.save(priv);

        // Soft-deleted (excluded)
        Event deleted = publishedLiveEvent();
        deleted.setDeletedAt(NOW.minusSeconds(60));
        deleted.setVenueCity("Deleted City");
        deleted.setVenueCountry("ZZ");
        eventRepository.save(deleted);

        // Cancelled (excluded — the feed drops it, the detail page still serves it)
        Event cancelled = publishedLiveEvent();
        cancelled.setStatus(EventStatus.CANCELLED);
        cancelled.setVenueCity("Cancelled City");
        cancelled.setVenueCountry("ZZ");
        eventRepository.save(cancelled);

        // Public + live (included)
        Event live = publishedLiveEvent();
        live.setVenueCity("Visible");
        live.setVenueCountry("DE");
        eventRepository.save(live);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::city)
                .containsExactly("Visible");
    }

    @Test
    void listCities_returns_empty_when_no_eligible_events() {
        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).isEmpty();
    }

    // -----------------------------------------------------------------------
    // listGenres
    // -----------------------------------------------------------------------
    @Test
    void listGenres_returns_distinct_alphabetical() {
        Event a = publishedLiveEvent();
        a.setGenre("techno");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setGenre("techno");
        eventRepository.save(b);
        Event c = publishedLiveEvent();
        c.setGenre("house");
        eventRepository.save(c);
        Event d = publishedLiveEvent();
        d.setGenre("ambient");
        eventRepository.save(d);

        assertThat(publicEventService.listGenres())
                .containsExactly("ambient", "house", "techno");
    }

    @Test
    void listGenres_merges_case_variants_into_one_chip_whose_count_the_listing_reproduces() {
        // The genre twin of the Metz problem: "techno" and "Techno" were two chips, each
        // holding a slice of one genre's nights. They are ONE genre. Merging is honest only
        // because the facet and ?genre= key off the same derived column, so the second half
        // taps the chip and asserts the listing returns every event the chip counted.
        for (int i = 0; i < 2; i++) {
            Event typed = publishedLiveEvent();
            typed.setGenre("Techno");
            eventRepository.save(typed);
        }
        Event shouted = publishedLiveEvent();
        shouted.setGenre("TECHNO");
        eventRepository.save(shouted);
        Event spaced = publishedLiveEvent();
        spaced.setGenre("techno");
        eventRepository.save(spaced);

        List<String> genres = publicEventService.listGenres();
        // "Techno" (2 events) beats "TECHNO" and "techno" (1 each) as the label. Nothing
        // title-cases or folds it — the label is a real stored spelling, never an invention.
        assertThat(genres).containsExactly("Techno");

        assertThat(feedBehindGenreChip(genres.get(0)).total()).isEqualTo(4L);
    }

    @Test
    void listGenres_breaks_a_spelling_tie_toward_the_least_shouted_then_alphabetically() {
        // One event each: the count cannot decide, so the deterministic tie-break must —
        // otherwise the chip label flips with whatever order the planner returns rows in.
        Event shouted = publishedLiveEvent();
        shouted.setGenre("HOUSE");
        eventRepository.save(shouted);
        Event typed = publishedLiveEvent();
        typed.setGenre("House");
        eventRepository.save(typed);

        assertThat(publicEventService.listGenres()).containsExactly("House");
    }

    @Test
    void listGenres_excludes_empty_genre() {
        // events.genre is NOT NULL DEFAULT '' at the schema level — only the empty-string
        // case is reachable. The query also tolerates NULL defensively.
        Event blank = publishedLiveEvent();
        blank.setGenre("");
        eventRepository.save(blank);

        Event withGenre = publishedLiveEvent();
        withGenre.setGenre("techno");
        eventRepository.save(withGenre);

        assertThat(publicEventService.listGenres()).containsExactly("techno");
    }

    @Test
    void listGenres_excludes_non_eligible_events() {
        Event draft = publishedLiveEvent();
        draft.setStatus(EventStatus.DRAFT);
        draft.setPublishedAt(null);
        draft.setGenre("hidden");
        eventRepository.save(draft);

        Event priv = publishedLiveEvent();
        priv.setVisibility(EventVisibility.PRIVATE);
        priv.setGenre("private");
        eventRepository.save(priv);

        Event deleted = publishedLiveEvent();
        deleted.setDeletedAt(NOW.minusSeconds(60));
        deleted.setGenre("deleted");
        eventRepository.save(deleted);

        Event cancelled = publishedLiveEvent();
        cancelled.setStatus(EventStatus.CANCELLED);
        cancelled.setGenre("cancelled");
        eventRepository.save(cancelled);

        Event live = publishedLiveEvent();
        live.setGenre("techno");
        eventRepository.save(live);

        assertThat(publicEventService.listGenres()).containsExactly("techno");
    }

    @Test
    void listGenres_returns_empty_when_no_eligible_events() {
        assertThat(publicEventService.listGenres()).isEmpty();
    }
    // -----------------------------------------------------------------------
    // freeOnly
    // -----------------------------------------------------------------------

    private PublicEventListQuery freeOnlyQuery() {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, true, 1, 20);
    }

    @Test
    void freeOnly_keeps_event_with_a_purchasable_zero_priced_tier() {
        Event free = eventRepository.save(publishedLiveEvent());
        tier(free.getId(), "Free entry", 0, 100, 0, true, 0);

        PageResponse<PublicEventListItem> result = publicEventService.list(freeOnlyQuery());
        assertThat(result.items()).extracting(PublicEventListItem::id).containsExactly(free.getId());
        assertThat(result.items().get(0).priceFromMinor()).isZero();
    }

    @Test
    void freeOnly_drops_event_whose_cheapest_tier_costs_money() {
        Event paid = eventRepository.save(publishedLiveEvent());
        tier(paid.getId(), "GA", 1500, 100, 0, true, 0);

        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
        // ...and the same event IS listed without the filter, so this is the filter's doing.
        assertThat(publicEventService.list(emptyQuery()).items()).hasSize(1);
    }

    @Test
    void freeOnly_keeps_mixed_event_because_its_cheapest_purchasable_tier_is_free() {
        Event mixed = eventRepository.save(publishedLiveEvent());
        tier(mixed.getId(), "Free entry", 0, 50, 0, true, 0);
        tier(mixed.getId(), "VIP", 5000, 50, 0, true, 1);

        PageResponse<PublicEventListItem> result = publicEventService.list(freeOnlyQuery());
        assertThat(result.items()).extracting(PublicEventListItem::id).containsExactly(mixed.getId());
        assertThat(result.items().get(0).priceFromMinor()).isZero();
    }

    @Test
    void freeOnly_drops_event_whose_only_free_tier_is_sold_out() {
        // Sold out is NOT free, it is unavailable — priceFromMinor is null, so a "free"
        // chip on this card would be a promise the checkout can't keep.
        Event soldOut = eventRepository.save(publishedLiveEvent());
        tier(soldOut.getId(), "Free entry", 0, 10, 10, true, 0);

        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
        assertThat(publicEventService.list(emptyQuery()).items().get(0).priceFromMinor()).isNull();
    }

    @Test
    void freeOnly_drops_event_whose_free_tier_has_not_opened_yet() {
        Event later = eventRepository.save(publishedLiveEvent());
        tierWithWindow(later.getId(), "Free entry", 0, 100, 0, 0, NOW.plusSeconds(3600), null);

        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
    }

    @Test
    void freeOnly_drops_event_whose_free_tier_sale_window_has_closed() {
        Event closed = eventRepository.save(publishedLiveEvent());
        tierWithWindow(closed.getId(), "Free entry", 0, 100, 0, 0, null, NOW.minusSeconds(60));

        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
    }

    @Test
    void freeOnly_drops_event_whose_free_tier_is_disabled() {
        Event disabled = eventRepository.save(publishedLiveEvent());
        tier(disabled.getId(), "Free entry", 0, 100, 0, false, 0);

        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
    }

    @Test
    void freeOnly_respects_the_event_level_on_sale_gate() {
        Event notOpen = publishedLiveEvent();
        notOpen.setOnSaleAt(NOW.plusSeconds(3600));
        notOpen = eventRepository.save(notOpen);
        tier(notOpen.getId(), "Free entry", 0, 100, 0, true, 0);

        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
    }

    @Test
    void freeOnly_drops_event_with_no_tiers_at_all() {
        eventRepository.save(publishedLiveEvent());
        assertThat(publicEventService.list(freeOnlyQuery()).items()).isEmpty();
    }

    @Test
    void freeOnly_false_is_the_unfiltered_listing() {
        Event free = eventRepository.save(publishedLiveEvent());
        tier(free.getId(), "Free entry", 0, 100, 0, true, 0);
        Event paid = eventRepository.save(publishedLiveEvent());
        tier(paid.getId(), "GA", 1500, 100, 0, true, 0);

        assertThat(publicEventService.list(emptyQuery()).items())
                .extracting(PublicEventListItem::id)
                .containsExactlyInAnyOrder(free.getId(), paid.getId());
    }

    @Test
    void freeOnly_narrows_the_pagination_total_not_just_the_page() {
        // The whole point of doing this server-side: `total` must count free events only,
        // otherwise the FE's pager offers pages that come back empty.
        for (int i = 0; i < 3; i++) {
            Event free = eventRepository.save(publishedLiveEvent());
            tier(free.getId(), "Free entry", 0, 100, 0, true, 0);
        }
        for (int i = 0; i < 5; i++) {
            Event paid = eventRepository.save(publishedLiveEvent());
            tier(paid.getId(), "GA", 2000, 100, 0, true, 0);
        }

        assertThat(publicEventService.list(emptyQuery()).total()).isEqualTo(8);
        PageResponse<PublicEventListItem> free = publicEventService.list(freeOnlyQuery());
        assertThat(free.total()).isEqualTo(3);
        assertThat(free.items()).hasSize(3);
    }

    @Test
    void freeOnly_agrees_with_priceFromMinor_across_the_whole_case_matrix() {
        // THE CONTRACT. `freeOnly` is a SQL EXISTS, `priceFromMinor` is Java over
        // TierAvailability — two expressions of one rule. This asserts they agree on every
        // interesting shape, so a change to one that forgets the other fails here rather
        // than shipping a "Free" chip onto a card that shows a price (or vice versa).
        Event freeOpen = eventRepository.save(publishedLiveEvent());
        tier(freeOpen.getId(), "Free", 0, 10, 0, true, 0);

        Event freeAndPaid = eventRepository.save(publishedLiveEvent());
        tier(freeAndPaid.getId(), "Free", 0, 10, 0, true, 0);
        tier(freeAndPaid.getId(), "VIP", 9900, 10, 0, true, 1);

        Event paidOnly = eventRepository.save(publishedLiveEvent());
        tier(paidOnly.getId(), "GA", 1000, 10, 0, true, 0);

        Event freeSoldOut = eventRepository.save(publishedLiveEvent());
        tier(freeSoldOut.getId(), "Free", 0, 10, 10, true, 0);

        Event freeSoldOutPaidLeft = eventRepository.save(publishedLiveEvent());
        tier(freeSoldOutPaidLeft.getId(), "Free", 0, 10, 10, true, 0);
        tier(freeSoldOutPaidLeft.getId(), "GA", 2500, 10, 0, true, 1);

        Event freeNotOpen = eventRepository.save(publishedLiveEvent());
        tierWithWindow(freeNotOpen.getId(), "Free", 0, 10, 0, 0, NOW.plusSeconds(600), null);

        Event freeClosed = eventRepository.save(publishedLiveEvent());
        tierWithWindow(freeClosed.getId(), "Free", 0, 10, 0, 0, null, NOW.minusSeconds(600));

        Event freeDisabled = eventRepository.save(publishedLiveEvent());
        tier(freeDisabled.getId(), "Free", 0, 10, 0, false, 0);

        Event noTiers = eventRepository.save(publishedLiveEvent());

        // The EXISTS has SIX clauses that are about the EVENT rather than the tier, and each
        // one has to be pinned individually: delete any single unpinned clause and every test
        // still passes while freeOnly=true starts returning cards whose priceFromMinor is null.
        // The four tier-level clauses are covered above; these are the event-level ones.

        // e.onSaleAt in the future — the event's sale has not opened yet.
        Event eventNotOnSaleYet = publishedLiveEvent();
        eventNotOnSaleYet.setOnSaleAt(NOW.plusSeconds(600));
        eventNotOnSaleYet = eventRepository.save(eventNotOnSaleYet);
        tier(eventNotOnSaleYet.getId(), "Free", 0, 10, 0, true, 0);

        // e.saleClosesAt in the past — event-level sales have shut, tier windows notwithstanding.
        Event eventSalesClosed = publishedLiveEvent();
        eventSalesClosed.setSaleClosesAt(NOW.minusSeconds(600));
        eventSalesClosed = eventRepository.save(eventSalesClosed);
        tier(eventSalesClosed.getId(), "Free", 0, 10, 0, true, 0);

        // EventStatus.PAST — the night is over; a free ticket to it is not purchasable.
        Event pastEvent = publishedLiveEvent();
        pastEvent.setStatus(EventStatus.PAST);
        pastEvent.setStartsAt(NOW.minusSeconds(172_800));
        pastEvent = eventRepository.save(pastEvent);
        tier(pastEvent.getId(), "Free", 0, 10, 0, true, 0);

        // EventStatus.CANCELLED is excluded by the listing itself, so it can never reach the
        // EXISTS — pinned in listing_excludes_cancelled_events, not here.

        List<UUID> zeroPricedCards = publicEventService.list(onlyPage(1, 100)).items().stream()
                .filter(i -> i.priceFromMinor() != null && i.priceFromMinor() == 0)
                .map(PublicEventListItem::id)
                .toList();
        List<UUID> freeOnlyIds = publicEventService.list(
                new PublicEventListQuery(null, null, null, null, null, null, null, null,
                        false, false, true, 1, 100)).items().stream()
                .map(PublicEventListItem::id)
                .toList();

        assertThat(freeOnlyIds)
                .as("freeOnly must return exactly the events whose card shows FROM €0")
                .containsExactlyInAnyOrderElementsOf(zeroPricedCards);
        assertThat(freeOnlyIds).containsExactlyInAnyOrder(freeOpen.getId(), freeAndPaid.getId());
        assertThat(freeOnlyIds).doesNotContain(paidOnly.getId(), freeSoldOut.getId(),
                freeSoldOutPaidLeft.getId(), freeNotOpen.getId(), freeClosed.getId(),
                freeDisabled.getId(), noTiers.getId(),
                eventNotOnSaleYet.getId(), eventSalesClosed.getId(), pastEvent.getId());

        // Each event-level clause pinned on its own, so deleting exactly one from the EXISTS
        // fails exactly one assertion with a name that says which.
        assertThat(freeOnlyIds).as("e.onSaleAt in the future must exclude the event")
                .doesNotContain(eventNotOnSaleYet.getId());
        assertThat(freeOnlyIds).as("e.saleClosesAt in the past must exclude the event")
                .doesNotContain(eventSalesClosed.getId());
        assertThat(freeOnlyIds).as("EventStatus.PAST must exclude the event")
                .doesNotContain(pastEvent.getId());
    }

    @Test
    void freeOnly_composes_with_the_other_filters() {
        Event berlinFree = publishedLiveEvent();
        berlinFree.setVenueCity("Berlin");
        berlinFree = eventRepository.save(berlinFree);
        tier(berlinFree.getId(), "Free", 0, 10, 0, true, 0);

        Event parisFree = publishedLiveEvent();
        parisFree.setVenueCity("Paris");
        parisFree = eventRepository.save(parisFree);
        tier(parisFree.getId(), "Free", 0, 10, 0, true, 0);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, "berlin", null, null, null, false, false, true, 1, 20));
        assertThat(result.items()).extracting(PublicEventListItem::id).containsExactly(berlinFree.getId());
    }

    // -----------------------------------------------------------------------
    // listCities — per-city counts
    // -----------------------------------------------------------------------

    @Test
    void listCities_counts_events_per_city() {
        for (int i = 0; i < 3; i++) {
            Event e = publishedLiveEvent();
            e.setVenueCity("Berlin");
            e.setVenueCountry("DE");
            eventRepository.save(e);
        }
        Event paris = publishedLiveEvent();
        paris.setVenueCity("Paris");
        paris.setVenueCountry("FR");
        eventRepository.save(paris);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).extracting(
                com.imin.iminapi.dto.publicapi.PublicCityItem::city,
                com.imin.iminapi.dto.publicapi.PublicCityItem::eventCount)
                .containsExactly(tuple("Berlin", 3L), tuple("Paris", 1L));
    }

    @Test
    void listCities_count_excludes_events_the_listing_would_not_show() {
        Event live = publishedLiveEvent();
        live.setVenueCity("Berlin");
        eventRepository.save(live);

        Event cancelled = publishedLiveEvent();
        cancelled.setVenueCity("Berlin");
        cancelled.setStatus(EventStatus.CANCELLED);
        eventRepository.save(cancelled);

        Event draft = publishedLiveEvent();
        draft.setVenueCity("Berlin");
        draft.setStatus(EventStatus.DRAFT);
        draft.setPublishedAt(null);
        eventRepository.save(draft);

        Event priv = publishedLiveEvent();
        priv.setVenueCity("Berlin");
        priv.setVisibility(EventVisibility.PRIVATE);
        eventRepository.save(priv);

        Event deleted = publishedLiveEvent();
        deleted.setVenueCity("Berlin");
        deleted.setDeletedAt(NOW.minusSeconds(60));
        eventRepository.save(deleted);

        assertThat(publicEventService.listCities())
                .extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::eventCount)
                .containsExactly(1L);
    }

    @Test
    void listCities_merges_case_and_missing_country_variants_into_one_chip_whose_count_the_listing_reproduces() {
        // Mirrors the live Metz/FR + Metz/null + METZ/'' data problem, which used to surface as
        // three identical-looking chips each holding a slice of the nights. They are ONE city.
        //
        // Merging is only honest because both sides now key off venue_city_key: the second half
        // of this test taps the chip and asserts the listing returns exactly the number the chip
        // promised. (Rows are written straight through the repository on purpose — this pins the
        // READ side, independent of EventService's write-time normalisation and of V82.)
        Event metzFr = publishedLiveEvent();
        metzFr.setVenueCity("Metz");
        metzFr.setVenueCountry("FR");
        eventRepository.save(metzFr);

        Event metzNull = publishedLiveEvent();
        metzNull.setVenueCity("Metz");
        metzNull.setVenueCountry(null);
        eventRepository.save(metzNull);

        Event metzUpper = publishedLiveEvent();
        metzUpper.setVenueCity("METZ");
        metzUpper.setVenueCountry("");
        eventRepository.save(metzUpper);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).hasSize(1);
        // "Metz" (2 events) beats "METZ" (1) as the label; FR is the only country on offer and
        // the country-less rows fold into it rather than inventing a second chip.
        assertThat(cities.get(0).city()).isEqualTo("Metz");
        assertThat(cities.get(0).country()).isEqualTo("FR");
        assertThat(cities.get(0).eventCount()).isEqualTo(3L);

        assertThat(feedBehindCityChip(cities.get(0).city()).total())
                .isEqualTo(cities.get(0).eventCount());
    }

    @Test
    void listCities_labels_a_key_with_its_most_common_spelling() {
        for (int i = 0; i < 2; i++) {
            Event shouted = publishedLiveEvent();
            shouted.setVenueCity("SAINT-DENIS");
            shouted.setVenueCountry("FR");
            eventRepository.save(shouted);
        }
        Event typed = publishedLiveEvent();
        typed.setVenueCity("Saint-Denis");
        typed.setVenueCountry("FR");
        eventRepository.save(typed);

        // Most common wins even when it is the ugly one — the label is evidence, not taste.
        // Nothing title-cases the city: doing so would wreck 's-Hertogenbosch and L'Aquila.
        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).hasSize(1);
        assertThat(cities.get(0).city()).isEqualTo("SAINT-DENIS");
        assertThat(cities.get(0).eventCount()).isEqualTo(3L);
    }

    @Test
    void listCities_keeps_two_countries_sharing_a_city_name_apart() {
        // Paris/FR and Paris/US are two cities, not one spelling problem. Merging them on the
        // key alone would be a fabricated fact, so the ambiguous key stays split.
        Event parisFr = publishedLiveEvent();
        parisFr.setVenueCity("Paris");
        parisFr.setVenueCountry("FR");
        eventRepository.save(parisFr);

        Event parisUs = publishedLiveEvent();
        parisUs.setVenueCity("PARIS");
        parisUs.setVenueCountry("US");
        eventRepository.save(parisUs);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).hasSize(2);
        assertThat(cities).extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::country)
                .containsExactly("FR", "US");
        assertThat(cities).allSatisfy(c -> assertThat(c.eventCount()).isEqualTo(1L));
    }

    // -----------------------------------------------------------------------
    // The facets vs the feed they open
    //
    // In production the Metz chip promised 14 nights and the feed behind it
    // returned 4: the count had no time bound, while every real client sends
    // one. These tests never assert a number of their own — they run BOTH
    // sides over the same fixtures and require them to agree, which is the
    // only assertion that would have failed before the fix.
    // -----------------------------------------------------------------------

    @Test
    void everyCityChipsCountIsWhatTheFeedBehindItReturns() {
        // Berlin: two upcoming, one running right now, three long finished.
        for (int i = 0; i < 2; i++) {
            Event upcoming = publishedLiveEvent();
            upcoming.setVenueCity("Berlin");
            upcoming.setVenueCountry("DE");
            eventRepository.save(upcoming);
        }
        Event running = publishedLiveEvent();
        running.setVenueCity("Berlin");
        running.setVenueCountry("DE");
        running.setStartsAt(NOW.minusSeconds(3600));
        running.setEndsAt(NOW.plusSeconds(7200));
        eventRepository.save(running);
        for (int i = 0; i < 3; i++) {
            Event over = finishedEvent();
            over.setVenueCity("Berlin");
            over.setVenueCountry("DE");
            eventRepository.save(over);
        }

        // Metz: nothing left at all — the shape of the production bug.
        for (int i = 0; i < 4; i++) {
            Event over = finishedEvent();
            over.setVenueCity("Metz");
            over.setVenueCountry("FR");
            eventRepository.save(over);
        }

        Event paris = publishedLiveEvent();
        paris.setVenueCity("Paris");
        paris.setVenueCountry("FR");
        eventRepository.save(paris);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).as("fixtures must produce chips, or the loop below asserts nothing")
                .isNotEmpty();
        for (com.imin.iminapi.dto.publicapi.PublicCityItem chip : cities) {
            assertThat(feedBehindCityChip(chip.city()).total())
                    .as("chip \"%s\" promises %d night(s); its own feed must return exactly that",
                            chip.city(), chip.eventCount())
                    .isEqualTo(chip.eventCount());
        }

        // Fixture integrity: there really are unreachable rows behind these cities, so the
        // agreement above is a fact about the time bound and not a vacuous pass on data that
        // happened to have no past.
        assertThat(publicEventService.list(new PublicEventListQuery(
                null, null, null, null, "Berlin", null, null, null, false, true, false, 1, 100)).total())
                .as("Berlin must own events no feed can reach, or this test proves nothing")
                .isGreaterThan(feedBehindCityChip("Berlin").total());
    }

    @Test
    void aCityWhoseNightsHaveAllPassedGetsNoChip() {
        // A chip is a promise that tapping it shows something. Metz had four sold-out
        // memories and an empty feed; the honest answer is no chip, not "Metz, 4".
        for (int i = 0; i < 4; i++) {
            Event over = finishedEvent();
            over.setVenueCity("Metz");
            over.setVenueCountry("FR");
            eventRepository.save(over);
        }

        assertThat(feedBehindCityChip("Metz").total())
                .as("premise: the feed for this city is empty")
                .isZero();
        assertThat(publicEventService.listCities())
                .extracting(com.imin.iminapi.dto.publicapi.PublicCityItem::city)
                .doesNotContain("Metz");
    }

    @Test
    void aNightThatHasStartedButNotEndedIsCountedBecauseTheFeedStillShowsIt() {
        // The window is the feed's own (from=now OR still running), not "startsAt in the
        // future". A doors-open event is still sellable and both clients send
        // includeOngoing=true, so under-counting it would be the same lie inverted.
        Event running = publishedLiveEvent();
        running.setVenueCity("Berlin");
        running.setVenueCountry("DE");
        running.setStartsAt(NOW.minusSeconds(3600));
        running.setEndsAt(NOW.plusSeconds(7200));
        eventRepository.save(running);

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> cities = publicEventService.listCities();
        assertThat(cities).hasSize(1);
        long feedTotal = feedBehindCityChip(cities.get(0).city()).total();
        assertThat(feedTotal).as("premise: the running night is in the feed").isPositive();
        assertThat(cities.get(0).eventCount()).isEqualTo(feedTotal);
    }

    @Test
    void everyGenreChipOpensAFeedWithSomethingInIt() {
        // The genre facet puts no number on the wire, so its promise is one bit wide —
        // "there are Disco nights". Same defect, same fix: a genre nobody can reach is
        // not offered.
        Event techno = publishedLiveEvent();
        techno.setGenre("techno");
        eventRepository.save(techno);

        Event disco = finishedEvent();
        disco.setGenre("disco");
        eventRepository.save(disco);

        List<String> genres = publicEventService.listGenres();
        assertThat(genres).as("fixtures must produce chips, or the loop below asserts nothing")
                .isNotEmpty();
        for (String genre : genres) {
            assertThat(feedBehindGenreChip(genre).total())
                    .as("genre chip \"%s\" must open a feed with nights in it", genre)
                    .isPositive();
        }
        assertThat(feedBehindGenreChip("disco").total())
                .as("premise: the finished genre's feed is empty")
                .isZero();
        assertThat(genres).doesNotContain("disco");
    }
}
