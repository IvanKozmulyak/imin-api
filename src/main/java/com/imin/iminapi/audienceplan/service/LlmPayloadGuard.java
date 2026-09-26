package com.imin.iminapi.audienceplan.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Last check before an audience-plan prompt leaves for an LLM: it must hold aggregates only.
 * Fails closed; the exception names the category, never the matched text, so logs cannot leak it.
 */
@Component
public class LlmPayloadGuard {

    public enum Reason { EMAIL, PHONE, NAME, TOO_LONG }

    public static final class Rejected extends RuntimeException {
        private final Reason reason;

        Rejected(Reason reason) {
            super("LLM payload rejected: contains " + reason.name().toLowerCase(Locale.ROOT));
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    // Anchored at the start of a local-part run so a long run without '@' stays linear.
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+'-])[\\p{L}\\p{N}._%+'-]++@[\\p{L}\\p{N}-]++(?:\\.[\\p{L}\\p{N}-]++){1,20}");
    // Separators people type between digit groups; one separator per gap.
    private static final String SEP = "[\\h.\\-]";
    private static final List<Pattern> PHONES = List.of(
            // International: (+CC) / + CC / 00CC, then " - " or a space, optional "(0)" or bracketed area code.
            Pattern.compile("(?:\\+|(?<!\\d)00)\\h?[1-9]\\d{0,3}\\)?(?:\\h?-\\h?|\\h)?(?:\\(\\d{1,4}\\))?(?:"
                    + SEP + "?\\d){5,15}"),
            // FR national 0X XX XX XX XX; one consistent separator, which keeps dates like 04.10.2026 out.
            Pattern.compile("(?<![\\d.\\-/])0[1-9](" + SEP + "?)\\d{2}(?:\\1\\d{2}){3}(?!\\d)"),
            // ES / PT national 9 digits starting 6, 7 or 9: 3-3-3 or compact.
            Pattern.compile("(?<![\\d.,\\-/])[679]\\d{2}(" + SEP + "?)\\d{3}\\1\\d{3}(?![\\d.,]\\d|\\d)"),
            // UA national 0XX XXX XX XX and (0XX) XXX-XX-XX.
            Pattern.compile("(?<![\\d.\\-/])(?:\\(0\\d{2}\\)\\h?|0\\d{2}" + SEP + "?)\\d{3}" + SEP + "?\\d{2}"
                    + SEP + "?\\d{2}(?!\\d)"));
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern DASHES = Pattern.compile("[\\-\\u2010-\\u2015\\u2212]");
    private static final Pattern APOSTROPHES = Pattern.compile("[\\u2018\\u2019\\u02BC`´]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final int MIN_NAME_LENGTH = 2;
    // Bounds regex work; prompts are aggregates and far shorter.
    static final int MAX_PAYLOAD_CHARS = 20_000;

    /**
     * @param payload    the full prompt text about to be sent
     * @param knownNames display / consumer names from the org's memberships sample; null or blank entries are skipped
     * @throws Rejected if the payload holds an email, a phone number or one of the names
     */
    public void check(String payload, Collection<String> knownNames) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new Rejected(Reason.TOO_LONG);
        }
        // NFKC turns full-width '＠', digits and no-break spaces into their plain forms.
        String plain = Normalizer.normalize(payload, Normalizer.Form.NFKC);
        if (EMAIL.matcher(plain).find()) {
            throw new Rejected(Reason.EMAIL);
        }
        for (Pattern phone : PHONES) {
            if (phone.matcher(plain).find()) {
                throw new Rejected(Reason.PHONE);
            }
        }
        Pattern names = namesPattern(knownNames);
        if (names != null && names.matcher(foldName(payload)).find()) {
            throw new Rejected(Reason.NAME);
        }
    }

    private static Pattern namesPattern(Collection<String> knownNames) {
        if (knownNames == null) return null;
        // Longest first so a full name wins over a shorter one it contains.
        TreeSet<String> folded = new TreeSet<>(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (String name : knownNames) {
            if (name == null) continue;
            String n = foldName(name);
            // One-letter names would match ordinary words.
            if (n.codePointCount(0, n.length()) >= MIN_NAME_LENGTH) folded.add(n);
        }
        if (folded.isEmpty()) return null;
        String alternation = folded.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])");
    }

    static String foldName(String s) {
        String noMarks = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFKD)).replaceAll("");
        String lower = noMarks.toLowerCase(Locale.ROOT).replace("ß", "ss");
        String dashes = DASHES.matcher(lower).replaceAll(" ");
        String apostrophes = APOSTROPHES.matcher(dashes).replaceAll("'");
        return SPACES.matcher(apostrophes).replaceAll(" ").trim();
    }
}
