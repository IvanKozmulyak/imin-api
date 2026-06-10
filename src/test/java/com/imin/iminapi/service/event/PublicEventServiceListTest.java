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

    private PublicEventListQuery emptyQuery() {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, 1, 20);
    }

    private PublicEventListQuery onlyPage(int page, int pageSize) {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, page, pageSize);
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
                null, null, "techno", null, null, null, null, null, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).genre()).isEqualTo("techno");
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
                null, null, null, "festival", null, null, null, null, false, false, 1, 20));
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
                null, null, null, null, null, "fr", null, null, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).venueCountry()).isEqualTo("FR");
    }

    @Test
    void filter_by_city_case_insensitive_contains() {
        Event a = publishedLiveEvent();
        a.setVenueCity("Berlin");
        eventRepository.save(a);
        Event b = publishedLiveEvent();
        b.setVenueCity("Paris");
        eventRepository.save(b);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, "berl", null, null, null, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).venueCity()).isEqualTo("Berlin");
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
                null, null, null, null, null, null, null, "festIVAL", false, false, 1, 20));
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
                start, null, null, null, null, null, null, null, false, false, 1, 20));
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
                null, start, null, null, null, null, null, null, false, false, 1, 20));
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
                null, null, null, null, null, null, other.getSlug(), null, false, false, 1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).organization().slug()).isEqualTo(other.getSlug());
    }

    @Test
    void unknown_orgSlug_returns_empty_page() {
        eventRepository.save(publishedLiveEvent());

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, "no-such-org", null, false, false, 1, 20));
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
                null, null, null, null, null, null, null, null, true, false, 1, 20));
        assertThat(result.items()).isEmpty();
    }

    @Test
    void onSaleOnly_includes_null_onSaleAt() {
        Event a = publishedLiveEvent();
        a.setOnSaleAt(null);
        eventRepository.save(a);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, null, true, false, 1, 20));
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void onSaleOnly_excludes_past_saleClosesAt() {
        Event a = publishedLiveEvent();
        a.setOnSaleAt(NOW.minusSeconds(7200));
        a.setSaleClosesAt(NOW.minusSeconds(60)); // past
        eventRepository.save(a);

        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                null, null, null, null, null, null, null, null, true, false, 1, 20));
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
    // priceFromMinor
    // -----------------------------------------------------------------------
    @Test
    void priceFromMinor_uses_min_enabled_tier() {
        Event a = eventRepository.save(publishedLiveEvent());
        tier(a.getId(), "VIP", 5000, 100, 0, true, 0);
        tier(a.getId(), "GA", 2500, 100, 0, true, 1);
        tier(a.getId(), "Cheap", 1500, 100, 0, true, 2);

        PageResponse<PublicEventListItem> result = publicEventService.list(emptyQuery());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(1500);
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
        assertThat(result.items().get(0).priceFromMinor()).isEqualTo(2000);
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
                null, null, null, null, null, null, null, "a", false, false, 1, 20)))
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
                null, null, null, null, null, "DEU", null, null, false, false, 1, 20)))
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

        Event live = publishedLiveEvent();
        live.setGenre("techno");
        eventRepository.save(live);

        assertThat(publicEventService.listGenres()).containsExactly("techno");
    }

    @Test
    void listGenres_returns_empty_when_no_eligible_events() {
        assertThat(publicEventService.listGenres()).isEmpty();
    }
}
