package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.marketing.dto.RecipientCounts;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * In-app notification when a campaign finishes or stops with an error. Goes to the campaign's creator while they
 * are active in the org, else to the first active owner, and only when their "A campaign ends" preference is on.
 * Writes in its own transaction and never throws, so a failure here cannot disturb a send.
 */
@Component
public class CampaignOutcomeNotifier {

    private static final Logger log = LoggerFactory.getLogger(CampaignOutcomeNotifier.class);

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository prefs;
    private final AudiencePlanAccess planAccess;
    private final TransactionTemplate requiresNew;

    public CampaignOutcomeNotifier(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                                   UserRepository users, NotificationRepository notifications,
                                   NotificationPreferencesRepository prefs, AudiencePlanAccess planAccess,
                                   PlatformTransactionManager transactionManager) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.users = users;
        this.notifications = notifications;
        this.prefs = prefs;
        this.planAccess = planAccess;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEnded(CampaignEnded ev) {
        try {
            requiresNew.executeWithoutResult(st -> write(ev));
        } catch (Exception e) {
            log.warn("Campaign outcome: notification failed for campaign {}: {}", ev.campaignId(),
                    LogSafe.redact(e.getMessage()));
        }
    }

    private void write(CampaignEnded ev) {
        Campaign c = campaigns.findById(ev.campaignId()).orElse(null);
        if (c == null) return;
        User to = recipient(c);
        if (to == null) return;
        boolean wants = prefs.findById(to.getId()).map(NotificationPreferences::isCampaignEnded).orElse(true);
        if (!wants) return;
        RecipientCounts counts = recipients.chipCounts(c.getId());
        long notSent = counts.failed();
        CampaignOutcomeText.Text text = CampaignOutcomeText.of(to.getLocale(), ev.outcome(), ev.failureCode(),
                c.getName(), counts.sent(), notSent, counts.skipped(), retryable(c));
        Notification n = new Notification();
        n.setUserId(to.getId());
        n.setKind("campaign." + ev.outcome());
        n.setTitle(text.title());
        n.setBody(text.body());
        n.setLink("/marketing/campaigns/" + c.getId());
        notifications.save(n);
    }

    /** Mirrors the refusals of {@code CampaignService.retry}: attempts spent, or audience-plan sends switched off. */
    private boolean retryable(Campaign c) {
        return c.getAttempts() < CampaignService.MAX_ATTEMPTS && planAccess.sendsAllowed(c.getOrigin());
    }

    /** The creator while active in the campaign's org, else the first active owner; null when neither exists. */
    private User recipient(Campaign c) {
        if (c.getCreatedBy() != null) {
            User creator = users.findById(c.getCreatedBy()).orElse(null);
            if (creator != null && c.getOrgId().equals(creator.getOrgId()) && creator.getDisabledAt() == null) {
                return creator;
            }
        }
        return users.findByOrgIdAndDisabledAtIsNullOrderByCreatedAtAsc(c.getOrgId()).stream()
                .filter(u -> u.getRole() == UserRole.OWNER)
                .findFirst()
                .orElse(null);
    }
}
