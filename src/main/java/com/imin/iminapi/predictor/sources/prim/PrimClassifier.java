package com.imin.iminapi.predictor.sources.prim;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Classifies a PRIM traffic message and names its line; pure functions over the feed's own fields. */
public final class PrimClassifier {

    public static final String STRIKE = "strike";
    public static final String WORKS = "works";
    public static final String OTHER = "other";
    /** Modes a line-level object counts with; Bus is labelled too, but only its stop-level objects ever count. */
    public static final Set<String> RAIL_MODES = Set.of("Metro", "RapidTransit", "Tramway", "LocalTrain");
    // The feed has no strike cause code; strikes are PERTURBATION rows titled "Mouvement social" or "grève".
    private static final Pattern STRIKE_TITLE = Pattern.compile("mouvement social|greve");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    // Noctilien night buses run on many operators' networks, so only the short name identifies them.
    private static final Pattern NOCTILIEN = Pattern.compile("^N\\d{2,3}$");

    private PrimClassifier() {}

    /** TRAVAUX is works; PERTURBATION titled as a strike is a strike; everything else is other. */
    public static String kind(String cause, String title) {
        String c = cause == null ? "" : cause.strip().toUpperCase(Locale.ROOT);
        if (c.equals("TRAVAUX")) return WORKS;
        if (c.equals("PERTURBATION") && title != null && STRIKE_TITLE.matcher(fold(title)).find()) return STRIKE;
        return OTHER;
    }

    /**
     * {@code RER A}, {@code M3bis}, {@code T3a}, {@code Transilien H}, {@code Bus 211}, {@code Noctilien N01}; empty for
     * any other mode (funicular, shuttle, cable car), which is dropped at parse time.
     */
    public static Optional<String> label(String mode, String shortName) {
        if (mode == null || shortName == null || shortName.isBlank()) return Optional.empty();
        String s = shortName.strip();
        return switch (mode) {
            case "RapidTransit" -> Optional.of("RER " + s);
            case "Metro" -> Optional.of("M" + s);
            case "Tramway" -> Optional.of(s.toUpperCase(Locale.ROOT).startsWith("T") ? s : "T" + s);
            case "LocalTrain" -> Optional.of("Transilien " + s);
            case "Bus" -> Optional.of((NOCTILIEN.matcher(s).matches() ? "Noctilien " : "Bus ") + s);
            default -> Optional.empty();
        };
    }

    /** NFD with combining marks stripped, lower-case: "GRÈVE" and "grève" both read "greve". */
    static String fold(String s) {
        return MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
