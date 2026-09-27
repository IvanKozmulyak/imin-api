package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One cached open-data figure set for a city: public aggregates only, with source and licence. */
@Entity
@Table(name = "city_open_data")
@Getter
@Setter
public class CityOpenData {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "city_key", nullable = false, length = 120)
    private String cityKey;

    @Column(nullable = false, length = 32)
    private String dataset;

    /** The source's own period: census year, academic year or reference date. */
    @Column(name = "ref_period", nullable = false, length = 32)
    private String refPeriod;

    /** Headline figure; null when the dataset has none. */
    @Column(name = "headline")
    private Long headline;

    /** JSON object with the extracted figures. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;

    @Column(nullable = false, length = 64)
    private String licence;

    @Column(nullable = false, length = 255)
    private String attribution;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
