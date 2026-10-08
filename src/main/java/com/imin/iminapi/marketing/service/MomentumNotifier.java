package com.imin.iminapi.marketing.service;

import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Best-effort in-app notification for a new momentum suggestion (spec §6.4).
 * Runs in its OWN transaction (REQUIRES_NEW) so a failure here (e.g. a stale
 * owner id FK violation) rolls back ONLY this write, never the caller's
 * suggestion transaction.
 */
@Component
public class MomentumNotifier {

    private static final Logger log = LoggerFactory.getLogger(MomentumNotifier.class);

    private final UserRepository users;
    private final NotificationRepository notifications;
    private final TransactionTemplate requiresNew;

    public MomentumNotifier(UserRepository users, NotificationRepository notifications,
                            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.notifications = notifications;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Notify the org owner that a suggestion is waiting. Never throws. */
    public void notifyOwner(UUID orgId, String triggerWire, String why) {
        try {
            // The transaction ends inside the try, so a rollback-only mark or a failed commit flush is caught too.
            requiresNew.executeWithoutResult(status -> write(orgId, triggerWire, why));
        } catch (Exception notifyEx) {
            log.warn("Momentum: notification write failed for org {}: {}", orgId, LogSafe.redact(notifyEx.getMessage()));
        }
    }

    private void write(UUID orgId, String triggerWire, String why) {
        UUID ownerUserId = users.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(u -> u.getRole() == UserRole.OWNER)
                .map(User::getId)
                .findFirst()
                .orElse(null);
        if (ownerUserId == null) return; // no owner user — skip cleanly
        Notification n = new Notification();
        n.setUserId(ownerUserId);
        n.setKind("momentum_suggestion");
        n.setTitle("New campaign suggestion: " + triggerWire.replace('_', ' '));
        n.setBody(why);
        n.setLink("/marketing"); // Momentum tab
        notifications.save(n);
    }
}
