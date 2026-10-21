package com.imin.iminapi.marketing.service;

import com.imin.iminapi.email.EmailLocale;

/** Default language for a new campaign, and the language name AI prompts use. */
public final class CampaignLocale {

    private CampaignLocale() {}

    /** Org country FR/MC gives fr, UA uk, ES es; otherwise the dashboard language, otherwise en. */
    public static String defaultFor(String orgCountry, String userLocale) {
        String country = orgCountry == null ? "" : orgCountry.trim().toUpperCase(java.util.Locale.ROOT);
        switch (country) {
            case "FR", "MC": return "fr";
            case "UA": return "uk";
            case "ES": return "es";
            default: break;
        }
        String user = EmailLocale.normalizeOrNull(userLocale);
        return user == null ? "en" : user;
    }

    /** English name of the language, for "Write in X" prompt rules. */
    public static String promptLanguageName(String locale) {
        return switch (EmailLocale.normalize(locale)) {
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "uk" -> "Ukrainian";
            default -> "English";
        };
    }
}
