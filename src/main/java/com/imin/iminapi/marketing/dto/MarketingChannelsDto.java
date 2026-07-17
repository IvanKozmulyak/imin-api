package com.imin.iminapi.marketing.dto;

import java.time.Instant;

/**
 * Channel health + guardrails read-model (GET /api/v1/marketing/channels).
 *
 * <p>This surface exists because the guardrails below are ALREADY ENFORCED server-side but
 * were never exposed over HTTP, so the dashboard's Channels tab rendered them as hard-coded
 * prose. Every field here is read back from the SAME config object or table the enforcement
 * path reads — so a number shown to an organizer cannot drift from the number that actually
 * gates their sends.
 *
 * <p><b>Everything below is real or structurally honest. Nothing is a placeholder.</b>
 *
 * <h2>Email</h2>
 * <ul>
 *   <li>{@code provider} — {@code "resend"}. Literal: {@code CampaignEmailProvider} sends bulk
 *       campaigns through the Resend batch API, and {@code ProviderEvent.PROVIDER_RESEND} is
 *       the only provider the webhook projector accepts.</li>
 *   <li>{@code fromAddress}/{@code fromName}/{@code fromHeader} — the configured marketing
 *       sender identity, verbatim from {@code imin.marketing.*} ({@code MarketingEmailProperties}).
 *       Empty string when unset. {@code fromHeader} is the provider's own
 *       {@code MarketingEmailProperties#fromHeader()} rendering, not a re-implementation.</li>
 *   <li>{@code sendingDomain} — the host part of the configured {@code fromAddress}
 *       ({@code contact@imin.support} ⇒ {@code imin.support}); empty when unset. This is the
 *       domain mail is sent FROM. It is <b>not</b> a verification status — see the
 *       "deliberately absent" note below.</li>
 *   <li>{@code sendingEnabled} — whether a from-address is configured at all (same test
 *       {@code MarketingHubService} applies). Blank from-address ⇒ nothing can send.</li>
 *   <li>{@code oneClickUnsubscribe} — {@code true}, unconditionally and truthfully: every
 *       campaign email built by {@code CampaignEmailProvider} carries
 *       {@code List-Unsubscribe} + {@code List-Unsubscribe-Post: List-Unsubscribe=One-Click}
 *       (RFC 8058) pointing at the owned {@code /api/v1/public/unsubscribe/{token}} endpoint.
 *       There is no code path that sends a campaign email without them.</li>
 *   <li>{@code sentLast30d} — org-scoped count of recipient rows that actually reached
 *       {@code sent|delivered|opened|clicked} in the last 30 days, via the same
 *       {@code CampaignRecipientRepository#countRecentSendsForOrg} query the daily cap uses.</li>
 * </ul>
 *
 * <h2>Quiet hours ({@link QuietHoursWindow})</h2>
 * Read from {@code QuietHours} — the component the dispatcher actually calls.
 * <ul>
 *   <li>{@code startLocal}/{@code endLocal} — {@code "22:00"}/{@code "09:00"}, read from the
 *       {@code QuietHours} constants themselves. Half-open window {@code [22:00, 09:00)}:
 *       09:00 sharp sends, 22:00 sharp is quiet.</li>
 *   <li>{@code timezone} — the org's own {@code organizations.timezone} (the exact value fed to
 *       the enforcement call). {@code QuietHours} falls back to UTC for a blank/unparseable
 *       zone; this field reports what is stored, not the fallback.</li>
 *   <li>{@code enforced} — {@code true}. {@code CampaignDispatcher} calls
 *       {@code QuietHours.isEmailQuiet} unconditionally on every claim pass; there is no
 *       override and no feature flag on that path. (A separate, unwired {@code QuietHoursPolicy}
 *       seam with a default-off flag exists in the tree but is referenced only by its own test —
 *       it gates nothing, so it is deliberately NOT what this field reports.)</li>
 *   <li>{@code quietNow} — live evaluation of {@code QuietHours.isEmailQuiet(orgTz, now)}.</li>
 * </ul>
 *
 * <h2>Guardrails ({@link Guardrails})</h2>
 * <ul>
 *   <li>{@code dailyCap} — {@code MarketingGuardProperties#getDailyCap()} (config
 *       {@code imin.marketing.guard.daily-cap}, default 10000 per spec §8/§10): max sends
 *       per org per <b>rolling 24h</b>, not per calendar day.</li>
 *   <li>{@code sentLast24h} — the org's rolling-24h send count. This is the EXACT quantity the
 *       dispatcher compares against {@code dailyCap}, from the same query
 *       ({@code countRecentSendsForOrg}). Named for the rolling window rather than "today"
 *       because no calendar-day counter exists.</li>
 *   <li>{@code frequencyFloorHours} — {@code MarketingGuardProperties#getFrequencyFloorHours()}
 *       (default 24): a membership contacted inside this window is skipped with
 *       {@code skip_reason='frequency_capped'} ({@code CampaignVolumeGuard}).</li>
 *   <li>{@code complaintRate} — {@code complaintCount / deliveredCount}, org-wide across all the
 *       org's campaigns, from {@code provider_events}. 0.0 when nothing was delivered.
 *       <b>This is an org-wide aggregate view, NOT the number the breaker thresholds:</b>
 *       {@code ComplaintRateBreaker} evaluates PER CAMPAIGN, so a single bad campaign can trip
 *       the pause while this org-wide rate still reads below {@code autoPauseThreshold}. It is
 *       named {@code complaintRate} — what it literally is — and never dressed up as a
 *       reputation grade.</li>
 *   <li>{@code complaintCount}/{@code deliveredCount} — the raw org-scoped
 *       {@code email.complained} / {@code email.delivered} counts behind that ratio, exposed so
 *       the rate is auditable rather than an opaque float.</li>
 *   <li>{@code autoPauseThreshold} — {@code ComplaintRateBreaker.THRESHOLD} (0.001 = 0.1%),
 *       read from the breaker constant itself.</li>
 *   <li>{@code autoPauseMinVolume} — {@code ComplaintRateBreaker.MIN_VOLUME} (200). Below this
 *       per-campaign volume the breaker never trips, however bad the ratio looks.</li>
 *   <li>{@code paused}/{@code pausedAt} — {@code organizations.marketing_paused_at} (V57). When
 *       set, {@code CampaignDispatcher} skips every one of the org's campaigns. {@code pausedAt}
 *       is null exactly when {@code paused} is false.</li>
 * </ul>
 *
 * <h2>SMS ({@link Sms})</h2>
 * <ul>
 *   <li>{@code optedInPhones} — real: memberships for the org with
 *       {@code sms_consent_status='subscribed'} AND a stored E.164 phone.</li>
 *   <li>{@code sendingEnabled} — {@code false}, and honestly so. {@code "sms"} is an accepted
 *       {@code campaigns.channel} value ({@code CampaignService.CHANNELS}), but no SMS provider,
 *       sender or client exists in the tree and {@code CampaignRepository.claimDue} filters
 *       {@code WHERE channel = 'email'} — so an SMS campaign is never dispatched. Consent is
 *       collected; sending is not built.</li>
 * </ul>
 *
 * <h2>Deliberately absent — unsourceable, so omitted rather than invented</h2>
 * <ul>
 *   <li><b>SPF / DKIM / DMARC status</b> — would require the Resend <i>domains</i> API. No client
 *       method and no configured credential for it exist ({@code ResendConfig} builds a single
 *       {@code Resend} bean used only for sending; the only SPF/DKIM mentions in the tree are
 *       prose comments). Reporting "SPF: ok" would be fabricated, so these fields do not exist.
 *       {@code sendingDomain} tells you WHICH domain to go check — it does not claim it passes.</li>
 *   <li><b>Reputation score / grade</b> — Resend exposes no such metric and nothing in the tree
 *       derives one. The honest neighbours ({@code complaintRate}, {@code complaintCount},
 *       {@code deliveredCount}) are published under their real names instead.</li>
 *   <li><b>Bounce rate</b> — {@code ProviderEvent.TYPE_BOUNCED} is projected, but nothing
 *       enforces on it; it is omitted here to keep this surface to config + enforced counts.</li>
 *   <li><b>SMS provider / region / sender ID / encoding / per-campaign first-release recipient
 *       cap</b> — no SMS provider config, no sender-id config, no region config, no encoding
 *       handling and no first-release cap exist anywhere in the tree. All five would be invented
 *       data, so none of them is a field.</li>
 * </ul>
 *
 * <p>No secret is reachable from this DTO: no API key, no webhook signing secret, no CAPI token.
 */
public record MarketingChannelsDto(Email email, Sms sms) {

    /** Email channel identity + health. See the class javadoc for per-field sourcing. */
    public record Email(
            String provider,
            String fromAddress,
            String fromName,
            String fromHeader,
            String sendingDomain,
            boolean sendingEnabled,
            boolean oneClickUnsubscribe,
            QuietHoursWindow quietHours,
            Guardrails guardrails,
            long sentLast30d
    ) {}

    /** The org-local window in which the dispatcher hard-blocks email. */
    public record QuietHoursWindow(
            String startLocal,
            String endLocal,
            String timezone,
            boolean enforced,
            boolean quietNow
    ) {}

    /** The enforced volume + complaint guardrails, with the org's live position against them. */
    public record Guardrails(
            int dailyCap,
            long sentLast24h,
            int frequencyFloorHours,
            double complaintRate,
            long complaintCount,
            long deliveredCount,
            double autoPauseThreshold,
            long autoPauseMinVolume,
            boolean paused,
            Instant pausedAt
    ) {}

    /** SMS: consent is collected, sending is not built. */
    public record Sms(
            boolean sendingEnabled,
            long optedInPhones
    ) {}
}
