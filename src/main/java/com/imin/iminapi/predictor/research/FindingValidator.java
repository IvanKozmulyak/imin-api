package com.imin.iminapi.predictor.research;

import com.imin.iminapi.predictor.rules.Finding;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Keeps a reported web item only when the search backs it. Page checks, in order: the URL is among this run's
 * results, the quote is word for word in that result's excerpt, the site is not blocklisted, the city is named, the
 * quote holds a run of the title that names the event (stored as the name), the type is known. Per candidate night,
 * the quote must hold a date in the window (not an earlier year's page), which also picks the question. Strength is
 * clamped to 1..2; never a stop factor.
 */
public final class FindingValidator {

    public enum Type { SAME_GENRE_EVENT, BIG_EVENT }

    public enum Drop { URL_NOT_CITED, QUOTE_NOT_IN_EXCERPT, BLOCKLISTED, WRONG_CITY, NO_TITLE_IN_QUOTE, UNKNOWN_TYPE }

    /** One item as the model reported it. */
    public record Reported(String title, String url, String quote, String type, Integer strength) {}

    /** A search result the answer cited: its URL, page title and excerpt. */
    public record Cited(String url, String title, String content) {}

    /** An item that passed the page checks, {@code name} cut from its quote; no excerpt, so it may be cached. */
    public record Checked(String name, String url, String quote, Type type, int strength) {}

    public record Result(List<Checked> kept, Map<Drop, Integer> dropped) {
        public Result {
            kept = List.copyOf(kept);
            dropped = Map.copyOf(dropped);
        }
    }

    static final int NIGHT_DAYS = 1;
    static final int WEEK_DAYS = 7;
    static final int MIN_STRENGTH = 1;
    static final int MAX_STRENGTH = 2;
    /** {@code date_check_finding.quote} / {@code url} column sizes. */
    static final int MAX_QUOTE = 1000;
    static final int MAX_URL = 2048;
    static final int MAX_TITLE = 200;
    private static final int MIN_TITLE_WORD = 3;
    /** A one-word title names the event only with a word at least this long. */
    private static final int MIN_SINGLE_WORD_NAME = 4;
    private static final int MIN_NAME_WORDS = 2;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKUP = Pattern.compile("[*_#]+");
    private static final Pattern QUOTES = Pattern.compile("[’'`\"«»“”]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TRACKING = Pattern.compile("^(utm_.*|fbclid|gclid)$");
    /** Function words that say nothing about which event a title names. */
    private static final Set<String> STOP = Set.of("the", "and", "les", "des", "une", "avec", "pour", "dans", "with",
            "from", "sur", "par", "aux", "for", "est", "und", "der", "die", "das", "von", "het", "een", "los", "las",
            "del", "con", "por", "una", "feat", "live");

    private FindingValidator() {}

    /** The page checks; candidate-independent, so the kept items can be cached for other nights. */
    public static Result check(List<Reported> reported, List<Cited> cited, String city) {
        Map<String, Cited> byUrl = new LinkedHashMap<>();
        for (Cited c : cited) {
            if (c != null && c.url() != null) byUrl.putIfAbsent(normUrl(c.url()), c);
        }
        Set<String> cityWords = words(city);
        List<Checked> kept = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Map<Drop, Integer> dropped = new EnumMap<>(Drop.class);
        for (Reported r : reported) {
            if (r == null) continue;
            Drop d = null;
            Cited page = r.url() == null ? null : byUrl.get(normUrl(r.url()));
            Type type = type(r.type());
            String name = null;
            if (page == null || page.url().length() > MAX_URL) d = Drop.URL_NOT_CITED;
            else if (!verbatim(r.quote(), page.content())) d = Drop.QUOTE_NOT_IN_EXCERPT;
            else if (SearchBlocklist.blocked(page.url())) d = Drop.BLOCKLISTED;
            else if (!namesCity(city, r, page)) d = Drop.WRONG_CITY;
            else if ((name = name(r.title(), r.quote(), cityWords)) == null) d = Drop.NO_TITLE_IN_QUOTE;
            else if (type == null) d = Drop.UNKNOWN_TYPE;
            if (d != null) {
                dropped.merge(d, 1, Integer::sum);
                continue;
            }
            String quote = r.quote().trim();
            Checked c = new Checked(truncate(name, MAX_TITLE), page.url(), quote, type, clamp(r.strength()));
            // One quote counts once whatever types it was reported as; same genre is the closer match.
            Integer at = seen.putIfAbsent(normUrl(page.url()) + "|" + squash(quote), kept.size());
            if (at == null) kept.add(c);
            else if (type == Type.SAME_GENRE_EVENT && kept.get(at).type() != type) kept.set(at, c);
        }
        return new Result(kept, dropped);
    }

    /**
     * The web findings for one candidate night: a same-genre event within a night is 2.1, within a week 2.2; a big
     * event within a night is 5.3, unless it names the same event as one of that night's same-genre items from any
     * page (either name shares an event word, not the city, with the other's quote). One finding per question, as the
     * rule engine gives: the nearest date, then the strongest, so two pages on one event count once.
     */
    public static List<Finding> assign(List<Checked> items, LocalDate candidate, String city, QuestionBank bank,
                                       Instant fetchedAt) {
        Map<String, Question> web = new LinkedHashMap<>();
        for (Question q : bank.questions()) {
            if (q.source() == SourceKind.WEB) web.putIfAbsent(q.id(), q);
        }
        Set<String> cityWords = words(city);
        List<LocalDate> nearestOf = new ArrayList<>();
        List<Checked> sameGenreNight = new ArrayList<>();
        for (Checked c : items) {
            QuoteDates.Classified dates = QuoteDates.classify(c.quote(), candidate, WEEK_DAYS);
            LocalDate nearest = dates.stale() || dates.inWindow().isEmpty() ? null : dates.inWindow().get(0);
            nearestOf.add(nearest);
            if (nearest != null && c.type() == Type.SAME_GENRE_EVENT && withinNight(candidate, nearest)) {
                sameGenreNight.add(c);
            }
        }
        Map<String, Finding> byQuestion = new LinkedHashMap<>();
        Map<String, Long> deltaOf = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Checked c = items.get(i);
            LocalDate nearest = nearestOf.get(i);
            if (nearest == null) continue;
            long delta = Math.abs(ChronoUnit.DAYS.between(candidate, nearest));
            String questionId = switch (c.type()) {
                case SAME_GENRE_EVENT -> delta <= NIGHT_DAYS ? "2.1" : "2.2";
                // A big event naming one of this night's same-genre items, from any page, is not also counted as big.
                case BIG_EVENT -> delta <= NIGHT_DAYS && !namesSameEvent(c, sameGenreNight, cityWords)
                        ? "5.3" : null;
            };
            Question q = questionId == null ? null : web.get(questionId);
            if (q == null) continue;
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("name", c.name());
            facts.put("date", nearest.toString());
            Finding f = new Finding(q.id(), Kind.RISK, Finding.Status.FOUND, Math.min(c.strength(), q.maxStrength()),
                    q.weight(), SourceKind.WEB, q.window(), false, facts, c.url(), c.quote(), fetchedAt);
            Finding prev = byQuestion.get(q.id());
            if (prev == null || delta < deltaOf.get(q.id())
                    || (delta == deltaOf.get(q.id()) && f.strength() > prev.strength())) {
                byQuestion.put(q.id(), f);
                deltaOf.put(q.id(), delta);
            }
        }
        return List.copyOf(byQuestion.values());
    }

    private static boolean namesSameEvent(Checked big, List<Checked> sameGenre, Set<String> cityWords) {
        for (Checked s : sameGenre) {
            if (sameEvent(big, s, cityWords)) return true;
        }
        return false;
    }

    // ponytail: a shared generic word (club, festival) counts as the same event, so a real 5.3 can be skipped.
    private static boolean sameEvent(Checked a, Checked b, Set<String> cityWords) {
        return !Collections.disjoint(eventWords(a.name(), cityWords), eventWords(b.quote(), cityWords))
                || !Collections.disjoint(eventWords(b.name(), cityWords), eventWords(a.quote(), cityWords));
    }

    /** The folded words of {@code s} that can name an event. */
    private static Set<String> eventWords(String s, Set<String> cityWords) {
        Set<String> out = new HashSet<>();
        for (String w : wordList(s)) {
            if (namesEvent(w, cityWords)) out.add(w);
        }
        return out;
    }

    private static boolean withinNight(LocalDate candidate, LocalDate date) {
        return Math.abs(ChronoUnit.DAYS.between(candidate, date)) <= NIGHT_DAYS;
    }

    static int clamp(Integer strength) {
        int s = strength == null ? MIN_STRENGTH : strength;
        return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, s));
    }

    private static Type type(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "same_genre_event" -> Type.SAME_GENRE_EVENT;
            case "big_event" -> Type.BIG_EVENT;
            default -> null;
        };
    }

    private static boolean verbatim(String quote, String excerpt) {
        if (quote == null || quote.isBlank() || quote.trim().length() > MAX_QUOTE || excerpt == null) return false;
        return squash(excerpt).contains(squash(quote));
    }

    /** The city's words appear, in order, in the item's title, quote or URL, or the cited page's URL or title. */
    private static boolean namesCity(String city, Reported r, Cited page) {
        List<String> cityWords = wordList(city);
        if (cityWords.isEmpty()) return false;
        String needle = " " + String.join(" ", cityWords) + " ";
        String hay = " " + String.join(" ", wordList(String.join(" ", nz(r.title()), nz(r.quote()), nz(r.url()),
                nz(page.url()), nz(page.title())))) + " ";
        return hay.contains(needle);
    }

    /**
     * The longest run of consecutive title words the quote holds (folded) that has a word naming the event, cut from
     * the quote as written; null under two words, or for a one-word title under {@link #MIN_SINGLE_WORD_NAME} letters.
     */
    static String name(String title, String quote, Set<String> cityWords) {
        List<String> t = wordList(title);
        if (t.isEmpty() || quote == null) return null;
        List<String> q = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher m = WORD.matcher(quote);
        while (m.find()) {
            String w = QuoteDates.fold(m.group());
            if (w.isEmpty()) continue;
            q.add(w);
            spans.add(new int[] {m.start(), m.end()});
        }
        int best = 0;
        int bestAt = -1;
        for (int i = 0; i < t.size(); i++) {
            for (int j = 0; j < q.size(); j++) {
                int k = 0;
                while (i + k < t.size() && j + k < q.size() && t.get(i + k).equals(q.get(j + k))) k++;
                if (k > best && t.subList(i, i + k).stream().anyMatch(w -> namesEvent(w, cityWords))) {
                    best = k;
                    bestAt = j;
                }
            }
        }
        if (bestAt < 0) return null;
        if (t.size() == 1 ? q.get(bestAt).length() < MIN_SINGLE_WORD_NAME : best < MIN_NAME_WORDS) return null;
        return quote.substring(spans.get(bestAt)[0], spans.get(bestAt + best - 1)[1]);
    }

    /** Not a function word, number, date word or the city. */
    private static boolean namesEvent(String w, Set<String> cityWords) {
        return w.length() >= MIN_TITLE_WORD && !w.chars().allMatch(Character::isDigit) && !STOP.contains(w)
                && !cityWords.contains(w) && !QuoteDates.isDateWord(w);
    }

    /** Host without www, path without trailing slash, query without tracking parameters; lower case. */
    static String normUrl(String url) {
        String raw = url == null ? "" : url.trim();
        try {
            URI u = new URI(raw);
            if (u.getHost() == null) return raw.toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            String query = u.getRawQuery() == null ? "" : Arrays.stream(u.getRawQuery().split("&"))
                    .filter(p -> !TRACKING.matcher(p.split("=", 2)[0].toLowerCase(Locale.ROOT)).matches())
                    .collect(Collectors.joining("&"));
            return (host + path + (query.isEmpty() ? "" : "?" + query)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return raw.toLowerCase(Locale.ROOT);
        }
    }

    /** Accents, markdown emphasis, quote styles and spacing folded so a copied quote matches its excerpt. */
    static String squash(String s) {
        String folded = QuoteDates.fold(s == null ? "" : s);
        folded = MARKUP.matcher(folded).replaceAll("");
        folded = QUOTES.matcher(folded).replaceAll("'");
        return SPACES.matcher(folded).replaceAll(" ").trim();
    }

    private static Set<String> words(String s) {
        return new LinkedHashSet<>(wordList(s));
    }

    private static List<String> wordList(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(NON_WORD.split(QuoteDates.fold(s))).filter(w -> !w.isEmpty()).toList();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
