package com.imin.iminapi.marketing.email;

import java.time.Instant;

/**
 * SPF / DKIM / DMARC verification status for the marketing sending domain, read live from the
 * Resend <b>domains</b> API ({@code GET /domains} then {@code GET /domains/{id}}) and cached.
 *
 * <p><b>Presence is itself the honest signal.</b> This object is attached to the channels DTO
 * ONLY when Resend actually answered for the configured from-domain. When the Resend key lacks
 * the {@code domains} scope, the API is unreachable, or the from-domain is not registered in the
 * Resend account, the entire object is absent (null) — the dashboard shows nothing, never a
 * fabricated "verified".
 *
 * <p>Each per-record field is null when Resend returned no record of that kind. {@code dmarc} is
 * commonly null: Resend does not provision a DMARC record by default, so a DMARC status is only
 * reported when Resend itself reports one — we do not infer or invent it.
 *
 * @param domain    the from-domain these statuses were read for (host of the marketing from-address)
 * @param spf       aggregated SPF status, or null when Resend reported no SPF record
 * @param dkim      aggregated DKIM status, or null when Resend reported no DKIM record
 * @param dmarc     aggregated DMARC status, or null when Resend reported no DMARC record
 * @param checkedAt when this snapshot was fetched from Resend (drives the client's cache TTL)
 */
public record SendingDomainDns(
        String domain,
        DnsRecordStatus spf,
        DnsRecordStatus dkim,
        DnsRecordStatus dmarc,
        Instant checkedAt
) {}
