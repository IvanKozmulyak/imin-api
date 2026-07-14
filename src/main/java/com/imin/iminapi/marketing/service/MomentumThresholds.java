package com.imin.iminapi.marketing.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * All Momentum trigger thresholds (spec §6.1 "initial thresholds, config-tunable").
 * Defaults live in application.yaml under imin.momentum.*; override via env for tuning.
 */
@Component
@ConfigurationProperties(prefix = "imin.momentum")
@Getter
@Setter
public class MomentumThresholds {

    /** Skip events whose SendGate-sendable audience is below this (spec §6.1 guardrail). */
    private int minAudienceFloor = 10;

    /** Days between two suggestions of the same trigger for the same event. */
    private int cooldownDays = 7;

    // Launch push: 48h after on-sale AND >=1 order AND sell-through < 15%.
    private int launchAfterHours = 48;
    private int launchMaxSellThroughPct = 15;

    // Mid-cycle slump: >=14 days out AND >=15% sold AND 7-day velocity < required-to-50%.
    private int slumpMinDaysOut = 14;
    private int slumpMinSellThroughPct = 15;
    private int slumpTargetPct = 50;

    // Last-72-hours urgency: 72h before start AND sell-through in [30, 90].
    private int urgencyBeforeHours = 72;
    private int urgencyMinSellThroughPct = 30;
    private int urgencyMaxSellThroughPct = 90;
}
