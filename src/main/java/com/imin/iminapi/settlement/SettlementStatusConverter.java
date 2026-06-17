package com.imin.iminapi.settlement;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link SettlementStatus} as its lowercase-with-underscore wire form
 * ({@code in_transit}, not {@code IN_TRANSIT}) so the stored VARCHAR matches the
 * V43 migration's status literals and the FE Payout status union byte-for-byte.
 *
 * <p>Used instead of {@code @Enumerated(EnumType.STRING)} because that would
 * persist the uppercase constant name.
 */
@Converter(autoApply = false)
public class SettlementStatusConverter implements AttributeConverter<SettlementStatus, String> {

    @Override
    public String convertToDatabaseColumn(SettlementStatus attribute) {
        return attribute == null ? null : attribute.toWire();
    }

    @Override
    public SettlementStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return SettlementStatus.valueOf(dbData.toUpperCase(java.util.Locale.ROOT));
    }
}
