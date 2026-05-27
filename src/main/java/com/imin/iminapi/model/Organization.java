package com.imin.iminapi.model;

import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false, length = 32)
    private String plan = "growth";

    @Column(name = "plan_monthly_euros", nullable = false)
    private int planMonthlyEuros = 89;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    /**
     * Stripe v2 connected account id (acct_...) — null until the org has called
     * `POST /api/v1/orgs/{orgId}/stripe/connect`. Populated once and never changed;
     * onboarding completion / payout readiness is fetched live from Stripe and is
     * deliberately NOT cached here.
     */
    @Column(name = "stripe_account_id", length = 64)
    private String stripeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stripe_connect_state", nullable = false, length = 32)
    private StripeConnectState stripeConnectState = StripeConnectState.NOT_STARTED;

    @Column(name = "stripe_payouts_enabled", nullable = false)
    private boolean stripePayoutsEnabled = false;

    @Column(name = "stripe_details_submitted", nullable = false)
    private boolean stripeDetailsSubmitted = false;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "stripe_requirements_currently_due", nullable = false, columnDefinition = "TEXT")
    private List<String> stripeRequirementsCurrentlyDue = new ArrayList<>();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "stripe_requirements_past_due", nullable = false, columnDefinition = "TEXT")
    private List<String> stripeRequirementsPastDue = new ArrayList<>();

    @Column(name = "stripe_disabled_reason", length = 128)
    private String stripeDisabledReason;

    @Column(name = "stripe_connect_status_updated_at")
    private Instant stripeConnectStatusUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @PrePersist
    void onPersist() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
        normalize();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Times.nowMicros();
        normalize();
    }

    /** ISO 3166-1 alpha-2 is uppercase by spec — enforce it on every persist to keep
     *  case-sensitive comparisons (e.g. country-aware Stripe Connect flows) safe. */
    private void normalize() {
        if (country != null) country = country.toUpperCase();
        if (currency != null) currency = currency.toUpperCase();
    }
}
