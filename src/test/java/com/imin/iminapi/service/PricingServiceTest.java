package com.imin.iminapi.service;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.repository.GeneratedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private GeneratedEventRepository repository;

    @InjectMocks
    private PricingService pricingService;

    @Test
    void recommend_withComparableEvents_averagesPrices() {
        GeneratedEvent e1 = new GeneratedEvent();
        e1.setSuggestedMinPrice(new BigDecimal("10"));
        e1.setSuggestedMaxPrice(new BigDecimal("20"));
        e1.setEventDate(LocalDate.of(2026, 6, 13));

        GeneratedEvent e2 = new GeneratedEvent();
        e2.setSuggestedMinPrice(new BigDecimal("20"));
        e2.setSuggestedMaxPrice(new BigDecimal("40"));
        e2.setEventDate(LocalDate.of(2026, 6, 6));

        when(repository.findComparableEvents(eq("techno"), eq("Berlin"), any(), any()))
                .thenReturn(List.of(e1, e2));

        PricingRecommendation result = pricingService.recommend("techno", "Berlin", LocalDate.of(2026, 6, 14));

        assertThat(result.suggestedMinPrice()).isEqualByComparingTo("15.00");
        assertThat(result.suggestedMaxPrice()).isEqualByComparingTo("30.00");
        assertThat(result.recommendedDow()).isEqualTo("SATURDAY");
        assertThat(result.pricingNotes()).contains("2 comparable");
    }

    @Test
    void recommend_withNoComparables_returnsGenreDefault() {
        when(repository.findComparableEvents(any(), any(), any(), any()))
                .thenReturn(List.of());

        PricingRecommendation result = pricingService.recommend("techno", "Berlin", LocalDate.of(2026, 6, 14));

        assertThat(result.suggestedMinPrice()).isEqualByComparingTo("15.00");
        assertThat(result.suggestedMaxPrice()).isEqualByComparingTo("25.00");
        assertThat(result.recommendedDow()).isEqualTo("SATURDAY");
        assertThat(result.pricingNotes()).contains("default");
    }

    @Test
    void recommend_withUnknownGenreAndNoComparables_returnsBaseDefault() {
        when(repository.findComparableEvents(any(), any(), any(), any()))
                .thenReturn(List.of());

        PricingRecommendation result = pricingService.recommend("polka", "Warsaw", LocalDate.of(2026, 6, 14));

        assertThat(result.suggestedMinPrice()).isEqualByComparingTo("15.00");
        assertThat(result.suggestedMaxPrice()).isEqualByComparingTo("30.00");
        assertThat(result.recommendedDow()).isEqualTo("FRIDAY");
    }
}
