package com.imin.iminapi.email;

import com.imin.iminapi.marketing.render.OrganizerIdentity;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Emails the imin team when a new organizer account becomes real; {@link #newOrganizer} is the one entry point.
 * Sent after the caller's transaction commits, and a failed send is logged, never thrown.
 */
@Service
public class TeamAlertService {

    private static final Logger log = LoggerFactory.getLogger(TeamAlertService.class);

    /** Sign-up method label for the email and password flow; other flows pass their own label. */
    public static final String EMAIL_PASSWORD = "Email and password";

    private static final String TEMPLATE = "team-alert-new-organizer";
    private static final ZoneId TEAM_ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** Display-ready snapshot taken at the call, so the send reads nothing from the database. */
    public record NewOrganizerAlert(List<String> to, String name, String email, String organization,
                                    String country, String method, Instant signedUp, Instant at) {}

    private final ApplicationEventPublisher events;
    private final TeamAlertProperties props;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final Clock clock;

    public TeamAlertService(ApplicationEventPublisher events, TeamAlertProperties props, EmailService email,
                            EmailTemplateRenderer renderer, Clock clock) {
        this.events = events;
        this.props = props;
        this.email = email;
        this.renderer = renderer;
        this.clock = clock;
    }

    /** Call once, when the account is complete; never for a log-in or an invited teammate. */
    public void newOrganizer(User user, Organization org, String method) {
        String name = Objects.toString(user.getFirstName(), "") + " " + Objects.toString(user.getLastName(), "");
        events.publishEvent(new NewOrganizerAlert(props.getEmails(), clean(name), clean(user.getEmail()),
                clean(org.getName()), clean(org.getCountry()), clean(method), user.getCreatedAt(), clock.instant()));
    }

    // fallbackExecution: with no transaction Spring would otherwise drop the event without a trace.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("ticketEmailExecutor")
    public void onNewOrganizer(NewOrganizerAlert ev) {
        for (String to : ev.to()) {
            try {
                EmailTemplateRenderer.Rendered r = renderer.render(TEMPLATE, values(ev));
                email.send(to, subject(ev), r.html(), r.text());
            } catch (RuntimeException e) {
                // One bad inbox must not stop the others, and nothing here may reach the sign-up.
                log.warn("[team-alert] new-organizer alert to {} failed: {}",
                        LogSafe.email(to), LogSafe.redact(e.getMessage()));
            }
        }
    }

    private static String subject(NewOrganizerAlert ev) {
        return "New organizer: " + ev.organization() + " (" + ev.country() + ")";
    }

    private static Map<String, String> values(NewOrganizerAlert ev) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", ev.name());
        values.put("email", ev.email());
        values.put("organization", ev.organization());
        values.put("country", ev.country());
        values.put("method", ev.method());
        values.put("signedUp", paris(ev.signedUp()));
        values.put("when", paris(ev.at()));
        return values;
    }

    private static String paris(Instant t) {
        return WHEN.format(t.atZone(TEAM_ZONE)) + " " + TEAM_ZONE.getId();
    }

    /** One line, so a user-supplied value cannot add a line to the subject or the plain-text part. */
    private static String clean(String s) {
        return OrganizerIdentity.singleLine(s);
    }
}
