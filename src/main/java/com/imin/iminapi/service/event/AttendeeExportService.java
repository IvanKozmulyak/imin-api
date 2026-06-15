package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the attendee CSV for an event. One row per SOLD ticket
 * ({@code state NOT IN ('refunded','revoked')}) — so the row count matches the
 * dashboard's ticketsSold tile and the Checked-in rows match its checkedIn tile.
 * "Attendee" is keyed by buyer email (the data model has no per-attendee name).
 */
@Service
public class AttendeeExportService {

    private static final String HEADER =
            "order_ref,buyer_email,tier,status,checked_in_at,price,purchased_at";

    private final EventRepository events;
    private final TicketRepository tickets;

    public AttendeeExportService(EventRepository events, TicketRepository tickets) {
        this.events = events;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public String toCsv(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (Object[] row : tickets.attendeeRows(eventId)) {
            Ticket t = (Ticket) row[0];
            String email = (String) row[1];
            String orderToken = (String) row[2];
            Instant purchasedAt = (Instant) row[3];

            boolean redeemed = Ticket.STATE_REDEEMED.equals(t.getState());
            sb.append(csv(orderToken)).append(',')
              .append(csv(email)).append(',')
              .append(csv(t.getTierName())).append(',')
              .append(redeemed ? "Checked-in" : "Issued").append(',')
              .append(t.getRedeemedAt() == null ? "" : t.getRedeemedAt().toString()).append(',')
              .append(formatMoney(t.getPriceMinor(), e.getCurrency())).append(',')
              .append(purchasedAt == null ? "" : purchasedAt.toString())
              .append("\r\n");
        }
        return sb.toString();
    }

    private static String formatMoney(int minor, String currency) {
        return currency + " " + String.format(Locale.ROOT, "%.2f", minor / 100.0);
    }

    /** RFC-4180 escaping: wrap in quotes and double any embedded quote when needed. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
