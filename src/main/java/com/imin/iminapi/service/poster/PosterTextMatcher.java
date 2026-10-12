package com.imin.iminapi.service.poster;

import com.imin.iminapi.service.poster.PosterTextValidationClient.Seen;
import com.imin.iminapi.service.poster.PosterTextValidationClient.Transcript;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides missing and extra text from a verbatim transcript. The vision model only reads; every
 * comparison happens here, so a misspelled or re-cased word can never be waved through as "near".
 *
 * <p>A listed string matches when it is drawn exactly as written or in ALL CAPS, and each listed
 * string may appear once: further copies and fragments are extra.
 */
public final class PosterTextMatcher {

    private static final int MAX_RUN = 4;

    private PosterTextMatcher() {}

    public record Match(List<String> missing, List<String> extra) {}

    /** NFC, one space per whitespace run, trimmed, typographic dashes and quotes made plain. Case is kept. */
    public static String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFC);
        s = s.replaceAll("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015]", "-")
                .replaceAll("[\\u2018\\u2019]", "'")
                .replaceAll("[\\u201C\\u201D]", "\"")
                .replaceAll("\\s+", " ");
        return s.trim();
    }

    static boolean matches(String seen, String target) {
        String t = normalize(target);
        String s = normalize(seen);
        return s.equals(t) || s.equals(t.toUpperCase(Locale.ROOT));
    }

    public static Match match(Transcript transcript, List<String> required, List<String> allowed) {
        List<String> entries = new ArrayList<>();
        for (Seen seen : transcript.texts()) {
            for (int i = 0; i < seen.count(); i++) entries.add(seen.text());
        }
        boolean[] used = new boolean[entries.size()];

        List<String> missing = new ArrayList<>();
        for (String target : required) {
            if (!consume(entries, used, target)) missing.add(target);
        }
        for (String target : allowed) {
            if (!required.contains(target)) consume(entries, used, target);
        }

        Map<String, Integer> leftovers = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!used[i]) leftovers.merge(normalize(entries.get(i)), 1, Integer::sum);
        }
        List<String> extra = new ArrayList<>();
        leftovers.forEach((text, n) -> extra.add(n > 1 ? text + " ×" + n : text));
        for (String d : transcript.unreadable()) extra.add("[" + d + "]");
        return new Match(missing, extra);
    }

    /** Takes the first unused entry equal to the target, else the first run of 2–4 unused entries that joins to it. */
    private static boolean consume(List<String> entries, boolean[] used, String target) {
        for (int i = 0; i < entries.size(); i++) {
            if (!used[i] && matches(entries.get(i), target)) {
                used[i] = true;
                return true;
            }
        }
        for (int start = 0; start < entries.size(); start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < entries.size() && end - start < MAX_RUN; end++) {
                if (used[end]) break;
                if (end > start) joined.append(' ');
                joined.append(entries.get(end));
                if (end > start && matches(joined.toString(), target)) {
                    for (int k = start; k <= end; k++) used[k] = true;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Text in the edge strips that is not part of the event text. A fragment of a listed string (a
     * word cut by the strip) and anything under two letters or digits is ignored; the main pass owns those.
     */
    public static List<String> edgeExtras(List<Transcript> bands, List<String> required, List<String> allowed) {
        Set<String> listed = new LinkedHashSet<>();
        for (String s : required) listed.add(normalize(s).toLowerCase(Locale.ROOT));
        for (String s : allowed) listed.add(normalize(s).toLowerCase(Locale.ROOT));

        List<String> out = new ArrayList<>();
        for (int b = 0; b < bands.size() && b < PosterEdgeBands.BAND_NAMES.size(); b++) {
            String band = PosterEdgeBands.BAND_NAMES.get(b);
            Transcript t = bands.get(b);
            for (Seen seen : t.texts()) {
                String text = normalize(seen.text());
                if (alphanumerics(text) < 2) continue;
                String lower = text.toLowerCase(Locale.ROOT);
                if (listed.stream().anyMatch(l -> l.contains(lower))) continue;
                out.add("[edge " + band + ": " + text + "]");
            }
            for (String d : t.unreadable()) out.add("[edge " + band + ": " + d + "]");
        }
        return out;
    }

    private static int alphanumerics(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) n++;
            i += Character.charCount(cp);
        }
        return n;
    }
}
