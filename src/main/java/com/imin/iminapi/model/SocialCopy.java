package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "social_copy")
@Getter
@Setter
public class SocialCopy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_event_id", nullable = false)
    private GeneratedEvent generatedEvent;

    private String platform;

    @Column(columnDefinition = "TEXT")
    private String copyText;
}
