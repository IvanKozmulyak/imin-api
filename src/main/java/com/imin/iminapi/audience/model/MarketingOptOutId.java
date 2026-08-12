package com.imin.iminapi.audience.model;

import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** Composite-PK class for {@link MarketingOptOut} — {@code (email_normalized, org_id, channel)}. */
@Embeddable
@Getter @Setter @NoArgsConstructor @EqualsAndHashCode
public class MarketingOptOutId implements Serializable {

    private String emailNormalized;
    private UUID orgId;
    private String channel;

    public MarketingOptOutId(String emailNormalized, UUID orgId, String channel) {
        this.emailNormalized = emailNormalized;
        this.orgId = orgId;
        this.channel = channel;
    }
}
