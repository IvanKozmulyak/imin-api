package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every number the model writes must be one it was given, read as the four locales write it (1,320 / 1 320 / 0,20);
 * dates count only as whole dates, and a range end only inside its own "low–high" range.
 */
final class SummaryNumbers {

    // Digit groups joined by one '.', ',' or a no-break / narrow no-break space (thousands in fr and uk).
    private static final Pattern TOKEN = Pattern.compile("\\d+(?:[.,\\u00A0\\u202F]\\d+)*");
    private static final Pattern PLAIN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern DMY_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?!\\d)");
    private static final String DASHES = "-\\u2010\\u2011\\u2012\\u2013\\u2014\\u2212";
    private static final Pattern RANGE = Pattern.compile(
            "(" + TOKEN.pattern() + ")\\s*%?\\s*[" + DASHES + "]\\s*(" + TOKEN.pattern() + ")");
    private static final Map<String, Month> MONTHS = monthNames();
    private static final Pattern DAY_MONTH;
    private static final Pattern MONTH_DAY;

    static {
        String names = String.join("|", MONTHS.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).toList());
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        DAY_MONTH = Pattern.compile("(?<!\\d)(\\d{1,2})(?:er|st|nd|rd|th)?\\s+(?:de\\s+)?(" + names
                + ")(?!\\p{L})\\.?(?:,?\\s+(?:de\\s+)?(\\d{4})(?!\\d))?", flags);
        MONTH_DAY = Pattern.compile("(?<!\\p{L})(" + names + ")(?!\\p{L})\\.?\\s+(\\d{1,2})(?!\\d)"
                + "(?:st|nd|rd|th)?(?:,?\\s+(\\d{4})(?!\\d))?", flags);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private SummaryNumbers() {}

    /**
     * What the model may write. {@code scalars} are standalone fields, {@code rangeEnds} the low/high of a pair;
     * a pair's midpoint is never added, so it passes only when it is itself a field.
     */
    record Allowed(Set<BigDecimal> scalars, Set<BigDecimal> rangeEnds, Set<List<BigDecimal>> pairs,
                   Set<LocalDate> dates) {
        boolean has(BigDecimal v) {
            return scalars.contains(v) || rangeEnds.contains(v);
        }
    }

    /** Reads the JSON the model is sent; numbers use '.' as the decimal point, dates are ISO strings. */
    static Allowed allowed(String inputJson) {
        Allowed a = new Allowed(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
        try {
            walk(JSON.readTree(inputJson), a);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("summary input is not JSON", e);
        }
        return a;
    }

    private static void walk(JsonNode n, Allowed a) {
        if (n.isObject()) {
            Set<String> paired = new HashSet<>();
            pair(n, "low", "high", a, paired);
            pair(n, "lowPct", "highPct", a, paired);
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!paired.contains(f.getKey())) walk(f.getValue(), a);
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) walk(c, a);
        } else if (n.isNumber()) {
            a.scalars().add(norm(n.decimalValue()));
        } else if (n.isTextual()) {
            String s = n.asText();
            Matcher d = ISO_DATE.matcher(s);
            if (d.matches()) {
                LocalDate date = date(d.group(1), d.group(2), d.group(3));
                if (date != null) a.dates().add(date);
                return;
            }
            Matcher m = PLAIN.matcher(s);
            while (m.find()) a.scalars().add(norm(new BigDecimal(m.group())));
        }
    }

    private static void pair(JsonNode n, String lowKey, String highKey, Allowed a, Set<String> paired) {
        JsonNode lo = n.get(lowKey);
        JsonNode hi = n.get(highKey);
        if (lo == null || hi == null || !lo.isNumber() || !hi.isNumber()) return;
        BigDecimal l = norm(lo.decimalValue());
        BigDecimal h = norm(hi.decimalValue());
        a.pairs().add(List.of(l, h));
        a.rangeEnds().add(l);
        a.rangeEnds().add(h);
        paired.add(lowKey);
        paired.add(highKey);
    }

    /** The numbers and dates in {@code text} that the input does not allow; empty when the text passes. */
    static List<String> invented(String text, Allowed allowed) {
        List<String> bad = new ArrayList<>();
        StringBuilder rest = new StringBuilder(text);
        for (Pattern p : List.of(ISO_DATE, DMY_DATE, DAY_MONTH, MONTH_DAY)) {
            Matcher m = p.matcher(rest.toString());
            while (m.find()) {
                if (!dateAllowed(p, m, allowed.dates())) bad.add(m.group().trim());
                for (int i = m.start(); i < m.end(); i++) rest.setCharAt(i, ' ');
            }
        }
        String masked = rest.toString();
        Set<Integer> inRange = new HashSet<>();
        Matcher r = RANGE.matcher(masked);
        while (r.find()) {
            if (isPair(r.group(1), r.group(2), allowed.pairs())) {
                inRange.add(r.start(1));
                inRange.add(r.start(2));
            }
        }
        Matcher m = TOKEN.matcher(masked);
        while (m.find()) {
            if (!passes(m.group(), inRange.contains(m.start()), allowed)) bad.add(m.group());
        }
        return bad;
    }

    private static boolean passes(String token, boolean inRange, Allowed allowed) {
        for (BigDecimal v : readings(token)) {
            // A range end written alone reads as a single figure the plan never gave.
            if (allowed.scalars().contains(v) || (inRange && allowed.rangeEnds().contains(v))) return true;
        }
        return false;
    }

    private static boolean isPair(String low, String high, Set<List<BigDecimal>> pairs) {
        for (BigDecimal l : readings(low)) {
            for (BigDecimal h : readings(high)) {
                if (pairs.contains(List.of(l, h))) return true;
            }
        }
        return false;
    }

    private static boolean dateAllowed(Pattern p, Matcher m, Set<LocalDate> dates) {
        if (p == ISO_DATE) return dates.contains(date(m.group(1), m.group(2), m.group(3)));
        if (p == DMY_DATE) {
            return dates.contains(date(m.group(3), m.group(2), m.group(1)))
                    || dates.contains(date(m.group(3), m.group(1), m.group(2)));
        }
        String day = p == DAY_MONTH ? m.group(1) : m.group(2);
        Month month = MONTHS.get((p == DAY_MONTH ? m.group(2) : m.group(1)).toLowerCase(Locale.ROOT));
        String year = m.group(3);
        for (LocalDate d : dates) {
            if (d.getMonth() == month && d.getDayOfMonth() == Integer.parseInt(day)
                    && (year == null || d.getYear() == Integer.parseInt(year))) return true;
        }
        return false;
    }

    private static LocalDate date(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    /** Full, standalone and short month names in the four summary locales, lower-cased. */
    private static Map<String, Month> monthNames() {
        Map<String, Month> out = new HashMap<>();
        for (String tag : List.of("en", "es", "fr", "uk")) {
            Locale loc = Locale.forLanguageTag(tag);
            for (Month mo : Month.values()) {
                for (TextStyle s : List.of(TextStyle.FULL, TextStyle.FULL_STANDALONE, TextStyle.SHORT,
                        TextStyle.SHORT_STANDALONE)) {
                    String name = mo.getDisplayName(s, loc).toLowerCase(Locale.ROOT).replace(".", "");
                    if (name.length() >= 3 && !name.chars().allMatch(Character::isDigit)) out.putIfAbsent(name, mo);
                }
            }
        }
        return Map.copyOf(out);
    }

    static List<BigDecimal> readings(String token) {
        List<BigDecimal> out = new ArrayList<>();
        String t = token.replace(" ", "").replace(" ", "");
        String[] groups = t.split("[.,]");
        if (groups.length == 1) {
            out.add(norm(new BigDecimal(t)));
            return out;
        }
        if (groups.length == 2) {
            out.add(norm(new BigDecimal(groups[0] + "." + groups[1])));
        }
        boolean thousands = true;
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3) thousands = false;
        }
        if (thousands) out.add(norm(new BigDecimal(String.join("", groups))));
        return out;
    }

    private static BigDecimal norm(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
