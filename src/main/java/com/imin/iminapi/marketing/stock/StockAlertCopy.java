package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.model.StockAlertKind;

/** The deterministic EN/ES/FR/UK draft of a stock-alert email; every number comes from the stock tokens at send. */
public final class StockAlertCopy {

    /** A draft's name, subject, preheader and body. */
    public record Draft(String name, String subject, String preheader, String bodyMd) {}

    private StockAlertCopy() {}

    public static Draft draft(StockAlertKind kind, String eventName, String tierName, String locale) {
        String lang = EmailLocale.normalize(locale);
        String event = eventName == null ? "" : eventName.trim();
        String tier = tierName == null ? "" : tierName.trim();
        String name = EmailLocale.choose(lang, "Stock alert", "Alerta de entradas", "Alerte billets", "Сповіщення про квитки")
                + " · " + tier + " · " + event;
        String tail = "\n\n{{stock_facts}}\n\n{{tickets_button}}\n";
        if (kind == StockAlertKind.LAST_TICKETS) {
            return new Draft(name,
                    EmailLocale.choose(lang,
                            "Last {{tier_name}} tickets for " + event,
                            "Últimas entradas {{tier_name}} para " + event,
                            "Derniers billets {{tier_name}} pour " + event,
                            "Останні квитки {{tier_name}} на " + event),
                    EmailLocale.choose(lang,
                            "Tickets left: {{tickets_left}}",
                            "Entradas disponibles: {{tickets_left}}",
                            "Billets restants : {{tickets_left}}",
                            "Залишок квитків: {{tickets_left}}"),
                    EmailLocale.choose(lang,
                            "A quick update on tickets for " + event + ":",
                            "Novedades sobre las entradas de " + event + ":",
                            "Point rapide sur les billets pour " + event + " :",
                            "Коротко про квитки на " + event + ":") + tail);
        }
        return new Draft(name,
                EmailLocale.choose(lang,
                        event + ": {{tier_name}} on sale until {{price_change_at}}",
                        event + ": {{tier_name}} a la venta hasta el {{price_change_at}}",
                        event + " : {{tier_name}} en vente jusqu’au {{price_change_at}}",
                        event + ": {{tier_name}} у продажу до {{price_change_at}}"),
                EmailLocale.choose(lang,
                        "Next price: {{price_next}}",
                        "Siguiente precio: {{price_next}}",
                        "Prix suivant : {{price_next}}",
                        "Наступна ціна: {{price_next}}"),
                EmailLocale.choose(lang,
                        "A quick update on prices for " + event + ":",
                        "Novedades sobre los precios de " + event + ":",
                        "Point rapide sur les prix pour " + event + " :",
                        "Коротко про ціни на " + event + ":") + tail);
    }
}
