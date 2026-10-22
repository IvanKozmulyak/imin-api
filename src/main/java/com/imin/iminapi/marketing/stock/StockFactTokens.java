package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.model.StockAlertKind;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stock tokens of a stock-alert email ({@code {{stock_facts}}}, tickets_left, tier_name, price_now, price_next,
 * price_change_at), filled from {@link StockFacts} in the campaign language, the event zone and currency; prices all-in.
 */
public final class StockFactTokens {

    public static final String STOCK_FACTS = "stock_facts";
    public static final String TICKETS_LEFT = "tickets_left";
    public static final String TIER_NAME = "tier_name";
    public static final String PRICE_NOW = "price_now";
    public static final String PRICE_NEXT = "price_next";
    public static final String PRICE_CHANGE_AT = "price_change_at";

    private static final Pattern ANY = Pattern.compile(
            "\\{\\{\\s*(stock_facts|tickets_left|tier_name|price_now|price_next|price_change_at)\\s*\\}\\}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FACTS_BLOCK = Pattern.compile("\\{\\{\\s*stock_facts\\s*\\}\\}", Pattern.CASE_INSENSITIVE);

    private StockFactTokens() {}

    /** True when {@code text} holds any stock token. */
    public static boolean containsStockToken(String text) {
        return text != null && ANY.matcher(text).find();
    }

    /** True when the body places the facts block. */
    public static boolean hasFactsBlock(String bodyMd) {
        return bodyMd != null && FACTS_BLOCK.matcher(bodyMd).find();
    }

    /** Every token's text for these facts. */
    public static Map<String, String> values(StockAlertKind kind, StockFacts f, String locale) {
        String lang = EmailLocale.normalize(locale);
        Locale loc = javaLocale(lang);
        Map<String, String> v = new LinkedHashMap<>();
        v.put(TICKETS_LEFT, NumberFormat.getIntegerInstance(loc).format(f.remaining()));
        v.put(TIER_NAME, f.tierName() == null ? "" : f.tierName());
        v.put(PRICE_NOW, money(f.priceAllInMinor(), f.currency(), loc));
        v.put(PRICE_NEXT, f.nextPriceAllInMinor() == null ? "" : money(f.nextPriceAllInMinor(), f.currency(), loc));
        v.put(PRICE_CHANGE_AT, f.closesAt() == null ? "" : date(f.closesAt(), f.timezone(), lang));
        v.put(STOCK_FACTS, factsBlock(kind, f, lang, loc));
        return v;
    }

    /** {@code text} with each stock token replaced from {@code values}; a token without a value is removed. Null map: unchanged. */
    public static String apply(String text, Map<String, String> values) {
        if (text == null || text.isEmpty() || values == null) return text;
        Matcher m = ANY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = values.getOrDefault(m.group(1).toLowerCase(Locale.ROOT), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "Sat 7 Nov, 00:30" in the event's zone (UTC when blank or unknown) and the campaign language. */
    public static String date(Instant at, String zoneId, String locale) {
        return DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", javaLocale(EmailLocale.normalize(locale)))
                .withZone(zone(zoneId))
                .format(at);
    }

    private static String factsBlock(StockAlertKind kind, StockFacts f, String lang, Locale loc) {
        String tier = f.tierName() == null ? "" : f.tierName();
        String checked = date(f.checkedAt(), f.timezone(), lang);
        if (kind == StockAlertKind.LAST_TICKETS) {
            String left = left(f.remaining(), lang, loc);
            return switch (lang) {
                case "es" -> "**" + tier + "**: " + left + " (consultado el " + checked + ").";
                case "fr" -> "**" + tier + "** : " + left + " (vérifié le " + checked + ").";
                case "uk" -> "**" + tier + "**: " + left + " (перевірено " + checked + ").";
                default -> "**" + tier + "**: " + left + " (checked " + checked + ").";
            };
        }
        String now = money(f.priceAllInMinor(), f.currency(), loc);
        String next = f.nextPriceAllInMinor() == null ? "" : money(f.nextPriceAllInMinor(), f.currency(), loc);
        String nextName = f.nextTierName() == null ? "" : f.nextTierName();
        String at = f.closesAt() == null ? "" : date(f.closesAt(), f.timezone(), lang);
        return switch (lang) {
            case "es" -> "**" + tier + "** a " + now + " se vende hasta el " + at + ". Después, **" + nextName
                    + "** cuesta " + next + ". Precios con gastos de gestión incluidos, consultados el " + checked + ".";
            case "fr" -> "**" + tier + "** à " + now + " est en vente jusqu’au " + at + ". Ensuite, **" + nextName
                    + "** coûte " + next + ". Prix frais de service inclus, vérifiés le " + checked + ".";
            case "uk" -> "**" + tier + "** за " + now + " продається до " + at + ". Потім **" + nextName
                    + "** коштує " + next + ". Ціни із сервісним збором, перевірено " + checked + ".";
            default -> "**" + tier + "** at " + now + " is on sale until " + at + ". After that, **" + nextName
                    + "** costs " + next + ". Prices include the booking fee, checked " + checked + ".";
        };
    }

    /** "12 tickets left" with the language's plural forms (UK one/few/many, FR 0 and 1 singular). */
    static String left(int n, String lang, Locale loc) {
        String num = NumberFormat.getIntegerInstance(loc).format(n);
        return switch (lang) {
            case "es" -> n == 1 ? "queda 1 entrada" : "quedan " + num + " entradas";
            case "fr" -> n <= 1 ? "il reste " + num + " billet" : "il reste " + num + " billets";
            case "uk" -> switch (ukPlural(n)) {
                case ONE -> "залишився " + num + " квиток";
                case FEW -> "залишилося " + num + " квитки";
                default -> "залишилося " + num + " квитків";
            };
            default -> n == 1 ? "1 ticket left" : num + " tickets left";
        };
    }

    enum UkPlural { ONE, FEW, MANY }

    static UkPlural ukPlural(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return UkPlural.ONE;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return UkPlural.FEW;
        return UkPlural.MANY;
    }

    static String money(long minor, String currencyCode, Locale locale) {
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode == null ? "EUR" : currencyCode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            currency = Currency.getInstance("EUR");
        }
        NumberFormat f = NumberFormat.getCurrencyInstance(locale);
        f.setCurrency(currency);
        int digits = Math.max(0, currency.getDefaultFractionDigits());
        f.setMinimumFractionDigits(digits);
        f.setMaximumFractionDigits(digits);
        return f.format(BigDecimal.valueOf(minor, digits));
    }

    static Locale javaLocale(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "es" -> Locale.forLanguageTag("es-ES");
            case "fr" -> Locale.FRANCE;
            case "uk" -> Locale.forLanguageTag("uk-UA");
            default -> Locale.ENGLISH;
        };
    }

    private static ZoneId zone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(zoneId.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }
}
