package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "campaign_recipients")
public class CampaignRecipient {
    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "membership_id")
    private UUID membershipId;

    private String email;

    @Column(name = "phone_e164")
    private String phoneE164;

    private String status = "pending";

    @Column(name = "skip_reason")
    private String skipReason;

    @Column(name = "rendered_body")
    private String renderedBody;

    @Column(name = "segment_count")
    private Short segmentCount;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount = 0;

    @Column(name = "error_code")
    private String errorCode;
}
