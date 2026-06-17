package com.imin.iminapi.payout;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link PayoutRunStatus} as its lowercase wire form
 * ({@code planned}/{@code submitted}/{@code paid}/{@code failed}) so the stored
 * VARCHAR matches the {@code V45__payout_runs.sql} status literals, rather than
 * the uppercase constant name.
 *
 * <p>Used instead of {@code @Enumerated(EnumType.STRING)} (which would persist the
 * uppercase name) — mirrors the Track A {@code SettlementStatusConverter}.
 */
@Converter(autoApply = false)
public class PayoutRunStatusConverter implements AttributeConverter<PayoutRunStatus, String> {

    @Override
    public String convertToDatabaseColumn(PayoutRunStatus attribute) {
        return attribute == null ? null : attribute.toWire();
    }

    @Override
    public PayoutRunStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return PayoutRunStatus.valueOf(dbData.toUpperCase(java.util.Locale.ROOT));
    }
}
