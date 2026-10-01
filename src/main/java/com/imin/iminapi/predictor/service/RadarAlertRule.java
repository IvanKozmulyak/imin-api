package com.imin.iminapi.predictor.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * When a radar run alerts: its verdict is worse than the baseline's (good &lt; adjust &lt; move; anything else never
 * alerts) and a finding qualifies, either a new found structured/internal risk or a found web risk seen twice running.
 */
public final class RadarAlertRule {

    /** One stored finding, in its lowercase wire values. */
    public record Signal(String questionId, String kind, String status, String sourceKind, String url) {}

    /** One scored night of a check: its verdict, risk score and findings. */
    public record Snapshot(String verdict, int riskScore, List<Signal> findings) {
        public Snapshot {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    /** What the notifier needs: the event, the radar check, the night and both ends of the change. */
    public record Alert(UUID eventId, UUID runCheckId, LocalDate night, String fromVerdict, String toVerdict,
                        int fromRisk, int toRisk) {}

    // Map.of rejects null keys, so worsens() null-checks before get().
    private static final Map<String, Integer> SEVERITY = Map.of("good", 0, "adjust", 1, "move", 2);

    private static final String FOUND = "found";
    private static final String RISK = "risk";
    private static final String WEB = "web";

    private RadarAlertRule() {}

    public static boolean shouldAlert(Snapshot baseline, Snapshot run) {
        return baseline != null && run != null && worsens(baseline.verdict(), run.verdict()) && qualifies(baseline, run);
    }

    /** Both verdicts known and the new one strictly more severe. */
    static boolean worsens(String from, String to) {
        if (from == null || to == null) return false;
        Integer f = SEVERITY.get(from);
        Integer t = SEVERITY.get(to);
        return f != null && t != null && t > f;
    }

    static boolean qualifies(Snapshot baseline, Snapshot run) {
        List<Signal> baseRisks = baseline.findings().stream().filter(RadarAlertRule::foundRisk).toList();
        for (Signal s : run.findings()) {
            if (!foundRisk(s)) continue;
            if ("structured".equals(s.sourceKind()) || "internal".equals(s.sourceKind())) {
                boolean seenBefore = baseRisks.stream().anyMatch(b -> Objects.equals(b.questionId(), s.questionId()));
                if (!seenBefore) return true;
            } else if (WEB.equals(s.sourceKind())) {
                String url = normaliseUrl(s.url());
                if (url == null) continue;
                boolean twice = baseRisks.stream().anyMatch(b -> WEB.equals(b.sourceKind())
                        && Objects.equals(b.questionId(), s.questionId()) && url.equals(normaliseUrl(b.url())));
                if (twice) return true;
            }
        }
        return false;
    }

    private static boolean foundRisk(Signal s) {
        return FOUND.equals(s.status()) && RISK.equals(s.kind());
    }

    /** host (lowercase, no leading www.) [:port] path-without-trailing-slash [?query]; null unless an http(s) URL. */
    static String normaliseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        URI u;
        try {
            u = new URI(raw.trim());
        } catch (URISyntaxException ex) {
            return null;
        }
        String scheme = u.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String host = u.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        StringBuilder key = new StringBuilder(host);
        if (u.getPort() != -1) key.append(':').append(u.getPort());
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        key.append(path);
        if (u.getRawQuery() != null) key.append('?').append(u.getRawQuery());
        return key.toString();
    }
}
