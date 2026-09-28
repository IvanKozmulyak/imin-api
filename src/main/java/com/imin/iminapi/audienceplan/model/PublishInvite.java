package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** The plan segments to invite when a draft event is published; deleted once a publish run has processed them. */
@Entity
@Table(name = "audience_plan_publish_invites")
@Getter
@Setter
public class PublishInvite {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** JSON list of {@code SegmentInvitation}. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String segments;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set when a run takes the intent; null while it waits for the publish. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(nullable = false)
    private int attempts;
}
