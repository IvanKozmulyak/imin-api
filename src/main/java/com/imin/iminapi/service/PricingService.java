package com.imin.iminapi.service;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.repository.GeneratedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PricingService {

    private static final Map<String, BigDecimal[]> GENRE_DEFAULTS = Map.of(
            "techno",       new BigDecimal[]{new BigDecimal("15"), new BigDecimal("25")},
            "house",        new BigDecimal[]{new BigDecimal("15"), new BigDecimal("25")},
            "electronic",   new BigDecimal[]{new BigDecimal("15"), new BigDecimal("25")},
            "hip-hop",      new BigDecimal[]{new BigDecimal("20"), new BigDecimal("35")},
            "jazz",         new BigDecimal[]{new BigDecimal("20"), new BigDecimal("40")},
            "classical",    new BigDecimal[]{new BigDecimal("30"), new BigDecimal("60")},
            "pop",          new BigDecimal[]{new BigDecimal("25"), new BigDecimal("50")}
    );
    private static final BigDecimal[] BASE_DEFAULT = {new BigDecimal("15"), new BigDecimal("30")};

    private static final java.util.Set<String> NIGHTLIFE_GENRES =
            java.util.Set.of("techno", "house", "electronic", "hip-hop", "drum-and-bass");

    private final GeneratedEventRepository repository;

    public PricingRecommendation recommend(String genre, String city, LocalDate date) {
        List<GeneratedEvent> comparables = repository.findComparableEvents(
                genre, city, date.minusDays(30), date.plusDays(30));

        if (!comparables.isEmpty()) {
            return fromComparables(genre, comparables);
        }
        return genreDefault(genre);
    }

    private PricingRecommendation fromComparables(String genre, List<GeneratedEvent> comparables) {
        BigDecimal avgMin = comparables.stream()
                .map(GeneratedEvent::getSuggestedMinPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(comparables.size()), 2, RoundingMode.HALF_UP);

        BigDecimal avgMax = comparables.stream()
                .map(GeneratedEvent::getSuggestedMaxPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(comparables.size()), 2, RoundingMode.HALF_UP);

        String dow = mostCommonDow(comparables);
        String notes = "Based on %d comparable %s event(s).".formatted(comparables.size(), genre);

        return new PricingRecommendation(avgMin, avgMax, dow, notes);
    }

    private PricingRecommendation genreDefault(String genre) {
        BigDecimal[] range = GENRE_DEFAULTS.getOrDefault(genre.toLowerCase(), BASE_DEFAULT);
        String dow = NIGHTLIFE_GENRES.contains(genre.toLowerCase()) ? "SATURDAY" : "FRIDAY";
        String notes = "Genre default pricing — no comparable events found.";
        return new PricingRecommendation(range[0], range[1], dow, notes);
    }

    private String mostCommonDow(List<GeneratedEvent> events) {
        return events.stream()
                .map(e -> e.getEventDate().getDayOfWeek().name())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("FRIDAY");
    }
}
