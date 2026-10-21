package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The only place that builds the bring-a-friend URL and its WhatsApp / Telegram / mail wrappers.
 * The link is the public event page with fixed tags and never carries an order or ticket token.
 */
@Component
public class ReminderShareLink {

    public static final String UTM_SOURCE = "friends";
    public static final String UTM_MEDIUM = "share";
    public static final String UTM_CAMPAIGN = "reminder";

    public record Links(String page, String whatsapp, String telegram, String mailto) {}

    private final EmailProperties emailProps;

    public ReminderShareLink(EmailProperties emailProps) {
        this.emailProps = emailProps;
    }

    public String pageUrl(Event event) {
        String base = emailProps.getBuyerSiteBaseUrl();
        if (base == null) base = "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/e/" + event.getId() + "?utm_source=" + UTM_SOURCE
                + "&utm_medium=" + UTM_MEDIUM + "&utm_campaign=" + UTM_CAMPAIGN;
    }

    /** {@code message} is the localized invitation line placed before the URL. */
    public Links forEvent(Event event, String message, String mailSubject) {
        String page = pageUrl(event);
        String text = message + " " + page;
        return new Links(
                page,
                "https://wa.me/?text=" + enc(text),
                "https://t.me/share/url?url=" + enc(page) + "&text=" + enc(message),
                "mailto:?subject=" + enc(mailSubject) + "&body=" + enc(text));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
