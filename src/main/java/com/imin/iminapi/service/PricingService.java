package com.imin.iminapi.service;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.PricingBasisDto;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.util.EventNormalization;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Suggested ticket price range for a concept: medians of the listed (not sold) tier prices of other
 * public imin events in the same genre, org country and currency, starting within a year back and half a
 * year ahead; fewer than {@link ComparablePrices#MIN_EVENTS} such events gives the default range.
 */
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

    private static final int DAYS_BACK = 365;
    private static final int DAYS_AHEAD = 180;
    private static final int MAX_EVENTS = 500;

    private final OrganizationRepository orgs;
    private final TicketTierRepository tiers;
    private final Clock clock;

    public PricingRecommendation recommend(UUID orgId, String genre) {
        String trimmed = genre == null ? "" : genre.trim();
        if (trimmed.isEmpty()) {
            return new PricingRecommendation(BASE_DEFAULT[0], BASE_DEFAULT[1],
                    new PricingBasisDto(PricingBasisDto.DEFAULT, null, null, null));
        }
        Organization org = orgs.findById(orgId).orElse(null);
        String country = org == null ? null : org.getCountry();
        String currency = org == null ? null : org.getCurrency();
        if (country == null || country.isBlank() || currency == null || currency.isBlank()) {
            return genreDefault(trimmed);
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Instant from = today.minusDays(DAYS_BACK).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.plusDays(DAYS_AHEAD).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Object[]> rows = tiers.comparablePriceRows(EventNormalization.genreKey(trimmed),
                country.toUpperCase(Locale.ROOT), currency.toUpperCase(Locale.ROOT), from, to,
                PageRequest.of(0, MAX_EVENTS));
        List<long[]> minMax = rows.stream()
                .map(r -> new long[]{((Number) r[1]).longValue(), ((Number) r[2]).longValue()})
                .toList();
        Optional<long[]> medians = ComparablePrices.medians(minMax);
        if (medians.isEmpty()) return genreDefault(trimmed);
        return new PricingRecommendation(BigDecimal.valueOf(medians.get()[0], 2), BigDecimal.valueOf(medians.get()[1], 2),
                new PricingBasisDto(PricingBasisDto.COMPARABLES, minMax.size(), trimmed, country.toUpperCase(Locale.ROOT)));
    }

    private PricingRecommendation genreDefault(String trimmedGenre) {
        BigDecimal[] range = GENRE_DEFAULTS.getOrDefault(trimmedGenre.toLowerCase(Locale.ROOT), BASE_DEFAULT);
        return new PricingRecommendation(range[0], range[1],
                new PricingBasisDto(PricingBasisDto.DEFAULT, null, trimmedGenre, null));
    }
}
