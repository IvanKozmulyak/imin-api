package com.imin.iminapi.marketing.render;

import com.imin.iminapi.email.EmailLocale;

/**
 * Every string IMIN adds around the organizer's own text in a fan email, in the campaign's
 * language. Organizer text is never translated here.
 *
 * @param addressSource {@code {src}} is replaced with the platform names
 * @param privacyUrlSuffix appended to {@code /legal/privacy}: French has its own page, others use the English anchor
 */
public record EmailShellCopy(
        String lang,
        String ticketsButton,
        String footerReason,
        String unsubscribe,
        String addressSource,
        String processor,
        String privacySeeBefore,
        String privacyLinkText,
        String privacySeeAfter,
        String privacyTextTail,
        String privacyUrlSuffix) {

    private static final EmailShellCopy EN = new EmailShellCopy("en",
            "Get tickets",
            "You received this because you are on this organizer's list.",
            "Unsubscribe",
            "This organizer received your address from {src}.",
            "IMIN processes it on the organizer's behalf",
            "; see the ", "privacy notice", " for retention and your rights.",
            "; retention and your rights: ",
            "#en");

    private static final EmailShellCopy ES = new EmailShellCopy("es",
            "Conseguir entradas",
            "Recibes este correo porque estás en la lista de este organizador.",
            "Darse de baja",
            "Este organizador recibió tu dirección a través de {src}.",
            "IMIN la trata en nombre del organizador",
            "; consulta el ", "aviso de privacidad", " para conocer la conservación y tus derechos.",
            "; conservación y tus derechos: ",
            "#en");

    private static final EmailShellCopy FR = new EmailShellCopy("fr",
            "Obtenir des billets",
            "Vous recevez cet e-mail parce que vous figurez sur la liste de cet organisateur.",
            "Se désinscrire",
            "Cet organisateur a reçu votre adresse via {src}.",
            "IMIN la traite pour le compte de l'organisateur",
            "; consultez l'", "avis de confidentialité", " pour la conservation et vos droits.",
            "; conservation et vos droits : ",
            "");

    private static final EmailShellCopy UK = new EmailShellCopy("uk",
            "Отримати квитки",
            "Ви отримали цей лист, бо ви у списку розсилки цього організатора.",
            "Відписатися",
            "Цей організатор отримав вашу адресу з {src}.",
            "IMIN обробляє її від імені організатора",
            "; див. ", "повідомлення про конфіденційність", " щодо зберігання та ваших прав.",
            "; зберігання та ваші права: ",
            "#en");

    /** The copy for a locale; null or unsupported gives English. */
    public static EmailShellCopy forLocale(String locale) {
        return switch (EmailLocale.normalize(locale)) {
            case "es" -> ES;
            case "fr" -> FR;
            case "uk" -> UK;
            default -> EN;
        };
    }

    /** The Art. 14 sentence for the given source names. */
    public String addressSourceLine(String src) {
        return addressSource.replace("{src}", src);
    }
}
