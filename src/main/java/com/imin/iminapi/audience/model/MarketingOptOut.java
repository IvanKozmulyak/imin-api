package com.imin.iminapi.audience.model;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Sticky per-{@code (address, organizer, channel)} marketing opt-out — the record
 * that makes the account-level "organizer updates" toggle lawful (buyer-accounts
 * epic §6.3).
 *
 * <p><b>Why it exists.</b> {@code ConsentService.unsubscribe} writes
 * {@code status = 'unsubscribed'} identically regardless of surface, so the only
 * trace of <i>where</i> an unsubscribe came from is {@code consent_records.source}
 * on an append-only table. Without this table the master toggle would be a
 * consent-laundering device: flipping it ON would resurrect organizers the buyer
 * had explicitly unsubscribed from, without anyone ever asking them.
 *
 * <p><b>Keyed by address, not by account</b>, so an unsubscribe by someone who has
 * no imin account is still recorded and still binds if they open one later. Nothing
 * but account erasure ever deletes a row.
 *
 * <p><b>Insert-only.</b> {@link #isNew()} is hard-coded {@code true} so Spring Data
 * always {@code persist()}s rather than {@code merge()}s. That is deliberate, and it
 * is what makes the read-then-insert idiom in
 * {@code MarketingOptOutRecorder} actually work: a {@code merge()} on a losing race
 * would quietly UPDATE the winner's row (rewriting {@code source}) instead of raising
 * the duplicate-key violation the recorder treats as success. Nothing in the system
 * updates an existing row — the record says <i>that</i> an objection exists, not how
 * many times it was expressed, and the earliest {@code created_at} is the one that
 * matters if anyone ever has to answer for it.
 */
@Entity
@Table(name = "marketing_optouts")
@IdClass(MarketingOptOutId.class)
@Getter @Setter @NoArgsConstructor
public class MarketingOptOut implements Persistable<MarketingOptOutId> {

    /** {@code lower(trim(email))} — set only through {@link EmailNormalizer}. */
    @Id
    @NotNull
    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Id
    @NotNull
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** email | sms */
    @Id
    @NotNull
    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    /** Human-readable audit string, carried over from the consent record. */
    @NotNull
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Times.nowMicros();

    /**
     * Factory that keeps the address normalized and the {@code source} inside the
     * column's 32 chars by construction. {@code source} reaches this table from
     * paths that do not police its length (the SMS webhook passes one through), and
     * truncating an audit string beats losing the whole record to a value-too-long.
     */
    public static MarketingOptOut of(String rawEmail, UUID orgId, String channel, String source) {
        MarketingOptOut row = new MarketingOptOut();
        row.setEmailNormalized(EmailNormalizer.normalize(rawEmail));
        row.setOrgId(orgId);
        row.setChannel(channel);
        row.setSource(truncate(source));
        return row;
    }

    private static String truncate(String source) {
        if (source == null || source.isBlank()) return "unknown";
        return source.length() <= 32 ? source : source.substring(0, 32);
    }

    @Override
    @Transient
    public MarketingOptOutId getId() {
        return new MarketingOptOutId(emailNormalized, orgId, channel);
    }

    /** Always {@code true} — see the class javadoc; this row is never updated. */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }
}
