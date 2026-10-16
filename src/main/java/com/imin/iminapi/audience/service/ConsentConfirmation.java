package com.imin.iminapi.audience.service;

import java.util.Set;

/**
 * Consent sources where anyone could type the address (door QR, survey, imin.wtf follow): the record waits for the
 * address to be confirmed before it counts for SendGateService or ConsentGate.
 */
public final class ConsentConfirmation {

    public static final Set<String> SOURCES = Set.of("door_qr", "survey", "site_follow");

    private ConsentConfirmation() {}

    public static boolean required(String source) {
        return source != null && SOURCES.contains(source);
    }
}
