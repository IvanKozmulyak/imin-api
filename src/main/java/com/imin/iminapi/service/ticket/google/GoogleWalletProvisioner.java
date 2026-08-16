package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.ticket.WalletEligibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Makes sure the two Google resources a save link points at actually exist: the
 * event's {@code EventTicketClass} and the ticket's {@code EventTicketObject}.
 * Returns the object id, which is all the JWT ever carries.
 *
 * <h2>One class per event, not one shared class</h2>
 *
 * <p>A class holds {@code eventName}, {@code venue} and {@code dateTime}, so a
 * single shared class would put one event's name on every ticket imin ever
 * sells. That alone settles it, but the blast radius is the reason worth
 * remembering: <b>a change to a class propagates immediately to every object
 * referencing it</b>. Per-event, a bad class edit reaches the holders of one
 * night's tickets. Shared, it reaches everyone.
 *
 * <p>The cost is real and is accepted: there is no template inheritance between
 * classes, so a change we ever want on <em>all</em> events — a different
 * {@code issuerName}, a new module — is N patches, one per event class, and
 * classes already created keep the old shape until patched. Today that is a
 * non-issue because nothing patches anything (ADR-0004), which means the honest
 * statement of the trade is: new events get the new shape, old events keep the
 * shape they were created with, and the two can silently diverge. If a
 * systematic class change is ever needed, it is a backfill job over
 * {@code events}, and it should be written as one rather than discovered as a
 * bug report about one club's ticket looking different.
 *
 * <h2>Lazily, on first use — not out of band</h2>
 *
 * <p>Three alternatives were available and each fails on something concrete.
 * <b>By hand, once per event</b> is not a system: an organizer publishing at
 * 03:00 would have a wallet button that 503s until somebody woke up.
 * <b>At boot</b> puts N network calls in the startup path and re-creates classes
 * for every event nobody is buying on every Railway restart. <b>At event
 * publish</b> is the closest miss — it would move the latency off the buyer
 * path — but it couples publishing an event to Google's availability, and an
 * event that fails to publish because a wallet API is down is a far worse
 * failure than a wallet button that is briefly slow.
 *
 * <p>Lazy also happens to be the only option that satisfies the account
 * onboarding sequence: publishing access cannot be requested until at least one
 * Passes Class exists, and that class has to be created by this code running
 * against production Google. Lazy creation means the first real save-link
 * request produces it.
 *
 * <p>The price is up to three synchronous calls inside a buyer's request — token,
 * class read, object insert. The token is cached for its lifetime, the class is
 * remembered per JVM after the first sighting, so the steady state is one call.
 * Every failure degrades to {@code 503} on that one button.
 */
@Component
public class GoogleWalletProvisioner {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletProvisioner.class);

    /**
     * How many event class ids to remember. Bounded because the natural key
     * space is "every event that ever sold a ticket", and an unbounded memo on a
     * long-lived JVM is a leak. Evicting one only costs a repeat {@code GET}.
     */
    private static final int REMEMBERED_CLASSES = 512;

    private final GoogleWalletProperties props;
    private final GoogleWalletApiClient api;
    private final EmailProperties emailProps;

    /**
     * Event classes this JVM has already seen exist and be usable. Access-ordered
     * and bounded, so it behaves as an LRU.
     *
     * <p>A {@code DRAFT} class is deliberately <b>not</b> recorded here: an
     * operator who promotes it in the console must not have to wait for a
     * restart to see the button start working.
     */
    private final Map<String, Boolean> knownClasses = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED_CLASSES;
                }
            });

    public GoogleWalletProvisioner(GoogleWalletProperties props,
                                   GoogleWalletApiClient api,
                                   EmailProperties emailProps) {
        this.props = props;
        this.api = api;
        this.emailProps = emailProps;
    }

    /**
     * Ensure the class and the object exist, and return the object id.
     *
     * @param qrPayload the canonical {@code imin1.<token>.<hmac>} string
     * @return {@code {issuerId}.tkt_{ticketToken}}
     * @throws ApiException {@code 503} when the wallet is off or Google will not
     *                      answer, {@code 409} when the ticket is not live
     */
    public String provision(Ticket ticket, Event event, Organization org, String qrPayload) {
        // Eligibility before the gate, and the ordering is deliberate. A refunded
        // ticket is refunded whether or not Google Wallet is switched on, so the
        // buyer gets 409 "this ticket was refunded" and not 503 "temporarily
        // unavailable" — the second is false, and it invites a retry that can
        // never succeed. It is the same reason the endpoint checks the token
        // before the config: what a buyer learns about their own ticket must not
        // depend on our deployment state. The check costs no I/O.
        //
        // It is here and not only at the endpoint because an object, once
        // created, outlives this request and nothing ever deletes it (ADR-0004).
        WalletEligibility.assertLive(ticket);

        // Then the gate, before anything is built or read. "Nothing reaches a
        // buyer while GOOGLE_WALLET_ENABLED is false" has to mean no HTTP call is
        // even prepared, not that one is made and its result discarded. Both
        // halves are checked: props can be complete while the credential itself
        // will not parse, and that is a closed gate too.
        if (!props.fullyConfigured() || !api.isUsable()) {
            throw unavailable();
        }

        String issuerId = props.getIssuerId().trim();
        String classId = GoogleWalletModels.classId(issuerId, event.getId());

        ensureClass(classId, event, org);

        String objectId = GoogleWalletModels.objectId(issuerId, ticket.getToken());
        api.insertEventTicketObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketObject(
                        issuerId, ticket, event, qrPayload, manageUrl(ticket))));
        return objectId;
    }

    /**
     * Get-then-insert, tolerating a {@code 409} so two concurrent buyers for the
     * same event cannot both create and neither fail.
     *
     * <p><b>The read is here for {@code reviewStatus} and nothing else.</b> A
     * class in {@code DRAFT} cannot be used to create objects, so the object
     * insert would fail with an error about the object rather than about the
     * class — the failure would point at the wrong resource, on the buyer's
     * request, at the door. This code never writes {@code DRAFT}
     * ({@code GoogleWalletModelsTest} pins that), so a draft class can only have
     * come from the console or from a hand-rolled call; we refuse it loudly and
     * name the fix instead of silently promoting a resource a human created
     * deliberately. Promotion is one-way and irreversible, which is not a
     * transition to make on somebody else's behalf inside a GET.
     *
     * <p>The object insert deliberately does <b>not</b> read first, and that is a
     * departure from the plan. The plan wanted get-then-insert on both "so two
     * concurrent buyers cannot both create and neither fails" — tolerating
     * {@code 409} achieves that by itself. A read before every object insert
     * would double the outbound calls on the path a buyer waits on to learn
     * something the insert already reports, and unlike the class there is no
     * second field to inspect.
     */
    private void ensureClass(String classId, Event event, Organization org) {
        if (knownClasses.containsKey(classId)) {
            return;
        }
        Optional<String> existing = api.getEventTicketClass(classId);
        if (existing.isPresent()) {
            String status = GoogleWalletModels.reviewStatusOf(existing.get());
            if (GoogleWalletModels.REVIEW_STATUS_DRAFT.equals(status)) {
                log.error("[wallet] Google Wallet class {} is in DRAFT and cannot have objects "
                        + "attached, so no pass can be created for this event. Promote it to "
                        + "UNDER_REVIEW in the Google Pay & Wallet Console — the transition is "
                        + "one-way and this service will not make it for you.", classId);
                throw unavailable();
            }
            knownClasses.put(classId, Boolean.TRUE);
            return;
        }
        api.insertEventTicketClass(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(props.getIssuerId().trim(), event, org)));
        log.info("[wallet] created Google Wallet class {} for event {}", classId, event.getId());
        knownClasses.put(classId, Boolean.TRUE);
    }

    /** The buyer's own ticket page, the same link the Apple pass puts on its back. */
    private String manageUrl(Ticket ticket) {
        String base = emailProps.getBuyerSiteBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        base = base.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/tickets/" + ticket.getToken();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                "Google Wallet is temporarily unavailable");
    }
}
