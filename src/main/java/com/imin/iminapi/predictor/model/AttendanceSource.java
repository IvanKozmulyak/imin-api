package com.imin.iminapi.predictor.model;

/**
 * Provenance of an outcome's attendance figure (spec §6.1). The record states its
 * own imperfection instead of lying:
 * <ul>
 *   <li>{@link #SCANS} — door-scan redemption count (tickets in state 'redeemed').
 *       The truth when the Door PWA was used.</li>
 *   <li>{@link #SALES} — fallback to tickets-sold when NO scans exist for the event
 *       (Door PWA v1 shipped online-only, so scan coverage is partial).</li>
 * </ul>
 */
public enum AttendanceSource {
    SCANS, SALES;

    public String wire() { return name().toLowerCase(); }
}
