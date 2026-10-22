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

    /** skip_reason of a row the campaign was canceled before it could send. */
    public static final String SKIP_CAMPAIGN_CANCELED = "campaign_canceled";
    /** skip_reason of a stock-alert row left queued when the stock fact it quoted stopped being true. */
    public static final String SKIP_FACT_CHANGED = "fact_changed";
    /** skip_reason of an address that cannot be mailed (malformed or a reserved test domain); set by the send gate. */
    public static final String SKIP_INVALID_ADDRESS = "invalid_address";
    /** skip_reason of a row whose batch outcome stayed unknown past the provider's 24 h replay window; never re-sent. */
    public static final String SKIP_DELIVERY_UNCONFIRMED = "delivery_unconfirmed";
    /** error_code of a failed row the provider refused on its own while the rest of its batch went out. */
    public static final String ERROR_PROVIDER_REJECTED = "provider_rejected";
    /** error_code of a sent row without a provider message id. */
    public static final String ERROR_NO_PROVIDER_ID = "no_provider_id";

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

    @Column(name = "segment_count")
    private Short segmentCount;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    // Only the webhook projector's UPDATEs write these three, so a whole-row save cannot revert them.
    @Column(name = "opened_at", updatable = false)
    private Instant openedAt;

    @Column(name = "clicked_at", updatable = false)
    private Instant clickedAt;

    /** Latest click on the campaign event's ticket page link (V191); a ticket click also stamps {@code clickedAt}. */
    @Column(name = "ticket_clicked_at", updatable = false)
    private Instant ticketClickedAt;

    /** When the email left for the provider (V194); null for rows that never sent, approximate for older rows. */
    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount = 0;

    /**
     * Earliest time this row may be claimed again (V117). Null = claimable now. Set when a
     * batch fails so the drain does not re-POST the identical batch to the provider within
     * milliseconds, three times over.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "error_code")
    private String errorCode;

    /**
     * Idempotency-Key of the batch this pending row is in (V202), committed before the provider call so a replay after
     * a timeout or restart carries the same key; cleared once the outcome is known. Only the sender's targeted
     * UPDATEs write it, so a whole-row save cannot revert it.
     */
    @Column(name = "send_key", updatable = false, insertable = false)
    private String sendKey;

    /** When {@link #sendKey} was stamped; a key older than the provider's replay window is expired. */
    @Column(name = "send_key_at", updatable = false, insertable = false)
    private Instant sendKeyAt;
}
