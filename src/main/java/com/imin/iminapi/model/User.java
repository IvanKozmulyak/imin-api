package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String email;

    @Column(name = "email_lower", nullable = false, unique = true)
    private String emailLower;

    @Column(nullable = false)
    private String name = "";

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(name = "avatar_initials", nullable = false, length = 2)
    private String avatarInitials = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    public void setEmail(String email) {
        this.email = email;
        this.emailLower = email == null ? null : email.toLowerCase();
    }

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        if (lastActiveAt != null) lastActiveAt = lastActiveAt.truncatedTo(ChronoUnit.MICROS);
    }
}
