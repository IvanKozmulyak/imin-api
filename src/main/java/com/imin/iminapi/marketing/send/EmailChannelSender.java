package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
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

    public EmailChannelSender(CampaignRecipientRepository recipients, CampaignRepository campaigns,
                              CampaignEmailRenderer renderer, CampaignEmailProvider provider,
                              UnsubscribeTokenService tokens, MarketingEmailProperties props) {
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.renderer = renderer;
        this.provider = provider;
        this.tokens = tokens;
        this.props = props;
    }

    /** Sends one batch. Returns true if there are (likely) more pending rows to process. */
    @Transactional
    public boolean sendNextBatch(Campaign c) {
        List<CampaignRecipient> batch = recipients.claimPendingBatch(c.getId(), BATCH_SIZE);
        if (batch.isEmpty()) return false;

        List<CampaignEmailProvider.OutgoingEmail> outgoing = new ArrayList<>(batch.size());
        for (CampaignRecipient r : batch) {
            String unsubUrl = props.getUnsubscribeBaseUrl() + "/optout?token="
                    + tokens.sign(c.getOrgId(), r.getMembershipId(), c.getId(), "email");
            CampaignEmailRenderer.Rendered rendered = renderer.render(
                    c.getSubject(), c.getPreheader(), c.getBodyMd(),
                    c.getId().toString(), "email", unsubUrl);
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
}
