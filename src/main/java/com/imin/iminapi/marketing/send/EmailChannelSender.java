package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.render.MergeTags;
import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Spec §2.5 step 3: per-row email worker over campaign_recipients. Claims a batch of
 * pending rows (SKIP LOCKED), renders each with its own unsubscribe token, sends via
 * the Resend batch API, and stamps provider_message_id per row. Row-level state +
 * stored ids make mid-send restarts resumable and retries non-duplicating.
 */
@Service
public class EmailChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelSender.class);
    static final int BATCH_SIZE = 100;
    /** Rolling window the per-org daily cap is measured over — matches CampaignDispatcher. */
    private static final long DAILY_CAP_WINDOW_HOURS = 24;

    private final CampaignRecipientRepository recipients;
    private final CampaignRepository campaigns;
    private final CampaignEmailRenderer renderer;
    private final CampaignEmailProvider provider;
    private final UnsubscribeTokenService tokens;
    private final MarketingEmailProperties props;
    private final CampaignTemplateService templateService;
    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final MembershipRepository memberships;
    private final ConsumerRepository consumers;
    private final SendGateService sendGate;
    private final MarketingGuardProperties guardProps;

    public EmailChannelSender(CampaignRecipientRepository recipients, CampaignRepository campaigns,
                              CampaignEmailRenderer renderer, CampaignEmailProvider provider,
                              UnsubscribeTokenService tokens, MarketingEmailProperties props,
                              CampaignTemplateService templateService,
                              OrganizationRepository organizations, EventRepository events,
                              MembershipRepository memberships, ConsumerRepository consumers,
                              SendGateService sendGate, MarketingGuardProperties guardProps) {
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.renderer = renderer;
        this.provider = provider;
        this.tokens = tokens;
        this.props = props;
        this.templateService = templateService;
        this.organizations = organizations;
        this.events = events;
        this.memberships = memberships;
        this.consumers = consumers;
        this.sendGate = sendGate;
        this.guardProps = guardProps;
    }

    /**
     * Sends one batch. Returns true if there are (likely) more claimable rows to process.
     *
     * <p>REQUIRES_NEW is load-bearing: the provider send is irreversible, so this batch's
     * 'sent' flips and provider_message_ids must be durable BEFORE the next batch is
     * claimed. Sharing one transaction with the whole drive (the previous shape) meant a
     * later crash rolled the record of already-delivered mail back and the dispatcher
     * re-sent it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendNextBatch(Campaign c) {
        // Per-org daily cap (spec §7), re-checked PER BATCH. Checking it only at claim time
        // capped which campaigns start, not how much they send: a single 200k campaign
        // admitted under a 10,000/day cap then drained all 200k. Stopping here leaves the
        // campaign 'sending' with rows queued, so it resumes when the window rolls forward.
        if (recipients.countRecentSendsForOrg(c.getOrgId(),
                Instant.now().minus(DAILY_CAP_WINDOW_HOURS, ChronoUnit.HOURS))
                >= guardProps.getDailyCap()) {
            log.info("[email-sender] org {} at daily cap — pausing campaign {}",
                    c.getOrgId(), c.getId());
            return false;
        }
        List<CampaignRecipient> batch = new ArrayList<>(recipients.claimPendingBatch(c.getId(), BATCH_SIZE));
        if (batch.isEmpty()) return false;
        // Idempotence belt-and-braces: only rows still 'pending' may be sent. The claim
        // already filters on it, but a row that changed underneath us must never be
        // re-emailed just because it was in the claimed page.
        batch.removeIf(r -> !"pending".equals(r.getStatus()));
        // Unreachable while the claim filters on status; stop the drive rather than spin
        // if it ever is reached — the dispatcher re-claims the campaign either way.
        if (batch.isEmpty()) return false;
        // The gate snapshot was taken at materialisation, and a large campaign drains for
        // minutes-to-hours after that. Re-run it over THIS batch so someone who unsubscribed,
        // complained or was deliverability-suppressed in the meantime is diverted rather than
        // emailed — SendGateService is "THE ONLY path that yields sendable recipients".
        divertNoLongerSendable(c, batch);
        if (batch.isEmpty()) {
            return recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0;
        }

        // Template, org brand name, and event poster are constant for the whole campaign —
        // resolve them ONCE per batch, not per recipient. Only the unsubscribe URL varies.
        ResolvedTemplate template = templateService.resolve(c.getOrgId(), c.getTemplateKey());
        String brandName = brandName(c.getOrgId());
        Event event = linkedEvent(c);
        String posterUrl = event == null ? null : event.getPosterUrl();
        String ticketsUrl = ticketsUrl(c, event);
        // {{eventUrl}} resolves to the linked event's buyer page; with no linked event,
        // to the buyer-site root rather than a broken/empty link.
        String eventUrl = ticketsUrl != null ? ticketsUrl : props.getBuyerSiteBaseUrl();
        // Per-recipient {{firstName}} — resolve display names for the whole batch in two
        // batched queries (membership -> consumer -> display_name), not per row.
        Map<UUID, String> firstNameByMembership = resolveFirstNames(c.getOrgId(), batch);

        List<CampaignEmailProvider.OutgoingEmail> outgoing = new ArrayList<>(batch.size());
        for (CampaignRecipient r : batch) {
            String unsubUrl = props.unsubscribeUrl(
                    tokens.sign(c.getOrgId(), r.getMembershipId(), c.getId(), "email"));
            String firstName = r.getMembershipId() == null
                    ? MergeTags.FIRST_NAME_FALLBACK
                    : firstNameByMembership.getOrDefault(r.getMembershipId(), MergeTags.FIRST_NAME_FALLBACK);
            String subject = MergeTags.apply(c.getSubject(), firstName, eventUrl);
            String bodyMd = MergeTags.apply(c.getBodyMd(), firstName, eventUrl);
            String preheader = MergeTags.apply(c.getPreheader(), firstName, eventUrl);
            CampaignEmailRenderer.Rendered rendered = renderer.render(
                    subject, preheader, bodyMd,
                    c.getId().toString(), "email", unsubUrl,
                    template, brandName, posterUrl, ticketsUrl);
            outgoing.add(new CampaignEmailProvider.OutgoingEmail(
                    props.fromHeader(), r.getEmail(), subject,
                    rendered.html(), rendered.text(), unsubUrl));
        }

        try {
            List<String> ids = provider.sendBatch(outgoing);
            for (int i = 0; i < batch.size(); i++) {
                CampaignRecipient r = batch.get(i);
                r.setStatus("sent");
                r.setProviderMessageId(i < ids.size() ? ids.get(i) : null);
                r.setAttemptCount((short) (r.getAttemptCount() + 1));
                r.setLastEventAt(Instant.now());
                recipients.save(r);
            }
        } catch (ApiException ex) {
            log.warn("[email-sender] batch failed for campaign {} — leaving {} rows pending: {}",
                    c.getId(), batch.size(), ex.getMessage());
            for (CampaignRecipient r : batch) {
                r.setAttemptCount((short) (r.getAttemptCount() + 1));
                r.setLastEventAt(Instant.now());
                recipients.save(r);
            }
        }

        // Heartbeat: bump campaigns.updated_at so the dispatcher's stale-`sending` reclaim
        // (status='sending' AND updated_at < now()-5min) does not fire mid-send. A no-op
        // self-assign would NOT dirty the entity, so campaigns.save(c) could skip the UPDATE
        // and never touch updated_at. Use an explicit @Modifying UPDATE that always flushes.
        campaigns.touch(c.getId(), Instant.now());

        return recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0;
    }

    /**
     * Re-runs the Send Gate for a claimed batch and diverts every row that no longer passes
     * to {@code skipped} with the gate's own exclusion reason, removing it from the batch.
     * Read-only and tenant-scoped (SendGateService.evaluate), four batched queries per call.
     *
     * <p>Rows with a null membership_id (DSAR-erased) cannot be gated and are left alone —
     * they carry no consent state to re-check.
     */
    private void divertNoLongerSendable(Campaign c, List<CampaignRecipient> batch) {
        List<UUID> membershipIds = batch.stream()
                .map(CampaignRecipient::getMembershipId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (membershipIds.isEmpty()) return;
        SendGateService.GateResult gate = sendGate.evaluate(c.getOrgId(), membershipIds);
        if (gate.excluded().isEmpty()) return;
        Map<UUID, String> reasonByMembership = new HashMap<>();
        for (ExclusionReason ex : gate.excluded()) {
            reasonByMembership.put(ex.membershipId(), ex.reason());
        }
        Instant now = Instant.now();
        batch.removeIf(r -> {
            String reason = r.getMembershipId() == null
                    ? null : reasonByMembership.get(r.getMembershipId());
            if (reason == null) return false;
            r.setStatus("skipped");
            r.setSkipReason(reason);
            r.setLastEventAt(now);
            recipients.save(r);
            log.info("[email-sender] campaign {} recipient {} no longer sendable ({}) — skipping",
                    c.getId(), r.getId(), reason);
            return true;
        });
    }

    /**
     * The organizer's header identity for the branded shell: brand name if set, else the org
     * name. Failure-isolated — a lookup hiccup must not fail a live send, it just omits the
     * header text (the template still renders).
     */
    private String brandName(java.util.UUID orgId) {
        try {
            Organization org = organizations.findById(orgId).orElse(null);
            if (org == null) return null;
            return org.getBrandName() != null && !org.getBrandName().isBlank()
                    ? org.getBrandName() : org.getName();
        } catch (Exception e) {
            log.debug("[email-sender] brand-name lookup failed for org {}: {}", orgId, e.getMessage());
            return null;
        }
    }

    /**
     * The campaign's linked event, org-scoped and active. Null when the campaign has no event or
     * it resolves outside this org / is soft-deleted. Failure-isolated: a lookup hiccup must not
     * fail a live send — the render just skips the poster and tickets button. Resolved ONCE per
     * batch and reused for both the poster header and the tickets-button URL.
     */
    private Event linkedEvent(Campaign c) {
        if (c.getEventId() == null) return null;
        try {
            return events.findActive(c.getEventId())
                    .filter(e -> c.getOrgId().equals(e.getOrgId()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[email-sender] event lookup failed for {}: {}", c.getEventId(), e.getMessage());
            return null;
        }
    }

    /**
     * The linked event's PUBLIC buyer URL ({@code {base}/e/{eventId}} on imin-public), for the
     * {@code {{tickets_button}}} CTA. Null when there is no linked event — the renderer then drops
     * the token rather than link to a fabricated URL. The base is the same imin-public origin the
     * unsubscribe footer already uses.
     */
    private String ticketsUrl(Campaign c, Event event) {
        if (event == null) return null;
        return props.getBuyerSiteBaseUrl() + "/e/" + event.getId();
    }

    /**
     * Batch-resolve first names for a claimed recipient batch: membership_id ->
     * greetable first name (from consumers.display_name), falling back to the neutral
     * greeting when a member has no usable name. Two batched queries, no per-row IO.
     */
    private Map<UUID, String> resolveFirstNames(UUID orgId, List<CampaignRecipient> batch) {
        List<UUID> membershipIds = batch.stream()
                .map(CampaignRecipient::getMembershipId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (membershipIds.isEmpty()) return Map.of();
        List<Membership> mems = memberships.findByIdsAndOrgId(membershipIds, orgId);
        List<UUID> consumerIds = mems.stream().map(Membership::getConsumerId).distinct().toList();
        Map<UUID, String> nameByConsumer = new HashMap<>();
        if (!consumerIds.isEmpty()) {
            for (Consumer cn : consumers.findAllByConsumerIdIn(consumerIds)) {
                nameByConsumer.put(cn.getConsumerId(), MergeTags.firstName(cn.getDisplayName()));
            }
        }
        Map<UUID, String> out = new HashMap<>();
        for (Membership m : mems) {
            out.put(m.getMembershipId(),
                    nameByConsumer.getOrDefault(m.getConsumerId(), MergeTags.FIRST_NAME_FALLBACK));
        }
        return out;
    }

}
