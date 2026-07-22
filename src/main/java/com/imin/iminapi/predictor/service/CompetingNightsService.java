package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The "competing nights on imin" signal (predictor task scope A) — how crowded the subject
 * event's night is on the PLATFORM. Real internal data only: same-city published events within
 * ±{@code imin.predictor.competing-nights-window-days} of the date. External event calendars are
 * deliberately NOT integrated (a paid provider; flagged as an open decision).
 *
 * <p>Signal is a plain count + total advertised capacity + a genre-overlap flag; it seasons the
 * LLM prompt/narration as context and never enters the deterministic pacing arithmetic.
 */
@Service
public class CompetingNightsService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final PredictorProperties props;

    public CompetingNightsService(EventRepository events, TicketTierRepository tiers,
                                  PredictorProperties props) {
        this.events = events;
        this.tiers = tiers;
        this.props = props;
    }

    /** {@code count} same-city platform events near the date, their {@code totalCapacity}, and
     *  whether any shares the subject's genre. */
    public record CompetingNights(int count, int totalCapacity, boolean genreOverlap) {
        public static final CompetingNights NONE = new CompetingNights(0, 0, false);
    }

    /** Compute the signal for an event; {@link CompetingNights#NONE} when the event has no city/date. */
    @Transactional(readOnly = true)
    public CompetingNights compute(Event e) {
        String city = e.getVenueCity();
        Instant startsAt = e.getStartsAt();
        if (city == null || city.isBlank() || startsAt == null) return CompetingNights.NONE;

        long window = props.getCompetingNightsWindowDays();
        Instant from = startsAt.minus(window, ChronoUnit.DAYS);
        Instant to = startsAt.plus(window, ChronoUnit.DAYS);
        String genre = e.getGenre() == null ? "" : e.getGenre();

        int count = 0;
        int totalCapacity = 0;
        boolean overlap = false;
        for (Object[] row : events.findCompetingNights(e.getId(), city, from, to)) {
            count++;
            totalCapacity += tiers.sumQuantityByEventId((UUID) row[0]);
            if (!genre.isBlank() && genre.equalsIgnoreCase((String) row[1])) overlap = true;
        }
        return new CompetingNights(count, totalCapacity, overlap);
    }
}
