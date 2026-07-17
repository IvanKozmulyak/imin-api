package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.marketing.dto.MarketingChannelsDto;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.email.ResendDomainsClient;
import com.imin.iminapi.marketing.email.SendingDomainDns;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.sms.MarketingSmsProperties;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.MarketingChannelStatsRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Read-model behind GET /api/v1/marketing/channels — channel identity, the enforced
 * guardrails, and the org's live position against them.
 *
 * <p>Org comes ONLY from the AuthPrincipal (SPINE INVARIANT): every count is scoped to
 * {@code principal.orgId()}, so another org's sends, complaints or SMS opt-ins can never
 * appear in a caller's numbers.
 *
 * <p>This service reads the SAME objects the enforcement path reads — {@link
 * MarketingGuardProperties} for the caps, {@link ComplaintRateBreaker}'s own constants for
 * the breaker thresholds, {@link QuietHours}'s own constants and its live evaluator for the
 * window, {@code organizations.marketing_paused_at} for the pause, and
 * {@code CampaignRecipientRepository#countRecentSendsForOrg} for the rolling counts. Nothing
 * is duplicated or restated, so a displayed number cannot drift from the enforced one.
 *
 * <p>Two additions beyond the enforced-guardrail core, both kept honest:
 * <ul>
 *   <li>SPF/DKIM/DMARC ({@code email.dns}) are read live from Resend's domains API via
 *       {@link ResendDomainsClient} and are ABSENT (null) whenever that read can't be made
 *       truthfully — never a fabricated "verified".</li>
 *   <li>The SMS metadata (sender-id/provider/region/cap/window/unlock-threshold) is INTENDED
 *       first-release policy from {@link MarketingSmsProperties}, surfaced with
 *       {@code sms.sendingEnabled=false} and {@code sendWindow.enforced=false} so it can never be
 *       mistaken for a live guardrail — there is no SMS dispatcher.</li>
 * </ul>
 * Fields that still can't be sourced truthfully (any reputation score, bounce rate, SMS
 * encoding) remain ABSENT — see {@link MarketingChannelsDto} for the full rationale.
 */
@Service
public class MarketingChannelsService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final long DAILY_CAP_WINDOW_HOURS = 24;   // mirrors CampaignDispatcher
    private static final long SENT_WINDOW_DAYS = 30;

    private final MarketingEmailProperties emailProps;
    private final MarketingSmsProperties smsProps;
    private final MarketingGuardProperties guardProps;
    private final QuietHours quietHours;
    private final ResendDomainsClient domainsClient;
    private final OrganizationRepository orgs;
    private final CampaignRecipientRepository recipients;
    private final MarketingChannelStatsRepository providerStats;
    private final MembershipRepository memberships;

    public MarketingChannelsService(MarketingEmailProperties emailProps,
                                    MarketingSmsProperties smsProps,
                                    MarketingGuardProperties guardProps,
                                    QuietHours quietHours,
                                    ResendDomainsClient domainsClient,
                                    OrganizationRepository orgs,
                                    CampaignRecipientRepository recipients,
                                    MarketingChannelStatsRepository providerStats,
                                    MembershipRepository memberships) {
        this.emailProps = emailProps;
        this.smsProps = smsProps;
        this.guardProps = guardProps;
        this.quietHours = quietHours;
        this.domainsClient = domainsClient;
        this.orgs = orgs;
        this.recipients = recipients;
        this.providerStats = providerStats;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public MarketingChannelsDto channels(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        Instant now = Instant.now();

        Organization org = orgs.findById(orgId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Organization not found"));

        // ---- sender identity (config verbatim; blank when unset — never faked) ----
        String fromAddress = nullToEmpty(emailProps.getFromAddress());
        String fromName = nullToEmpty(emailProps.getFromName());
        boolean sendingEnabled = !fromAddress.isBlank();

        // ---- guardrails: the rolling-24h count the dispatcher itself compares to the cap ----
        long sentLast24h = recipients.countRecentSendsForOrg(
                orgId, now.minus(DAILY_CAP_WINDOW_HOURS, ChronoUnit.HOURS));
        long sentLast30d = recipients.countRecentSendsForOrg(
                orgId, now.minus(SENT_WINDOW_DAYS, ChronoUnit.DAYS));

        // ---- complaint rate: org-wide aggregate. The breaker trips PER CAMPAIGN, so this is
        // a health view, not the exact quantity thresholded (documented on the DTO). ----
        long delivered = providerStats.countByOrgIdAndType(orgId, ProviderEvent.TYPE_DELIVERED);
        long complaints = providerStats.countByOrgIdAndType(orgId, ProviderEvent.TYPE_COMPLAINED);
        double complaintRate = delivered == 0 ? 0.0 : (double) complaints / delivered;

        Instant pausedAt = org.getMarketingPausedAt();

        MarketingChannelsDto.Guardrails guardrails = new MarketingChannelsDto.Guardrails(
                guardProps.getDailyCap(),
                sentLast24h,
                guardProps.getFrequencyFloorHours(),
                complaintRate,
                complaints,
                delivered,
                ComplaintRateBreaker.THRESHOLD,
                ComplaintRateBreaker.MIN_VOLUME,
                pausedAt != null,
                pausedAt);

        // ---- quiet hours: constants + live evaluation from the enforcing component ----
        MarketingChannelsDto.QuietHoursWindow window = new MarketingChannelsDto.QuietHoursWindow(
                QuietHours.EMAIL_QUIET_START.format(HH_MM),
                QuietHours.EMAIL_QUIET_END.format(HH_MM),
                nullToEmpty(org.getTimezone()),
                true,   // CampaignDispatcher calls isEmailQuiet unconditionally — no flag, no override
                quietHours.isEmailQuiet(org.getTimezone(), now));

        // ---- DNS: SPF/DKIM/DMARC read live from Resend's domains API, cached; null when it
        //      can't be read truthfully (no domains scope / unreachable / domain not registered) ----
        SendingDomainDns dns = domainsClient.sendingDomainDns().orElse(null);

        MarketingChannelsDto.Email email = new MarketingChannelsDto.Email(
                ProviderEvent.PROVIDER_RESEND,
                fromAddress,
                fromName,
                emailProps.fromHeader(),
                sendingDomain(fromAddress),
                dns,
                sendingEnabled,
                true,   // RFC 8058 headers are attached to every campaign email, unconditionally
                window,
                guardrails,
                sentLast30d);

        // ---- SMS: opt-ins are real; sending is not built (no provider, dispatcher is email-only).
        //      The remaining fields are INTENDED first-release policy from MarketingSmsProperties,
        //      not live guardrails — sendingEnabled=false and sendWindow.enforced=false say so. ----
        MarketingChannelsDto.SmsSendWindow smsWindow = new MarketingChannelsDto.SmsSendWindow(
                smsProps.getSendWindowStartLocal(),
                smsProps.getSendWindowEndLocal(),
                false);   // no SMS dispatcher exists — this window enforces nothing
        MarketingChannelsDto.Sms sms = new MarketingChannelsDto.Sms(
                false,
                memberships.countSmsSubscribedByOrgId(orgId),
                smsProps.getSenderId(),
                smsProps.getProvider(),
                smsProps.getRegion(),
                smsProps.getFirstReleaseRecipientCap(),
                smsWindow,
                smsProps.getUnlockThresholdPhones());

        return new MarketingChannelsDto(email, sms);
    }

    /** Host part of the configured from-address ("contact@imin.support" ⇒ "imin.support"). */
    private static String sendingDomain(String fromAddress) {
        int at = fromAddress.lastIndexOf('@');
        if (at < 0 || at == fromAddress.length() - 1) return "";
        return fromAddress.substring(at + 1);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
