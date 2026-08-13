package com.imin.iminapi.audience.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite-PK class for {@link MarketingOptOut} — {@code (email_normalized, org_id, channel)}.
 *
 * <p>Bound with {@code @IdClass}, deliberately not {@code @Embeddable}: the id columns live
 * on the entity itself. Annotating it {@code @Embeddable} is harmless (Hibernate still takes
 * the {@code @IdClass} path) but reads as an {@code @EmbeddedId} that isn't there.
 */
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
