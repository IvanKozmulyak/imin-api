package com.imin.iminapi.audience.dto;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FE-facing member DTO. Field names match the spec verbatim.
 */
public record MemberDto(
        String membershipId,
        String name,
        String email,
        String city,
        List<String> genres,
        int events,
        int attended,
        int noShow,
        int orders,
        long spendMinor,
        long aovMinor,
        Instant firstSeenAt,
        Instant lastPurchaseAt,
        Instant lastAttendedAt,
        Integer recencyDays,
        String firstTouchSource,
        String lawfulBasis,          // 'explicit' | 'soft_opt_in' | null
        String subscriptionStatus,   // 'subscribed' | 'unsubscribed' | 'never'
        SuppressionInfo suppression, // null if not suppressed
        Instant lastEmailOpenAt,
        Instant lastEmailClickAt,
        Integer nps,
        String vibe,
        String quote,
        List<String> tags,
        String notes,
        String lifecycle,
        RfmInfo rfm
) {
    public record SuppressionInfo(String scope, String reason, Instant since) {}
    public record RfmInfo(int r, int f, int m) {}

    public static MemberDto from(Membership m, Consumer consumer, SuppressionEntry suppression) {
        SuppressionInfo suppInfo = suppression == null ? null
                : new SuppressionInfo(suppression.getScope(), suppression.getReason(), suppression.getSince());
        return new MemberDto(
                m.getMembershipId().toString(),
                m.getDisplayName() != null ? m.getDisplayName() : (consumer != null ? consumer.getDisplayName() : ""),
                consumer != null ? consumer.getNormalizedEmail() : "",
                m.getCity(),
                m.getGenres(),
                m.getEvents(),
                m.getAttended(),
                m.getNoShow(),
                m.getOrders(),
                m.getSpendMinor(),
                m.getAovMinor(),
                m.getFirstSeen(),
                m.getLastPurchase(),
                m.getLastAttended(),
                m.getRecencyDays(),
                m.getFirstTouchSrc(),
                m.getConsentBasis(),
                m.getConsentStatus(),
                suppInfo,
                m.getLastEmailOpen(),
                m.getLastEmailClick(),
                m.getNps() != null ? m.getNps().intValue() : null,
                m.getVibe(),
                m.getQuote(),
                m.getTags(),
                m.getNotes(),
                m.getLifecycle(),
                new RfmInfo(m.getRfmR(), m.getRfmF(), m.getRfmM())
        );
    }
}
