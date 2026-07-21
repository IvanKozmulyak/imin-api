package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.service.ComparableCorpusService;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot;
import com.imin.iminapi.predictor.service.PredictionInputSnapshotService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Snapshot determinism (task 86cav4741): the SHA-256 of the canonical snapshot IS the cache
 * key, so byte-identical inputs must hash identically and any material edit must move it.
 */
class PredictionInputSnapshotServiceTest {

    private final Instant now = Instant.parse("2026-06-01T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final PromoCodeRepository promos = mock(PromoCodeRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final ComparableCorpusService corpus = mock(ComparableCorpusService.class);

    private final PredictionInputSnapshotService sut =
            new PredictionInputSnapshotService(tiers, promos, orgs, events, corpus, clock);

    private final UUID eventId = UUID.fromString("00000000-0000-0000-0000-00000000e0e0");
    private final UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");

    private Event draft() {
        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        e.setName("Test Night");
        e.setGenre("techno");
        e.setVenueCity("Amsterdam");
        e.setVenueCountry("NL");
        e.setTimezone("Europe/Amsterdam");
        e.setStartsAt(Instant.parse("2026-07-18T20:00:00Z")); // Saturday
        return e;
    }

    private TicketTier tier(String name, int priceMinor, int quantity) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName(name);
        t.setPriceMinor(priceMinor);
        t.setQuantity(quantity);
        return t;
    }

    private void stub(int price) {
        when(tiers.sumQuantityByEventId(eventId)).thenReturn(250);
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId))
                .thenReturn(List.of(tier("Early Bird", price, 50), tier("Standard", 2400, 200)));
        when(promos.findByEventId(eventId)).thenReturn(List.of());
        when(orgs.findById(orgId)).thenReturn(Optional.empty());
        when(events.countPublished(orgId)).thenReturn(4L);
        when(corpus.retrieve(any(), any(), any(), any(), any(), any())).thenReturn(
                new ComparableCorpusService.ComparableCorpus(RelaxationLevel.CITY_TO_COUNTRY,
                        11, 2, 9, List.of(), null));
    }

    @Test
    void sameDraftSameHash() {
        stub(1500);
        String h1 = sut.build(draft()).sha256();
        String h2 = sut.build(draft()).sha256();
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    void changedTierPriceChangesHash() {
        stub(1500);
        String before = sut.build(draft()).sha256();
        stub(1800); // one tier price moves — a material scoring input
        String after = sut.build(draft()).sha256();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void changedComparableCorpusChangesHash() {
        stub(1500);
        String before = sut.build(draft()).sha256();
        when(corpus.retrieve(any(), any(), any(), any(), any(), any())).thenReturn(
                new ComparableCorpusService.ComparableCorpus(RelaxationLevel.CITY_TO_COUNTRY,
                        12, 2, 10, List.of(), null)); // a new finalized outcome arrived
        String after = sut.build(draft()).sha256();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void snapshotCarriesDraftAttributesAndCalendarSignals() {
        stub(1500);
        PredictionInputSnapshot snap = sut.build(draft());
        assertThat(snap.snapshotVersion()).isEqualTo(PredictionInputSnapshot.SNAPSHOT_VERSION);
        assertThat(snap.city()).isEqualTo("Amsterdam");
        assertThat(snap.country()).isEqualTo("NL");
        assertThat(snap.genreFamily()).isEqualTo("techno");
        assertThat(snap.capacity()).isEqualTo(250);
        assertThat(snap.capacityBand()).isEqualTo("B101_300");
        assertThat(snap.dayOfWeek()).isEqualTo(6); // Saturday in Europe/Amsterdam
        assertThat(snap.season()).isEqualTo("SUMMER");
        assertThat(snap.leadTimeDays()).isEqualTo(47); // 2026-06-01 -> 2026-07-18
        assertThat(snap.holidayTableCovers()).isTrue(); // NL 2026 is in the static table
        assertThat(snap.priorEventCount()).isEqualTo(4); // draft: raw published count
        assertThat(snap.comparables().densityTotal()).isEqualTo(11);
        assertThat(snap.tiers()).hasSize(2);
    }

    @Test
    void holidayNearEventIsCaptured() {
        stub(1500);
        Event e = draft();
        e.setVenueCountry("FR");
        e.setVenueCity("Paris");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(Instant.parse("2026-07-14T20:00:00Z")); // Fête nationale
        PredictionInputSnapshot snap = sut.build(e);
        assertThat(snap.holidayTableCovers()).isTrue();
        assertThat(snap.holidaysNearEvent())
                .anyMatch(h -> h.name().contains("nationale") && h.dateIso().equals("2026-07-14"));
    }
}
