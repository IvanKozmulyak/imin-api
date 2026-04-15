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
import java.util.Objects;

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
        List<BigDecimal> minPrices = comparables.stream()
                .map(GeneratedEvent::getSuggestedMinPrice)
                .filter(Objects::nonNull)
                .toList();
        List<BigDecimal> maxPrices = comparables.stream()
                .map(GeneratedEvent::getSuggestedMaxPrice)
                .filter(Objects::nonNull)
                .toList();

        if (minPrices.isEmpty() || maxPrices.isEmpty()) {
            return genreDefault(genre);
        }

        BigDecimal avgMin = minPrices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(minPrices.size()), 2, RoundingMode.HALF_UP);

        BigDecimal avgMax = maxPrices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(maxPrices.size()), 2, RoundingMode.HALF_UP);

        String notes = "Based on %d comparable %s event(s).".formatted(comparables.size(), genre);

        return new PricingRecommendation(avgMin, avgMax, notes);
    }

    private PricingRecommendation genreDefault(String genre) {
        BigDecimal[] range = GENRE_DEFAULTS.getOrDefault(genre.toLowerCase(), BASE_DEFAULT);
        String notes = "Genre default pricing — no comparable events found.";
        return new PricingRecommendation(range[0], range[1], notes);
    }
}
