package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @PrePersist
    void onPersist() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = Times.nowMicros(); }
}
