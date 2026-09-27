package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Which arm of an experiment one member was put in; needed for an honest lift. */
@Entity
@Table(name = "audience_assignments")
@IdClass(AudienceAssignment.Key.class)
@Getter
@Setter
public class AudienceAssignment {

    @Id
    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Id
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    @Column(nullable = false, length = 16)
    private String arm;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public record Key(UUID experimentId, UUID membershipId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
