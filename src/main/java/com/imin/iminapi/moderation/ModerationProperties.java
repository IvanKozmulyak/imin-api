package com.imin.iminapi.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who may place and lift moderation holds, and where organizers appeal. Read per call.
 * An empty staff list switches the internal endpoints off (404 for everyone); a malformed id fails startup.
 */
@ConfigurationProperties(prefix = "imin.moderation")
public class ModerationProperties {

    public static final String DEFAULT_APPEAL_EMAIL = "hello@imin.wtf";

    private Set<UUID> staffUserIds = Set.of();

    private String appealEmail = DEFAULT_APPEAL_EMAIL;

    public Set<UUID> getStaffUserIds() { return staffUserIds; }

    /** Blank elements (a trailing comma) convert to null and are dropped. */
    public void setStaffUserIds(Set<UUID> staffUserIds) {
        if (staffUserIds == null) {
            this.staffUserIds = Set.of();
            return;
        }
        Set<UUID> cleaned = new HashSet<>();
        for (UUID id : staffUserIds) {
            if (id != null) cleaned.add(id);
        }
        this.staffUserIds = Set.copyOf(cleaned);
    }

    public String getAppealEmail() { return appealEmail; }

    public void setAppealEmail(String appealEmail) {
        this.appealEmail = appealEmail == null || appealEmail.isBlank() ? DEFAULT_APPEAL_EMAIL : appealEmail.trim();
    }

    public boolean isStaff(UUID userId) {
        return userId != null && staffUserIds.contains(userId);
    }
}
