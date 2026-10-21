package com.imin.iminapi.marketing.service;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.model.CampaignFailureReason;

/** Localized title and body of a campaign-ended notification (en/es/fr/uk, English for anything else). */
final class CampaignOutcomeText {

    private CampaignOutcomeText() {}

    private static final String CANT_RETRY_EN = "It can't be retried — duplicate it to try again.";
    private static final String CANT_RETRY_ES = "No se puede reintentar: duplícala para volver a intentarlo.";
    private static final String CANT_RETRY_FR = "Elle ne peut pas être relancée\u00a0: dupliquez-la pour réessayer.";
    private static final String CANT_RETRY_UK = "Повторити не можна — дублюйте кампанію, щоб спробувати знову.";

    record Text(String title, String body) {}

    /** {@code retryable} is whether the organizer can still retry a failed campaign; it picks the closing advice. */
    static Text of(String locale, String outcome, String failureCode, String name, long sent, long notSent,
                   long skipped, boolean retryable) {
        boolean failed = CampaignEnded.FAILED.equals(outcome);
        String label = name == null || name.isBlank() ? "" : name.trim();
        String title = failed
                ? EmailLocale.choose(locale, "Campaign failed: ", "Campaña fallida: ", "Campagne en échec\u00a0: ",
                        "Кампанія не завершилась: ") + label
                : EmailLocale.choose(locale, "Campaign sent: ", "Campaña enviada: ", "Campagne envoyée\u00a0: ",
                        "Кампанію надіслано: ") + label;
        String counts = counts(locale, sent, notSent);
        if (!failed && sent == 0 && skipped > 0) counts = nobodyEmailed(locale, skipped);
        String body = failed ? reason(locale, failureCode, retryable) + (counts.isEmpty() ? "" : " " + counts) : counts;
        return new Text(title.trim(), body);
    }

    private static String nobodyEmailed(String locale, long n) {
        boolean one = n == 1;
        return EmailLocale.choose(locale,
                "Nobody was emailed: " + n + " skipped",
                "No se envió ningún correo: " + n + (one ? " omitido" : " omitidos"),
                "Aucun e-mail envoyé\u00a0: " + n + (one ? " ignoré" : " ignorés"),
                "Нікому не надіслано: пропущено " + n);
    }

    private static String counts(String locale, long sent, long notSent) {
        String out = "";
        if (sent > 0) out = sentPart(locale, sent);
        if (notSent > 0) out += (out.isEmpty() ? "" : " · ") + notSentPart(locale, notSent);
        return out;
    }

    private static String sentPart(String locale, long n) {
        boolean one = n == 1;
        return EmailLocale.choose(locale,
                n + (one ? " email sent" : " emails sent"),
                n + (one ? " correo enviado" : " correos enviados"),
                n + (one ? " e-mail envoyé" : " e-mails envoyés"),
                "Надіслано: " + n);
    }

    private static String notSentPart(String locale, long n) {
        boolean one = n == 1;
        return EmailLocale.choose(locale,
                n + " not sent",
                n + (one ? " no enviado" : " no enviados"),
                n + (one ? " non envoyé" : " non envoyés"),
                "Не надіслано: " + n);
    }

    private static String reason(String locale, String code, boolean retryable) {
        if (CampaignFailureReason.LEGAL_IDENTITY_MISSING.equals(code)) {
            if (!retryable) {
                return EmailLocale.choose(locale,
                        "Add your organization's legal name and contact in Settings. " + CANT_RETRY_EN,
                        "Añade la razón social y el contacto de tu organización en Ajustes. " + CANT_RETRY_ES,
                        "Ajoutez la raison sociale et le contact de votre organisation dans les paramètres. " + CANT_RETRY_FR,
                        "Додайте юридичну назву та контакт організації в налаштуваннях. " + CANT_RETRY_UK);
            }
            return EmailLocale.choose(locale,
                    "Add your organization's legal name and contact in Settings, then retry.",
                    "Añade la razón social y el contacto de tu organización en Ajustes y vuelve a intentarlo.",
                    "Ajoutez la raison sociale et le contact de votre organisation dans les paramètres, puis réessayez.",
                    "Додайте юридичну назву та контакт організації в налаштуваннях і повторіть спробу.");
        }
        if (CampaignFailureReason.SEGMENT_MISSING.equals(code)) {
            return EmailLocale.choose(locale,
                    "The audience segment no longer exists.",
                    "El segmento de la audiencia ya no existe.",
                    "Le segment d'audience n'existe plus.",
                    "Сегмент аудиторії більше не існує.");
        }
        if (CampaignFailureReason.NOTHING_SENT.equals(code)) {
            return EmailLocale.choose(locale,
                    "No email could be sent.",
                    "No se pudo enviar ningún correo.",
                    "Aucun e-mail n'a pu être envoyé.",
                    "Жодного листа не вдалося надіслати.");
        }
        if (!retryable) {
            return EmailLocale.choose(locale,
                    "Sending stopped on an error. " + CANT_RETRY_EN,
                    "El envío se detuvo por un error. " + CANT_RETRY_ES,
                    "L'envoi s'est arrêté sur une erreur. " + CANT_RETRY_FR,
                    "Надсилання зупинилося через помилку. " + CANT_RETRY_UK);
        }
        return EmailLocale.choose(locale,
                "Sending stopped on an error. You can retry it.",
                "El envío se detuvo por un error. Puedes reintentarlo.",
                "L'envoi s'est arrêté sur une erreur. Vous pouvez le relancer.",
                "Надсилання зупинилося через помилку. Можна повторити.");
    }
}
