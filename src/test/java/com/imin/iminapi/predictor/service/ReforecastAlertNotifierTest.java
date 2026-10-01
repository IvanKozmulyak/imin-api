package com.imin.iminapi.predictor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReforecastAlertNotifierTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationPreferencesRepository prefs = mock(NotificationPreferencesRepository.class);
    private final PredictorAlertStore alerts = mock(PredictorAlertStore.class);
    private final EventRepository events = mock(EventRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RecordingEmailService mail = new RecordingEmailService();

    private Event event;
    private Organization org;
    private User creator;

    private ReforecastAlertNotifier notifier(Instant now) {
        return new ReforecastAlertNotifier(notifications, prefs, alerts, events, orgs, users, mail,
                new EmailTemplateRenderer(), new EmailProperties(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private ReforecastAlertNotifier notifier() {
        return notifier(NOW);
    }

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setId(UUID.randomUUID());
        org.setContactEmail("team@example.test");
        creator = new User();
        creator.setId(UUID.randomUUID());
        event = new Event();
        event.setId(UUID.randomUUID());
        event.setOrgId(org.getId());
        event.setCreatedBy(creator.getId());
        event.setName("Radar Night");
        when(events.findById(event.getId())).thenReturn(Optional.of(event));
        when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
        when(users.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(prefs.findById(creator.getId())).thenReturn(Optional.empty());
        when(alerts.claim(any(), any(), anyString(), any())).thenReturn(true);
        // The real store runs the in-app write inside the claim's transaction, only for the winner.
        when(alerts.claimWith(any(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(4).run();
            return true;
        });
    }

    private RadarAlertRule.Alert alert() {
        return new RadarAlertRule.Alert(event.getId(), UUID.randomUUID(), NIGHT, "good", "move", 0, 8);
    }

    private void optOut() {
        NotificationPreferences p = new NotificationPreferences();
        p.setUserId(creator.getId());
        p.setPredictorShift(false);
        when(prefs.findById(creator.getId())).thenReturn(Optional.of(p));
    }

    private Notification savedNotification() {
        ArgumentCaptor<Notification> c = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(c.capture());
        return c.getValue();
    }

    private void fire(String kind) {
        if ("band".equals(kind)) {
            notifier().notifyBandChange(event, ProjectionBand.UNDER_60, ProjectionBand.TRACKING_60_85, null);
        } else {
            notifier().notifyRadarWorsened(alert());
        }
    }

    // --- radar copy per locale ---

    private static final String EN_FOOTER =
            "These alerts follow the Predictor notification setting of the person who created this event in imin.";

    static Stream<Arguments> locales() {
        String en = "Radar Night: date check changed (Good → Move)";
        String enBody = "Thursday 15 October · Date risk 0/10 → 8/10. Open the Predictor tab to see what we found.";
        String enIntro = "We re-checked this night with the data we have today, and the verdict changed.";
        String enHeadsUp = "This is a heads-up, not a decision: open the Predictor tab to see what we found before you act.";
        return Stream.of(
                Arguments.of("en", "en", en, enBody, EN_FOOTER, enIntro, enHeadsUp),
                Arguments.of(null, "en", en, enBody, EN_FOOTER, enIntro, enHeadsUp),
                Arguments.of("de", "en", en, enBody, EN_FOOTER, enIntro, enHeadsUp),
                Arguments.of("es", "es", "Radar Night: cambió la revisión de la fecha (Buena → Cambiar)",
                        "Jueves 15 de octubre · Riesgo de la fecha 0/10 → 8/10. Abre la pestaña Predictor para ver lo que encontramos.",
                        "Estos avisos siguen la configuración de notificaciones del Predictor de la persona que creó este evento en imin.",
                        "Volvimos a revisar la fecha de tu evento con los datos que tenemos hoy y el veredicto cambió.",
                        "Es un aviso, no una decisión: abre la pestaña Predictor para ver lo que encontramos antes de actuar."),
                Arguments.of("fr", "fr", "Radar Night\u00a0: la vérification de la date a changé (Bonne → À déplacer)",
                        "Jeudi 15 octobre · Risque de date 0/10 → 8/10. Ouvrez l’onglet Prédicteur pour voir ce que nous avons trouvé.",
                        "Ces alertes suivent le réglage des notifications du Prédicteur de la personne qui a créé cet événement dans imin.",
                        "Nous avons revérifié cette soirée avec les données dont nous disposons aujourd’hui, et le verdict a changé.",
                        "C’est une alerte, pas une décision\u00a0: ouvrez l’onglet Prédicteur pour voir ce que nous avons trouvé avant d’agir."),
                Arguments.of("uk", "uk", "Radar Night: перевірка дати змінилась (Добра → Перенесіть)",
                        "Четвер, 15 жовтня · Ризик дати 0/10 → 8/10. Відкрийте вкладку «Прогноз», щоб побачити, що ми знайшли.",
                        "Ці сповіщення залежать від налаштувань сповіщень «Прогнозу» людини, яка створила цю подію в imin.",
                        "Ми повторно перевірили цей вечір за даними, які маємо сьогодні, і вердикт змінився.",
                        "Це попередження, а не рішення: відкрийте вкладку «Прогноз», щоб побачити, що ми знайшли, перш ніж діяти."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locales")
    void emailLocaleFollowsOrganizer(String locale, String lang, String title, String body, String footer,
                                     String intro, String headsUp) {
        creator.setLocale(locale);
        RadarAlertRule.Alert a = alert();

        notifier().notifyRadarWorsened(a);

        Notification n = savedNotification();
        assertThat(n.getUserId()).isEqualTo(creator.getId());
        assertThat(n.getKind()).isEqualTo("predictor.radar.worsened");
        assertThat(n.getTitle()).isEqualTo(title);
        assertThat(n.getBody()).isEqualTo(body);
        assertThat(n.getLink()).isEqualTo("/events/" + event.getId() + "/predictor");
        verify(alerts).claimWith(eq(event.getId()), eq(LocalDate.of(2026, 10, 1)), eq(PredictorAlertStore.KIND_RADAR),
                eq(a.runCheckId()), any());

        String url = "https://dashboard.imin.wtf/events/" + event.getId() + "/predictor";
        assertThat(mail.sent()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("team@example.test");
            assertThat(m.subject()).isEqualTo(title);
            assertThat(m.html()).contains("<html lang=\"" + lang + "\">").contains("href=\"" + url + "\"");
            assertThat(m.text()).contains(url).contains(title).contains("0/10 → 8/10");
            assertThat(m.text()).contains("Radar Night · " + body.substring(0, body.indexOf(" · ")));
            // The footer speaks of the event creator's setting, never the recipient's account.
            assertThat(m.html()).contains(footer);
            assertThat(m.text()).contains(footer);
            // Intro (also the preheader) and heads-up; the html writes a no-break space as &nbsp;.
            assertThat(m.html()).contains(intro.replace("\u00a0", "&nbsp;"))
                    .contains(headsUp.replace("\u00a0", "&nbsp;"));
            assertThat(m.text()).contains(intro).contains(headsUp);
        });
    }

    // --- gates ---

    @ParameterizedTest
    @ValueSource(strings = {"band", "radar"})
    void optedOutGetsNothing(String kind) {
        optOut();
        fire(kind);
        verifyNoInteractions(alerts);
        verify(notifications, never()).save(any());
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void missingPrefsRowMeansOn() {
        fire("radar");
        verify(alerts).claimWith(eq(event.getId()), any(), eq(PredictorAlertStore.KIND_RADAR), any(), any());
        verify(notifications).save(any());
        assertThat(mail.sent()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"band", "radar"})
    void lostClaimSendsNothing(String kind) {
        when(alerts.claim(any(), any(), anyString(), any())).thenReturn(false);
        doReturn(false).when(alerts).claimWith(any(), any(), anyString(), any(), any());
        fire(kind);
        verify(notifications, never()).save(any());
        assertThat(mail.sent()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void noContactEmailSendsInAppOnly(String contact) {
        org.setContactEmail(contact);
        List<ILoggingEvent> logged = capture(() -> fire("radar"));
        verify(notifications).save(any());
        assertThat(mail.sent()).isEmpty();
        assertThat(logged).filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement().satisfies(e -> assertThat(e.getFormattedMessage()).contains("in-app only"));
    }

    @Test
    void emailFailureKeepsInAppRow() {
        mail.failNextSendWith(new IllegalStateException("resend down"));
        List<ILoggingEvent> logged = capture(() ->
                assertThatCode(() -> fire("radar")).doesNotThrowAnyException());
        verify(notifications).save(any());
        assertThat(logged).filteredOn(e -> e.getLevel() == Level.WARN).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("email failed for event " + event.getId());
            assertThat(e.getThrowableProxy().getMessage()).isEqualTo("resend down");
        });
    }

    @ParameterizedTest
    @CsvSource({"not_enough_data,move", "good,not_enough_data", "good,maybe"})
    void unknownVerdictNeverClaims(String from, String to) {
        RadarAlertRule.Alert odd = new RadarAlertRule.Alert(event.getId(), UUID.randomUUID(), NIGHT, from, to, 0, 8);
        assertThatThrownBy(() -> notifier().notifyRadarWorsened(odd)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(alerts);
        verify(notifications, never()).save(any());
        assertThat(mail.sent()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prefs", "claim"})
    void bandGateFailureNeverThrows(String failing) {
        if ("prefs".equals(failing)) {
            when(prefs.findById(creator.getId())).thenThrow(new IllegalStateException("db down"));
        } else {
            when(alerts.claim(any(), any(), anyString(), any())).thenThrow(new IllegalStateException("db down"));
        }
        List<ILoggingEvent> logged = capture(() -> assertThatCode(() -> fire("band")).doesNotThrowAnyException());
        verify(notifications, never()).save(any());
        assertThat(logged).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getThrowableProxy().getMessage()).isEqualTo("db down"));
    }

    @Test
    void goneEventSendsNothing() {
        RadarAlertRule.Alert missing = new RadarAlertRule.Alert(UUID.randomUUID(), UUID.randomUUID(), NIGHT, "good",
                "move", 0, 8);
        when(events.findById(missing.eventId())).thenReturn(Optional.empty());
        notifier().notifyRadarWorsened(missing);

        event.setDeletedAt(NOW);
        notifier().notifyRadarWorsened(alert());

        verifyNoInteractions(alerts);
        verify(notifications, never()).save(any());
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void failedInAppWriteSendsNoEmail() {
        when(notifications.save(any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> fire("radar")).hasMessage("db down");
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void mutedEventGetsNoRadarAlert() {
        event.setRadarMuted(true);
        fire("radar");
        verifyNoInteractions(prefs, alerts);
        verify(notifications, never()).save(any());
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void muteLeavesBandAlerts() {
        event.setRadarMuted(true);
        fire("band");
        verify(alerts).claim(eq(event.getId()), any(), eq(PredictorAlertStore.KIND_BAND), isNull());
        assertThat(savedNotification().getKind()).isEqualTo("predictor.trajectory.tracking_60_85");
    }

    // --- copy limits ---

    @Test
    void titleFitsTheColumn() {
        event.setName("N".repeat(255));
        fire("radar");
        String title = savedNotification().getTitle();
        assertThat(title).hasSizeLessThanOrEqualTo(255).startsWith("NNNN").endsWith("…: date check changed (Good → Move)");
        assertThat(title).endsWith(" (Good → Move)");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"en,Your event", "es,Tu evento", "fr,Votre événement", "uk,Ваша подія"})
    void blankEventNameUsesFallback(String locale, String fallback) {
        creator.setLocale(locale);
        event.setName("  ");
        fire("radar");
        assertThat(savedNotification().getTitle()).startsWith(fallback);
        assertThat(mail.lastSent().text()).contains(fallback + " · ");
    }

    // --- alert day ---

    @Test
    void alertDayIsTheEventsLocalDay() {
        event.setTimezone("Europe/Paris");
        notifier(Instant.parse("2026-10-01T22:30:00Z")).notifyRadarWorsened(alert());
        verify(alerts).claimWith(eq(event.getId()), eq(LocalDate.of(2026, 10, 2)), anyString(), any(), any());
    }

    @Test
    void badZoneFallsBackToUtc() {
        event.setTimezone("Mars/Olympus");
        notifier(Instant.parse("2026-10-01T22:30:00Z")).notifyRadarWorsened(alert());
        verify(alerts).claimWith(eq(event.getId()), eq(LocalDate.of(2026, 10, 1)), anyString(), any(), any());
    }

    // --- band ---

    @Test
    void bandAlertClaimsTheDayThenWritesAsBefore() {
        notifier().notifyBandChange(event, ProjectionBand.UNDER_60, ProjectionBand.TRACKING_60_85, null);

        verify(alerts).claim(eq(event.getId()), eq(LocalDate.of(2026, 10, 1)), eq(PredictorAlertStore.KIND_BAND),
                isNull());
        Notification n = savedNotification();
        assertThat(n.getUserId()).isEqualTo(creator.getId());
        assertThat(n.getKind()).isEqualTo("predictor.trajectory.tracking_60_85");
        assertThat(n.getTitle()).isEqualTo("Sales trajectory changed for Radar Night");
        assertThat(n.getBody()).isEqualTo("Was tracking below 60% of capacity; now tracking 60–85% of capacity.");
        assertThat(n.getLink()).isEqualTo("/events/" + event.getId());
        assertThat(mail.sent()).isEmpty();
    }

    private static List<ILoggingEvent> capture(Runnable r) {
        Logger logger = (Logger) LoggerFactory.getLogger(ReforecastAlertNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            r.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
