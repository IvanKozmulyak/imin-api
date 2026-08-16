package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.WalletEligibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Turns a ticket token into the {@code https://pay.google.com/gp/v/save/{jwt}}
 * URL the buyer's browser is redirected to.
 *
 * <p>Three steps and no more: read the rows, make sure the Google resources
 * exist, sign a JWT that names the object and nothing else.
 *
 * <h2>The JWT carries an id, never an inline object</h2>
 *
 * <p>Google caps a save URL at <b>1800 characters</b> and answers the "my JWT
 * link exceeds the limit" question with "pre-create the resources through the
 * REST API and put only the id in the JWT". A class carrying an event name, a
 * venue, a date and a logo, plus an object carrying a barcode, blows past that
 * immediately — so provisioning happens over REST ({@link GoogleWalletProvisioner})
 * and the payload here is {@code {"eventTicketObjects":[{"id":"…"}]}}. The
 * length is checked at runtime as well as in the test, because a link over the
 * cap does not fail loudly at Google — it fails at whichever layer truncates it.
 *
 * <h2>This class is deliberately NOT {@code @Transactional}</h2>
 *
 * <p>It reads three rows and then makes up to three outbound HTTPS calls, and
 * those two facts must not overlap. A {@code @Transactional(readOnly = true)}
 * here — the annotation a reviewer would reasonably suggest for a method that
 * only reads — would hold a pooled JDBC connection open across every one of
 * those calls. At the configured 5s connect + 5s read timeouts that is up to
 * ~15s of connection-holding per request on an <b>unauthenticated</b> endpoint,
 * which is a pool-exhaustion outage of the entire API triggered by Google being
 * slow. The failure would not show up in any single-request test; it needs
 * concurrency and a slow upstream, i.e. production.
 *
 * <p>Saying so in a comment is not enough, because a comment does not fail a
 * build. {@link GoogleWalletProvisioner#provision} asserts at runtime that no
 * transaction is active before it opens a socket, and two tests drive it:
 * {@code GoogleWalletProvisionerTest#provisioningInsideATransactionIsRefusedBeforeAnySocketOpens}
 * proves the assertion bites, and
 * {@code GoogleWalletEndpointTest#theSaveLinkPathRunsWithNoDatabaseTransactionOpen}
 * proves this path is on the right side of it.
 *
 * <h2>The QR payload is produced here, once</h2>
 *
 * <p>{@link QrPayloadSigner#sign} is called on this path exactly as it is on the
 * pkpass path and the emailed-PNG path, so the barcode Google stores is
 * byte-identical to the one on the buyer's ticket page. That is a property of
 * the call site, not of the models — {@link GoogleWalletModels} takes the
 * payload as a parameter and will faithfully carry whatever it is handed.
 */
@Service
public class GoogleWalletPassService {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletPassService.class);

    /** Google's save endpoint. The JWT is appended verbatim; it is base64url, so nothing needs escaping. */
    static final String SAVE_URL_PREFIX = "https://pay.google.com/gp/v/save/";

    /**
     * Google's documented ceiling on a save link, and the reason the JWT is thin.
     * A longer URL is not rejected with an error a buyer could act on — it is
     * truncated somewhere between the browser and Google and produces a save
     * page that fails for reasons nothing here can see.
     */
    static final int MAX_SAVE_URL_LENGTH = 1800;

    private final GoogleWalletProperties props;
    private final GoogleWalletProvisioner provisioner;
    private final GoogleWalletJwtSigner signer;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final OrganizationRepository organizations;
    private final QrPayloadSigner qrSigner;

    public GoogleWalletPassService(GoogleWalletProperties props,
                                   GoogleWalletProvisioner provisioner,
                                   GoogleWalletJwtSigner signer,
                                   TicketRepository tickets,
                                   EventRepository events,
                                   OrganizationRepository organizations,
                                   QrPayloadSigner qrSigner) {
        this.props = props;
        this.provisioner = provisioner;
        this.signer = signer;
        this.tickets = tickets;
        this.events = events;
        this.organizations = organizations;
        this.qrSigner = qrSigner;
    }

    /**
     * Whether a save link can be produced at all.
     *
     * <p>Both halves, for the reason the Apple side learned the hard way: the env
     * vars being set and the credential actually loading are different facts, and
     * a deployment where the second is false must report itself unavailable
     * rather than 500 on the first buyer who taps the button.
     */
    public boolean isConfigured() {
        return props.fullyConfigured() && signer.isUsable();
    }

    /**
     * The full redirect target for {@code ticketToken}.
     *
     * @throws ApiException {@code 404} when the token is unknown, {@code 409}
     *                      when the ticket is refunded or revoked, {@code 503}
     *                      when the wallet is off or Google will not answer
     */
    public String saveUrl(String ticketToken) {
        Ticket ticket = tickets.findByToken(ticketToken)
                .orElseThrow(() -> ApiException.notFound("Ticket"));

        // Eligibility first, and before the config gate. A refunded ticket is
        // refunded whether or not Google Wallet is switched on: 409 is the true
        // answer and 503 "temporarily unavailable" would invite a retry that can
        // never succeed. Same ordering as GoogleWalletProvisioner, which repeats
        // the check because an object it creates outlives this request.
        WalletEligibility.assertLive(ticket);

        if (!isConfigured()) {
            throw unavailable();
        }

        Event event = events.findById(ticket.getEventId())
                .orElseThrow(() -> new IllegalStateException(
                        "Event missing for a ticket that exists"));
        // The event's own org, not the order's: they are the same organization and
        // this saves a row read on a path a buyer is waiting on. Nullable on
        // purpose — a missing organization row degrades the "Presented by" module,
        // it does not fail a pass.
        Organization org = event.getOrgId() == null ? null
                : organizations.findById(event.getOrgId()).orElse(null);

        // The one canonical payload. The pkpass barcode, the emailed PNG and the
        // web /qr.png all come from this same call on this same token, so a gate
        // scanner sees identical bytes however the buyer presents the ticket.
        String qrPayload = qrSigner.sign(ticket.getToken());

        String objectId = provisioner.provision(ticket, event, org, qrPayload);

        // Thin by construction: an id and nothing else. See the class note.
        String jwt = signer.sign(Map.of("eventTicketObjects", List.of(Map.of("id", objectId))));
        String url = SAVE_URL_PREFIX + jwt;

        if (url.length() > MAX_SAVE_URL_LENGTH) {
            // Deliberately refuses rather than handing over a link that Google
            // may or may not honour. The only way to get here is a payload that
            // stopped being thin, which is a code change, not an operational one
            // — so the log names the cause instead of the symptom. The URL itself
            // is never logged: it is a bearer artifact that mints a pass.
            log.error("[wallet] a Google Wallet save URL came out at {} characters, over Google's "
                            + "{}-character cap — the JWT payload is no longer just an object id",
                    url.length(), MAX_SAVE_URL_LENGTH);
            throw unavailable();
        }
        return url;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                "Google Wallet passes are not available");
    }
}
