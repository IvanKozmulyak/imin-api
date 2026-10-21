package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** A short code that opens one event's page with fixed UTM tags; one per event and purpose. */
@Entity
@Table(name = "share_links")
@Getter
@Setter
public class ShareLink implements Persistable<String> {

    public static final String PURPOSE_STORY_KIT = "story_kit";

    @Id
    @Column(length = 12)
    private String code;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 32)
    private String purpose;

    @Column(name = "utm_source", nullable = false)
    private String utmSource;

    @Column(name = "utm_medium", nullable = false)
    private String utmMedium;

    @Column(name = "utm_campaign", nullable = false)
    private String utmCampaign;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    // A new code is always an INSERT: a merge would silently re-point another event's link on a collision.
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean fresh;

    public static ShareLink create() {
        ShareLink link = new ShareLink();
        link.fresh = true;
        return link;
    }

    @Override
    public String getId() {
        return code;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void stored() {
        fresh = false;
    }
}
