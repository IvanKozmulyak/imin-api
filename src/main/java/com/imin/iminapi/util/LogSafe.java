package com.imin.iminapi.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders personal data safe to log.
 *
 * <h2>Why this is not optional</h2>
 *
 * <p>{@code application-prod.yaml} routes error-level logs to Sentry. A
 * plaintext recipient address in a {@code log.error} is therefore not a line in
 * a file imin controls — it is a copy of that person's address inside a third
 * party, created on every sale, every password reset and every nightly audience
 * pass. The 2026-09 legal audit found seven such call sites; this class is the
 * single seam they all go through so the next one is a one-word change rather
 * than a fresh decision.
 *
 * <h2>What is kept, and why</h2>
 *
 * <p>{@link #email(String)} keeps the <b>domain</b> and a truncated HMAC-free
 * SHA-256 <b>correlator</b>, and throws the local part away. The domain is what
 * deliverability debugging actually needs (a whole corporate domain bouncing
 * looks nothing like one bad address), and it identifies nobody on its own. The
 * correlator is what keeps a support thread followable across log lines without
 * anyone reading an address. It is a bare digest on purpose: this is a log
 * pseudonym, not a credential, and a peppered one could not be reproduced from a
 * second service when someone is trying to join two traces.
 */
public final class LogSafe {

    private LogSafe() {}

    private static final Pattern EMAIL_IN_TEXT =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern E164_IN_TEXT = Pattern.compile("\\+\\d{7,15}");
    /**
     * {@code ?token=…}, {@code &qr=…} — the bearer credentials that ride in our
     * URLs. The leading boundary is {@code ^} as well as {@code ?}/{@code &}:
     * Sentry hands the query string over on its own, with no {@code ?} in front,
     * so anchoring only on the separators would miss the very first parameter.
     */
    private static final Pattern SECRET_QUERY_PARAM = Pattern.compile(
            "(^|[?&])((?:token|qr|code|secret|key|signature|sig)=)[^&\\s\"']+",
            Pattern.CASE_INSENSITIVE);
    /**
     * {@code /tickets/<24 opaque chars>/qr.png}, {@code /order/<token>} — the
     * ticket and order tokens are the whole credential for those pages, and they
     * travel as a path segment rather than a query parameter.
     */
    private static final Pattern SECRET_PATH_SEGMENT = Pattern.compile(
            "(/(?:tickets|order|orders|refund|unsubscribe)/)[A-Za-z0-9_.\\-]{12,}",
            Pattern.CASE_INSENSITIVE);

    /**
     * An address rendered for a log line: {@code ***@example.com#1f3c8a2b}.
     * Case- and whitespace-insensitive, so the same person always correlates.
     */
    public static String email(String raw) {
        if (raw == null || raw.isBlank()) return "<none>";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        int at = normalized.lastIndexOf('@');
        String domain = at >= 0 && at < normalized.length() - 1
                ? normalized.substring(at + 1)
                : "<no-domain>";
        return "***@" + domain + "#" + correlator(normalized);
    }

    /** A phone rendered for a log line: {@code +34***78}. Mirrors the mask BirdSmsClient already used. */
    public static String phone(String raw) {
        if (raw == null || raw.length() < 5) return "***";
        return raw.substring(0, 3) + "***" + raw.substring(raw.length() - 2);
    }

    /**
     * Scrubs free text imin did not compose — a provider's error body, an
     * exception message, a request line. Addresses, E.164 numbers and bearer
     * tokens are replaced in place; everything else survives, because the point
     * of logging an upstream body is to read the upstream's reason.
     */
    public static String redact(String text) {
        if (text == null) return null;
        String out = replaceAll(EMAIL_IN_TEXT, text, m -> email(m.group()));
        out = replaceAll(E164_IN_TEXT, out, m -> phone(m.group()));
        out = SECRET_QUERY_PARAM.matcher(out).replaceAll("$1$2[redacted]");
        out = SECRET_PATH_SEGMENT.matcher(out).replaceAll("$1[redacted]");
        return out;
    }

    /** First eight hex chars of SHA-256 — enough to correlate, far too little to reverse usefully. */
    private static String correlator(String normalized) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            return "????????";
        }
    }

    private static String replaceAll(Pattern p, String input,
                                     java.util.function.Function<Matcher, String> replacer) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }
}
