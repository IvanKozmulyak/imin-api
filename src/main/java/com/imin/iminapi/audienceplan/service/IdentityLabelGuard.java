package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Refuses portrait text that labels people by a protected or sensitive trait (ethnicity, origin, religion, health,
 * sexuality, politics). Groups describe taste, place, age band or study/work status only.
 */
@Component
public class IdentityLabelGuard {

    /** Beyond the genres file's {@code forbidden_terms}; English and French, matched as whole words, accents ignored. */
    static final List<String> TERMS = List.of(
            // origin, ethnicity, nationality
            "ethnic", "ethnicity", "ethnicities", "race", "racial", "immigrant", "immigrants", "migrant", "migrants",
            "refugee", "refugees", "nationality", "nationalities", "diaspora", "diasporas", "arab", "arabs", "asian",
            "asians", "maghrebi", "black", "caucasian", "african", "africans", "latino", "latinos", "latina",
            "latinas", "latinx", "hispanic", "roma", "ethnique", "immigre", "immigres", "immigree", "immigrees",
            "refugie", "refugies", "maghrebin", "maghrebins", "communaute africaine",
            // religion
            "religion", "religions", "religious", "faith", "christian", "christians", "catholic", "catholics",
            "muslim", "muslims", "islamic", "jewish", "jews", "hindu", "buddhist", "church", "mosque", "synagogue",
            "halal", "kosher", "religieux", "religieuse", "musulman", "musulmans", "musulmane", "juif", "juifs",
            "juive", "chretien", "chretiens", "catholique", "catholiques", "eglise", "mosquee",
            // sexuality, gender identity
            "gay", "gays", "lesbian", "lesbians", "queer", "lgbt", "lgbtq", "lgbtqia", "bisexual",
            "transgender", "trans", "homosexual", "homosexuals", "sexuality", "sexual orientation", "homosexuel",
            "homosexuels", "lesbienne", "lesbiennes",
            // health, disability
            "health", "illness", "disability", "disabilities", "disabled", "pregnant", "sante", "handicap",
            "handicape", "handicapes", "malade", "malades",
            // politics, unions
            "political", "politics", "left-wing", "right-wing", "trade union", "politique", "politiques",
            "syndicat", "syndique");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final Pattern pattern;

    @Autowired
    public IdentityLabelGuard(AudiencePlanLogic logic) {
        this(logic.genres().forbiddenTerms());
    }

    IdentityLabelGuard(List<String> extraTerms) {
        Set<String> all = new LinkedHashSet<>();
        for (String t : TERMS) all.add(fold(t));
        if (extraTerms != null) for (String t : extraTerms) if (t != null && !t.isBlank()) all.add(fold(t));
        // Hyphens count as word characters so "trans" stays out of "trans-border".
        String alternation = all.stream().sorted((a, b) -> b.length() - a.length()).map(Pattern::quote)
                .collect(Collectors.joining("|"));
        this.pattern = Pattern.compile("(?<![\\p{L}\\p{N}-])(?:" + alternation + ")(?![\\p{L}\\p{N}-])");
    }

    /** True when the text names people by an identity trait; null or blank is clean. */
    public boolean labelsIdentity(String text) {
        if (text == null || text.isBlank()) return false;
        return pattern.matcher(fold(text)).find();
    }

    static String fold(String s) {
        String noMarks = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFKD)).replaceAll("");
        return SPACES.matcher(noMarks.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }
}
