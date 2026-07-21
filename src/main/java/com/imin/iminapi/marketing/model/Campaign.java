package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaigns")
@Getter
@Setter
public class Campaign {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String channel;                 // email|sms

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status = "draft";        // draft|scheduled|sending|sent|failed|canceled

    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "recipient_count")
    private Integer recipientCount;

    @Column(name = "excluded_count")
    private Integer excludedCount;

    @Column(name = "exclusion_summary")
    private String exclusionSummary;

    @Column(nullable = false)
    private short attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(nullable = false)
    private String origin = "manual";       // manual|momentum

    @Column(name = "momentum_suggestion_id")
    private UUID momentumSuggestionId;

    // email-only
    private String subject;
    private String preheader;

    /**
     * The email template this campaign renders with (V66): a builtin key
     * ('classic'|'midnight'|'poster'|'mono') or a saved org template's UUID string.
     * DB default 'classic' (never null) so legacy + minimal-input rows render branded.
     */
    @Column(name = "template_key")
    private String templateKey = "classic";

    @Column(name = "body_md")
    private String bodyMd;

    @Column(name = "html_rendered")
    private String htmlRendered;

    @Column(name = "text_rendered")
    private String textRendered;

    // sms-only (forward-compat, unused in Phase 1)
    @Column(name = "body_template")
    private String bodyTemplate;

    @Column(name = "sender_id")
    private String senderId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
