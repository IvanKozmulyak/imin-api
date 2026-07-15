package com.imin.iminapi.marketing.service;

import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Best-effort in-app notification for a new momentum suggestion (spec §6.4).
 * Runs in its OWN transaction (REQUIRES_NEW) so a failure here (e.g. a stale
 * owner id FK violation) rolls back ONLY this write, never the caller's
 * suggestion transaction. Mirrors AudienceOrderProjector's REQUIRES_NEW
 * best-effort pattern (AudienceOrderProjector.java:50-53).
 */
@Component
public class MomentumNotifier {

    private static final Logger log = LoggerFactory.getLogger(MomentumNotifier.class);

    private final UserRepository users;
    private final NotificationRepository notifications;

    public MomentumNotifier(UserRepository users, NotificationRepository notifications) {
        this.users = users;
        this.notifications = notifications;
    }

    /** Notify the org owner that a suggestion is waiting. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyOwner(UUID orgId, String triggerWire, String why) {
        try {
            UUID ownerUserId = users.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                    .filter(u -> u.getRole() == UserRole.OWNER)
                    .map(User::getId)
                    .findFirst()
                    .orElse(null);
            if (ownerUserId == null) return; // no owner user — skip cleanly
            Notification n = new Notification();
            n.setId(UUID.randomUUID());
            n.setUserId(ownerUserId);
            n.setKind("momentum_suggestion");
            n.setTitle("New campaign suggestion: " + triggerWire.replace('_', ' '));
            n.setBody(why);
            n.setLink("/marketing"); // Momentum tab
            notifications.save(n);
        } catch (Exception notifyEx) {
            // Best-effort: this inner REQUIRES_NEW transaction rolls back on failure;
            // the caller's suggestion transaction is unaffected.
            log.warn("Momentum: notification write failed for org {}: {}", orgId, notifyEx.getMessage());
        }
    }
}
