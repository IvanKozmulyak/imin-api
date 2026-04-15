package com.imin.iminapi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record EventCreatorResponse(
        UUID id,
        String status,
        List<String> accentColors,
        List<String> posterUrls,
        List<ConceptDto> concepts,
        List<SocialCopyDto> socialCopy,
        PricingDto pricing,
        LocalDateTime createdAt
) {
    public record ConceptDto(String title, String description, String tagline, int sortOrder) {}

    public record SocialCopyDto(String platform, String copyText) {}

    public record PricingDto(
            BigDecimal suggestedMinPrice,
            BigDecimal suggestedMaxPrice,
            String pricingNotes
    ) {}
}
