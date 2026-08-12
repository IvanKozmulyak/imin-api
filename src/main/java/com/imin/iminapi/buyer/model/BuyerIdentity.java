package com.imin.iminapi.buyer.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A social identity linked to a {@link BuyerAccount} — {@code user_identities}
 * (V63:8-18) with the FK repointed and the email narrowed to 254, because buyer
 * addresses join {@code orders.email VARCHAR(254)} (epic §1.10).
 *
 * <p><b>Matched by subject, never by email</b> ({@code UNIQUE (provider,
 * provider_user_id)}). Matching on email breaks the moment a buyer changes the
 * address on their Google account, and it forecloses Apple entirely — Hide My
 * Email issues a {@code …@privaterelay.appleid.com} relay that matches no past
 * order. {@link #emailAtLink} is a record of what the provider said at link
 * time; it is <b>not</b> a permission and nothing joins on it.
 */
@Entity
@Table(name = "buyer_identities")
@Getter
@Setter
public class BuyerIdentity {

    public static final String PROVIDER_GOOGLE = "google";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    @NotNull
    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    /** The provider's stable subject id (OIDC {@code sub}). */
    @NotNull
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    /** Audit only — what the provider claimed when the link was made. */
    @Column(name = "email_at_link", length = 254)
    private String emailAtLink;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    void truncateTimestamps() {
        if (createdAt == null) createdAt = Times.nowMicros();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
