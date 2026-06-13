package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "concept")
@Getter
@Setter
public class Concept {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_event_id", nullable = false)
    private GeneratedEvent generatedEvent;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String tagline;
    private int sortOrder;

    @Column(name = "instagram_caption", columnDefinition = "TEXT")
    private String instagramCaption;

    @Column(name = "tiktok_caption", columnDefinition = "TEXT")
    private String tiktokCaption;

    @Column(name = "x_caption", columnDefinition = "TEXT")
    private String xCaption;
}
