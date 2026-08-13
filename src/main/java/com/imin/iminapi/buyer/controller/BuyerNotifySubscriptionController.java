package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerNotifySubscriptionResponse;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drop alerts — "notify me when tickets drop" — on the account page (spec §4.5).
 *
 * <p>The join is on the account's <b>verified</b> addresses, the same security
 * boundary as {@code GET /buyer/orders}: an unverified
 * {@code buyer_account_emails} row is a claim anybody can make about any
 * address, and reading a stranger's watch list off one would be the same defect
 * as reading their orders.
 */
@RestController
public class BuyerNotifySubscriptionController {

    private static final String NO_STORE = "private, no-store";

    private final NotifySubscriptionRepository subscriptions;
    private final BuyerAccountEmailRepository emails;
    private final EventRepository events;

    public BuyerNotifySubscriptionController(NotifySubscriptionRepository subscriptions,
                                             BuyerAccountEmailRepository emails,
                                             EventRepository events) {
        this.subscriptions = subscriptions;
        this.emails = emails;
        this.events = events;
    }

    @GetMapping("/api/v1/buyer/notify-subscriptions")
    @Transactional(readOnly = true)
    public ResponseEntity<List<BuyerNotifySubscriptionResponse>> list(@CurrentBuyer BuyerPrincipal buyer) {
        List<String> addresses = verifiedAddresses(buyer.accountId());
        if (addresses.isEmpty()) return noStore(List.of());

        List<NotifySubscription> rows = subscriptions.findByEmailIn(addresses);
        if (rows.isEmpty()) return noStore(List.of());

        // One batched event fetch — the payload needs a name and a poster, and
        // notify_subscriptions carries neither.
        Map<UUID, Event> byId = new HashMap<>();
        for (Event event : events.findAllById(rows.stream().map(NotifySubscription::getEventId).distinct().toList())) {
            byId.put(event.getId(), event);
        }

        // Un-notified first, then newest first. Sorted here rather than in the
        // query: NULLS FIRST is not portable across H2 and Postgres.
        List<BuyerNotifySubscriptionResponse> out = rows.stream()
                .sorted(Comparator
                        .comparing((NotifySubscription s) -> s.getNotifiedAt() != null)
                        .thenComparing(NotifySubscription::getCreatedAt, Comparator.reverseOrder()))
                .map(s -> {
                    Event event = byId.get(s.getEventId());
                    return new BuyerNotifySubscriptionResponse(
                            s.getEventId(),
                            event == null ? null : event.getName(),
                            event == null ? null : event.getSlug(),
                            event == null ? null : event.getStartsAt(),
                            event == null ? null : event.getPosterUrl(),
                            s.getCreatedAt(),
                            s.getNotifiedAt());
                })
                .toList();

        return noStore(out);
    }

    /** Idempotent. "Stop watching" means every address the account holds. */
    @DeleteMapping("/api/v1/buyer/notify-subscriptions/{eventId}")
    @Transactional
    public ResponseEntity<Void> stopWatching(@CurrentBuyer BuyerPrincipal buyer,
                                             @PathVariable UUID eventId) {
        List<String> addresses = verifiedAddresses(buyer.accountId());
        if (!addresses.isEmpty()) {
            subscriptions.deleteByEventIdAndEmailIn(eventId, addresses);
        }
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }

    private List<String> verifiedAddresses(UUID accountId) {
        return emails.findByBuyerAccountIdOrderByCreatedAtAsc(accountId).stream()
                .filter(e -> e.getVerifiedAt() != null)
                .map(BuyerAccountEmail::getEmailNormalized)
                .toList();
    }

    private ResponseEntity<List<BuyerNotifySubscriptionResponse>> noStore(
            List<BuyerNotifySubscriptionResponse> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }
}
