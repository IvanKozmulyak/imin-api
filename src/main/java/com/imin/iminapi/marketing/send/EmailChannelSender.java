package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    private final CampaignRecipientRepository recipients;
    private final CampaignRepository campaigns;
    private final CampaignEmailRenderer renderer;
    private final CampaignEmailProvider provider;
    private final UnsubscribeTokenService tokens;
    private final MarketingEmailProperties props;
    private final CampaignTemplateService templateService;
    private final OrganizationRepository organizations;
    private final EventRepository events;

    public EmailChannelSender(CampaignRecipientRepository recipients, CampaignRepository campaigns,
                              CampaignEmailRenderer renderer, CampaignEmailProvider provider,
                              UnsubscribeTokenService tokens, MarketingEmailProperties props,
                              CampaignTemplateService templateService,
                              OrganizationRepository organizations, EventRepository events) {
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.renderer = renderer;
        this.provider = provider;
        this.tokens = tokens;
        this.props = props;
        this.templateService = templateService;
        this.organizations = organizations;
        this.events = events;
    }

    /** Sends one batch. Returns true if there are (likely) more pending rows to process. */
    @Transactional
    public boolean sendNextBatch(Campaign c) {
        List<CampaignRecipient> batch = recipients.claimPendingBatch(c.getId(), BATCH_SIZE);
        if (batch.isEmpty()) return false;

        // Template, org brand name, and event poster are constant for the whole campaign —
        // resolve them ONCE per batch, not per recipient. Only the unsubscribe URL varies.
        ResolvedTemplate template = templateService.resolve(c.getOrgId(), c.getTemplateKey());
        String brandName = brandName(c.getOrgId());
        Event event = linkedEvent(c);
        String posterUrl = event == null ? null : event.getPosterUrl();
        String ticketsUrl = ticketsUrl(c, event);

        List<CampaignEmailProvider.OutgoingEmail> outgoing = new ArrayList<>(batch.size());
        for (CampaignRecipient r : batch) {
            String unsubUrl = props.getUnsubscribeBaseUrl() + "/optout?token="
                    + tokens.sign(c.getOrgId(), r.getMembershipId(), c.getId(), "email");
            CampaignEmailRenderer.Rendered rendered = renderer.render(
                    c.getSubject(), c.getPreheader(), c.getBodyMd(),
                    c.getId().toString(), "email", unsubUrl,
                    template, brandName, posterUrl, ticketsUrl);
            outgoing.add(new CampaignEmailProvider.OutgoingEmail(
                    props.fromHeader(), r.getEmail(), c.getSubject(),
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
        return props.getUnsubscribeBaseUrl() + "/e/" + event.getId();
    }
}
