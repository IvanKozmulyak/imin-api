package com.imin.iminapi.marketing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The counts in the bell read as words in every language the dashboard speaks. */
class CampaignOutcomeTextTest {

    @ParameterizedTest(name = "{0} sent {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "en|1|1 email sent", "en|2|2 emails sent", "en|5|5 emails sent",
            "es|1|1 correo enviado", "es|2|2 correos enviados", "es|5|5 correos enviados",
            "fr|1|1 e-mail envoyé", "fr|2|2 e-mails envoyés", "fr|5|5 e-mails envoyés",
            "uk|1|Надіслано: 1", "uk|2|Надіслано: 2", "uk|5|Надіслано: 5",
    })
    void sentCountsReadAsWordsInEveryLocale(String locale, long n, String expected) {
        assertThat(CampaignOutcomeText.of(locale, CampaignEnded.SENT, null, "Spring", n, 0, 0, true).body()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} not sent {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "en|1|1 not sent", "en|2|2 not sent", "en|5|5 not sent",
            "es|1|1 no enviado", "es|2|2 no enviados", "es|5|5 no enviados",
            "fr|1|1 non envoyé", "fr|2|2 non envoyés", "fr|5|5 non envoyés",
            "uk|1|Не надіслано: 1", "uk|2|Не надіслано: 2", "uk|5|Не надіслано: 5",
    })
    void failedCountsReadAsWordsInEveryLocale(String locale, long n, String expected) {
        String body = CampaignOutcomeText.of(locale, CampaignEnded.FAILED, "send_failed", "Spring", 0, n, 0, true).body();
        assertThat(body).endsWith(expected);
    }

    @ParameterizedTest
    @CsvSource({"en,Campaign failed: Spring", "es,Campaña fallida: Spring", "fr,Campagne en échec\u00a0: Spring",
            "uk,Кампанія не завершилась: Spring"})
    void theTitleIsInTheRecipientsLanguage(String locale, String expected) {
        assertThat(CampaignOutcomeText.of(locale, CampaignEnded.FAILED, "send_failed", "Spring", 0, 0, 0, true).title())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} retryable={2}")
    @CsvSource(delimiter = '|', value = {
            "en|send_failed|true|Sending stopped on an error. You can retry it.",
            "en|send_failed|false|Sending stopped on an error. It can't be retried — duplicate it to try again.",
            "es|send_failed|false|El envío se detuvo por un error. No se puede reintentar: duplícala para volver a intentarlo.",
            "fr|send_failed|false|L'envoi s'est arrêté sur une erreur. Elle ne peut pas être relancée\u00a0: dupliquez-la pour réessayer.",
            "uk|send_failed|false|Надсилання зупинилося через помилку. Повторити не можна — дублюйте кампанію, щоб спробувати знову.",
            "en|legal_identity_missing|true|Add your organization's legal name and contact in Settings, then retry.",
            "en|legal_identity_missing|false|Add your organization's legal name and contact in Settings. It can't be retried — duplicate it to try again.",
            "es|legal_identity_missing|false|Añade la razón social y el contacto de tu organización en Ajustes. No se puede reintentar: duplícala para volver a intentarlo.",
            "fr|legal_identity_missing|false|Ajoutez la raison sociale et le contact de votre organisation dans les paramètres. Elle ne peut pas être relancée\u00a0: dupliquez-la pour réessayer.",
            "uk|legal_identity_missing|false|Додайте юридичну назву та контакт організації в налаштуваннях. Повторити не можна — дублюйте кампанію, щоб спробувати знову.",
    })
    void aFailureOnlyPromisesARetryTheServerWouldAccept(String locale, String code, boolean retryable, String expected) {
        assertThat(CampaignOutcomeText.of(locale, CampaignEnded.FAILED, code, "Spring", 0, 0, 0, retryable).body())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} skipped {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "en|1|Nobody was emailed: 1 skipped", "en|7|Nobody was emailed: 7 skipped",
            "es|1|No se envió ningún correo: 1 omitido", "es|7|No se envió ningún correo: 7 omitidos",
            "fr|1|Aucun e-mail envoyé\u00a0: 1 ignoré", "fr|7|Aucun e-mail envoyé\u00a0: 7 ignorés",
            "uk|7|Нікому не надіслано: пропущено 7",
    })
    void aCampaignThatEmailedNobodySaysSoAndCountsTheSkipped(String locale, long skipped, String expected) {
        assertThat(CampaignOutcomeText.of(locale, CampaignEnded.SENT, null, "Spring", 0, 0, skipped, true).body())
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"3,0", "3,2"})
    void skippedRowsDoNotReplaceTheSentCounts(long sent, long notSent) {
        assertThat(CampaignOutcomeText.of("en", CampaignEnded.SENT, null, "Spring", sent, notSent, 5, true).body())
                .doesNotContain("Nobody");
    }

    /** A refused send is fixed by IMIN, so the notice sends the organizer to support and claims no working retry. */
    @ParameterizedTest(name = "{0} retryable={1}")
    @CsvSource(delimiter = '|', value = {
            "en|true|IMIN's email service refused the send. Write to support@imin.wtf; you can retry once it's fixed.",
            "en|false|IMIN's email service refused the send. Write to support@imin.wtf. It can't be retried — duplicate it to try again.",
            "es|true|El servicio de email de IMIN rechazó el envío. Escribe a support@imin.wtf; podrás reintentarlo cuando esté resuelto.",
            "fr|true|Le service d'e-mail d'IMIN a refusé l'envoi. Écrivez à support@imin.wtf\u00a0; vous pourrez relancer une fois le problème réglé.",
            "uk|false|Поштовий сервіс IMIN відхилив надсилання. Напишіть на support@imin.wtf. Повторити не можна — дублюйте кампанію, щоб спробувати знову.",
    })
    void aRefusedSendSendsTheOrganizerToSupport(String locale, boolean retryable, String expected) {
        String body = CampaignOutcomeText.of(locale, CampaignEnded.FAILED, "provider_rejected", "Spring", 0, 250, 0, retryable).body();
        assertThat(body).startsWith(expected.replace("\\u00a0", "\u00a0"));
        assertThat(body).doesNotContain("You can retry it");
    }
}
