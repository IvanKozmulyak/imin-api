package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "generated_event")
@Getter
@Setter
public class GeneratedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(columnDefinition = "TEXT")
    private String vibe;

    private String tone;
    private String genre;
    private String city;
    private LocalDate eventDate;
    private String platforms;
    private String accentColors;
    private String posterUrls;

    @Column(precision = 10, scale = 2)
    private BigDecimal suggestedMinPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal suggestedMaxPrice;

    @Column(columnDefinition = "TEXT")
    private String pricingNotes;

    @Enumerated(STRING)
    @Column(nullable = false, length = 20)
    private GeneratedEventStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "generatedEvent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<Concept> concepts = new ArrayList<>();

    @OneToMany(mappedBy = "generatedEvent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SocialCopy> socialCopies = new ArrayList<>();

    @Column(name = "org_id")
    private java.util.UUID orgId;

    @Column(columnDefinition = "TEXT")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "palette_hexes", columnDefinition = "TEXT")
    private String paletteHexes;   // comma-joined "#1a1a18,#2d5cff,..."

    @Column(name = "confidence_pct")
    private Integer confidencePct;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
