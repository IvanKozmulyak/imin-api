package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.stock.StockFacts;

import java.time.Instant;

/**
 * Facts read from the tier rows (left = quantity − reserved − sold, threshold, all-in minor prices, close, read time,
 * event zone and currency); the next-tier fields are null for "last tickets".
 */
public record StockFactsDto(String tierName, int remaining, int quantity, int threshold, long priceAllInMinor,
                            java.util.UUID nextTierId, String nextTierName, Long nextPriceAllInMinor, Instant closesAt,
                            Instant checkedAt, String timezone, String currency) {

    public static StockFactsDto from(StockFacts f) {
        if (f == null) return null;
        return new StockFactsDto(f.tierName(), f.remaining(), f.quantity(), f.threshold(), f.priceAllInMinor(),
                f.nextTierId(), f.nextTierName(), f.nextPriceAllInMinor(), f.closesAt(), f.checkedAt(), f.timezone(),
                f.currency());
    }
}
