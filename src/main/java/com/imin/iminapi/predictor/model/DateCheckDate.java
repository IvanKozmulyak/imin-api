package com.imin.iminapi.predictor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One candidate date inside a date check, with its verdict and scores (V162). */
@Entity
@Table(name = "date_check_date")
@Getter
@Setter
public class DateCheckDate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "date_check_id", nullable = false)
    private UUID dateCheckId;

    @Column(name = "candidate_date", nullable = false)
    private LocalDate candidateDate;

    /** good | adjust | move | not_enough_data. */
    @Column(nullable = false, length = 16)
    private String verdict;

    /** 0..10. */
    @Column(name = "risk_score", nullable = false)
    private short riskScore;

    /** 0..10. */
    @Column(name = "opp_score", nullable = false)
    private short oppScore;

    /** Share of the question bank answered, 0..1. */
    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal coverage;

    @Column(name = "rank_order")
    private Short rankOrder;

    @Column(name = "actions_json", nullable = false, columnDefinition = "TEXT")
    private String actionsJson = "[]";
}
