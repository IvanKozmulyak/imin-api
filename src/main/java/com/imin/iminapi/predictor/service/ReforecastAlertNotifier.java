package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trajectory-change alerts (spec §4.2, task 86cav479r). Rides the same storage + channel as
 * {@code SalesMilestoneNotifier}: one in-app {@link Notification} row for the organizer user who
 * created the event. Dashboard channel ONLY — email is a deliberate later cut (task: "Email
 * channel: NOT in this task").
 *
 * <p><b>Exactly once per crossing.</b> The no-spam guarantee lives in the caller
 * ({@code ReforecastService}): a notification is emitted only when the newly-computed projection
 * band differs from the previous re-forecast's band. Repeated recomputes inside the same band
 * compare equal and emit nothing; a recovery (band moves back) is just another crossing and
 * emits exactly one more. This component only renders the copy and writes the row.
 *
 * <p>Copy is honest range-wording: it states the OLD band and the NEW band and the projected
 * RANGE — never "will", never a bare number.
 */
@Component
public class ReforecastAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReforecastAlertNotifier.class);

    private final NotificationRepository notifications;

    public ReforecastAlertNotifier(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** Emit exactly one dashboard notification for a band crossing old → new. */
    public void notifyBandChange(Event event, ProjectionBand oldBand, ProjectionBand newBand,
                                 ReforecastResult result) {
        String eventName = event.getName() == null || event.getName().isBlank() ? "your event" : event.getName();

        Notification n = new Notification();
        n.setUserId(event.getCreatedBy());
        n.setKind("predictor.trajectory." + newBand.wire().toLowerCase());
        n.setTitle("Sales trajectory changed for " + eventName);
        n.setBody(body(oldBand, newBand, result));
        n.setLink("/events/" + event.getId());
        notifications.save(n);

        log.info("[reforecast-alert] event {} band {} -> {} (one dashboard notification)",
                event.getId(), oldBand, newBand);
    }

    private static String body(ProjectionBand oldBand, ProjectionBand newBand, ReforecastResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Was ").append(oldBand.phrase()).append("; now ").append(newBand.phrase()).append('.');
        if (result != null && result.projectedFinalRange() != null) {
            sb.append(" Projected ").append(result.projectedFinalRange().low())
                    .append("–").append(result.projectedFinalRange().high()).append(" sold.");
        }
        return sb.toString();
    }
}
