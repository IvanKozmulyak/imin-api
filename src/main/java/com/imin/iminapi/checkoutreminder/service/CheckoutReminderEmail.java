package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.render.OrganizerIdentity;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.service.event.QuoteService;
import com.imin.iminapi.service.event.TierAvailability;
import com.imin.iminapi.stripe.StripeProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the one reminder email from live data: the tier's current price for the quantity the link
 * will pick plus the service fee ({@link QuoteService#computeFee}), the stock at send time, the
 * night's date in the event's zone, and the organizer's legal footer. Copy is IMIN's, in the
 * buyer's language; the organizer cannot edit it.
 */
@Component
public class CheckoutReminderEmail {

    static final String TEMPLATE = "checkout-reminder";
    static final String UTM = "utm_source=imin&utm_medium=email&utm_campaign=checkout_reminder";

    /** Everything one send needs; {@code linkQty} is the quantity the button picks. */
    public record Message(String from, String subject, String preheader, String html, String text,
                          int linkQty, int remaining) {}

    /** The facts behind one email, whether from a stored reminder or a preview. */
    public record Facts(Organization org, Event event, TicketTier tier, int qty, String locale,
                        boolean promoUsed, String consentProof) {}

    private final EmailTemplateRenderer renderer;
    private final MarketingEmailProperties emailProps;
    private final StripeProperties stripeProps;
    private final CheckoutReminderProperties props;

    public CheckoutReminderEmail(EmailTemplateRenderer renderer, MarketingEmailProperties emailProps,
                                 StripeProperties stripeProps, CheckoutReminderProperties props) {
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.stripeProps = stripeProps;
        this.props = props;
    }

    public Message build(Facts f, String unsubscribeUrl, boolean test) {
        String lang = EmailLocale.normalize(f.locale());
        Locale locale = CheckoutReminderSchedule.javaLocale(lang);
        String organizer = OrganizerIdentity.singleLine(f.org().displayName());
        String tierName = OrganizerIdentity.singleLine(f.tier().getName());
        int remaining = TierAvailability.remaining(f.tier());
        int linkQty = Math.max(1, Math.min(f.qty(), remaining));

        long unit = f.tier().getPriceMinor();
        long subtotal = unit * linkQty;
        long fee = unit == 0 ? 0L : QuoteService.computeFee(subtotal, linkQty,
                stripeProps.getApplicationFeeBps(), stripeProps.getApplicationFeeFixedMinor());

        Map<String, String> v = new LinkedHashMap<>();
        v.put("organizer", organizer);
        v.put("preheader", preheader(lang, tierName));
        v.put("eventName", blankTo(f.event().getName(), ""));
        v.put("eventWhen", f.event().getStartsAt() == null
                ? EmailLocale.choose(lang, "Date to be announced", "Fecha por anunciar", "Date à venir",
                        "Дату ще оголосять")
                : CheckoutReminderSchedule.eventWhen(f.event().getStartsAt(), f.event().getTimezone(), lang));
        v.put("eventWhere", where(f.event()));
        v.put("lineItem", linkQty + " × " + tierName);
        v.put("total", money(subtotal + fee, f.event().getCurrency(), locale));
        v.put("feeLabel", fee > 0 ? EmailLocale.choose(lang, "incl. service fee", "gastos de gestión incluidos",
                "frais de service inclus", "з сервісним збором") : "");
        String lowStock = remaining < props.getLowStockBelow() ? lowStock(lang, remaining) : "";
        put(v, "lowStock", lowStock);
        put(v, "clamp", linkQty < f.qty() ? clamp(lang, linkQty) : "");
        put(v, "promo", f.promoUsed() ? EmailLocale.choose(lang,
                "You used a promo code. Enter it again at checkout; the price above is without it.",
                "Usaste un código promocional. Vuelve a introducirlo al pagar; el precio de arriba no lo incluye.",
                "Tu as utilisé un code promo. Saisis-le à nouveau au paiement ; le prix ci-dessus ne le compte pas.",
                "Промокод треба ввести ще раз під час оплати: ціна вище без нього.") : "");
        v.put("finishUrl", finishUrl(f.event().getId(), f.tier().getId(), linkQty));
        v.put("consentProof", blankTo(f.consentProof(), ""));
        v.put("unsubscribeUrl", unsubscribeUrl);
        v.put("legalFooter", String.join(" · ", OrganizerIdentity.of(f.org()).footerParts()));

        EmailTemplateRenderer.Rendered rendered = renderer.render(TEMPLATE, lang, v);
        String subject = (test ? "[TEST] " : "") + subject(lang);
        return new Message(emailProps.fromHeader(f.org().displayName()), subject, v.get("preheader"),
                rendered.html(), rendered.text().replaceAll("\n{3,}", "\n\n"), linkQty, remaining);
    }

    /** {@code /e/{event}/buy?tier=&qty=} plus the campaign tag; the address is never in the link. */
    String finishUrl(UUID eventId, UUID tierId, int qty) {
        String base = emailProps.getBuyerSiteBaseUrl() == null ? "" : emailProps.getBuyerSiteBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return UriComponentsBuilder.fromUriString(base + "/e/" + eventId + "/buy")
                .queryParam("tier", tierId)
                .queryParam("qty", qty)
                .build().toUriString() + "&" + UTM;
    }

    /** A line and its show/hide style, so an absent line leaves no empty box in the html. */
    private static void put(Map<String, String> v, String key, String line) {
        v.put(key + "Line", line);
        v.put(key + "Hide", line.isEmpty() ? "display:none;" : "");
    }

    static String subject(String lang) {
        return EmailLocale.choose(lang, "You didn’t finish your order", "No terminaste tu pedido",
                "Ta commande n’est pas terminée", "Твоє замовлення не завершене");
    }

    private static String preheader(String lang, String tierName) {
        return EmailLocale.choose(lang, tierName + " is still on sale.", tierName + " sigue a la venta.",
                "Les billets " + tierName + " sont en vente.", tierName + " ще в продажу.");
    }

    /** Stock when this email was sent, with no clock time: the buyer's zone may not be the night's. */
    private static String lowStock(String lang, int remaining) {
        return switch (lang) {
            case "es" -> (remaining == 1 ? "Solo quedaba 1" : "Solo quedaban " + remaining)
                    + " cuando enviamos este email.";
            case "fr" -> "Il n’en restait plus que " + remaining + " quand nous avons envoyé cet e-mail.";
            case "uk" -> "Коли ми надсилали цей лист, лишалося тільки " + remaining + ".";
            default -> "Only " + remaining + " left when we sent this email.";
        };
    }

    private static String clamp(String lang, int linkQty) {
        return switch (lang) {
            case "es" -> "Ya no quedan tantas: el enlace elige " + linkQty + ".";
            case "fr" -> "Il n’en reste plus autant : le lien en choisit " + linkQty + ".";
            case "uk" -> "Стільки вже не лишилося: посилання вибирає " + linkQty + ".";
            default -> "Fewer are left than you picked, so the link picks " + linkQty + ".";
        };
    }

    private static String where(Event e) {
        String name = blankTo(e.getVenueName(), null);
        String city = blankTo(e.getVenueCity(), null);
        if (name != null && city != null) return name + ", " + city;
        return name != null ? name : city != null ? city : "";
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

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : OrganizerIdentity.singleLine(s);
    }
}
