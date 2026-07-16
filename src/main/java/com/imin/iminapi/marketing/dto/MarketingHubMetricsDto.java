package com.imin.iminapi.marketing.dto;

/**
 * The Marketing hub tiles read-model (GET /api/v1/marketing/hub-metrics).
 *
 * <p>Every field is a REAL number sourced from the DB/services, or a literal 0 when the
 * underlying source genuinely does not exist yet — nothing here is a placeholder.
 *
 * <ul>
 *   <li>{@code sendableEmail} — audience that clears the Send Gate over ALL the org's
 *       memberships (same machinery as campaign preview-audience).</li>
 *   <li>{@code totalContacts} — count of the org's memberships.</li>
 *   <li>{@code attributedRevMinor} — attributed revenue in minor units: a TRUE per-order
 *       last-touch sum of the revenue driven by the org's campaigns from the last 30 days,
 *       joined on {@code orders.utm_campaign = <campaign id>} (V62). Not an estimate and
 *       not divided by visit share. Legitimately 0 when those campaigns drove no paid
 *       orders — and necessarily 0 for campaigns that ran before V62, whose orders carry
 *       no utm_campaign and cannot be back-filled (the tag was never captured).</li>
 *   <li>{@code attributedPurchases} — attributed checkout SESSIONS summed across the org's
 *       campaigns from the last 30 days (utm_campaign funnel read-model). A visit-based
 *       proxy: a session that starts checkout but never pays still counts. It is therefore
 *       normal for this to exceed the paid orders behind {@code attributedRevMinor} — the
 *       two measure different things and neither is derived from the other.</li>
 *   <li>{@code momentumWaiting} — live 'suggested' Momentum suggestions for the org.</li>
 *   <li>{@code momentumWatching} — on-sale events for the org currently eligible for
 *       Momentum evaluation.</li>
 *   <li>{@code smsPhones} — memberships for the org with an SMS opt-in captured.</li>
 *   <li>{@code emailFrom}/{@code emailFromName} — the configured marketing sender identity
 *       (empty string when unset).</li>
 *   <li>{@code sendingEnabled} — whether the marketing sender is configured well enough to
 *       send (a from-address is present).</li>
 * </ul>
 */
public record MarketingHubMetricsDto(
        int sendableEmail,
        int totalContacts,
        long attributedRevMinor,
        int attributedPurchases,
        int momentumWaiting,
        int momentumWatching,
        int smsPhones,
        String emailFrom,
        String emailFromName,
        boolean sendingEnabled
) {}
