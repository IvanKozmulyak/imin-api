package com.imin.iminapi.settlement;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link SettlementObjectType} as its lowercase wire form
 * ({@code transfer} / {@code payout}) so the stored VARCHAR matches the V43
 * migration's object_type literals, rather than the uppercase constant name.
 */
@Converter(autoApply = false)
public class SettlementObjectTypeConverter implements AttributeConverter<SettlementObjectType, String> {

    @Override
    public String convertToDatabaseColumn(SettlementObjectType attribute) {
        return attribute == null ? null : attribute.toWire();
    }

    @Override
    public SettlementObjectType convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return SettlementObjectType.valueOf(dbData.toUpperCase(java.util.Locale.ROOT));
    }
}
