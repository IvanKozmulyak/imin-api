package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKLocation;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.PKPassBuilder;
import de.brendamour.jpasskit.PKRelevantDate;
import de.brendamour.jpasskit.PKSemantics;
import de.brendamour.jpasskit.PKSemanticsBuilder;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.enums.PKDataDetectorType;
import de.brendamour.jpasskit.enums.PKDateStyle;
import de.brendamour.jpasskit.enums.PKEventType;
import de.brendamour.jpasskit.passes.PKEventTicket;
import de.brendamour.jpasskit.passes.PKGenericPassBuilder;
import de.brendamour.jpasskit.semantics.PKSemanticLocation;
import de.brendamour.jpasskit.signing.PKInMemorySigningUtil;
import de.brendamour.jpasskit.signing.PKPassTemplateInMemory;
import de.brendamour.jpasskit.signing.PKSigningInformation;
import de.brendamour.jpasskit.signing.PKSigningInformationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Builds and signs Apple Wallet {@code .pkpass} archives on demand.
 *
 * <p>The pass embeds the same HMAC-signed QR payload that the web ticket page
 * and email use, so the gate scanner sees one canonical token. It is an
 * {@code eventTicket} carrying the event name, the door time in the
 * <b>venue's</b> zone, the venue, the tier, the full address on the back, and a
 * relevancy interval so iOS surfaces it on the lock screen near door time —
 * which is the entire reason a pass beats a screenshot at a door with no signal.
 *
 * <p><b>No web-service URL is registered and none is planned</b> (ADR-0004). A
 * pass already on a device never updates. Two consequences are handled here
 * rather than wished away:
 * <ul>
 *   <li>{@link WalletEligibility#assertLive} refuses to mint a pass for a ticket
 *       that is already refunded or revoked — the only revocation control that
 *       exists on this side. The door remains authoritative regardless.</li>
 *   <li>{@code expirationDate} lets the OS age the pass out by itself once the
 *       night is over, which covers the overwhelmingly common staleness case.</li>
 * </ul>
 *
 * <p>Artwork (icon + logo) is generated programmatically as solid brand-coloured
 * squares. Replacing the bundled art with real branded PNGs at
 * {@code src/main/resources/wallet/icon.png} etc. is a separate task; the
 * placeholders satisfy Apple's pkpass schema and keep the pass scannable.
 *
 * <p>When any of the {@code APPLE_WALLET_*} env vars are missing,
 * {@link #isConfigured()} returns false; callers (controller + email template)
 * gate on this so the missing-cert case 503s cleanly rather than generating a
 * broken pass.
 */
@Service
public class AppleWalletPassService {

    private static final Logger log = LoggerFactory.getLogger(AppleWalletPassService.class);

    /**
     * How long an event runs when {@code events.ends_at} is NULL. The column is
     * nullable and most organizers leave it blank, so the relevancy interval and
     * the expiry both need a defensible default rather than a missing endDate.
     * A club night is the shape being modelled, not a conference day.
     */
    private static final Duration ASSUMED_RUN = Duration.ofHours(12);

    /**
     * Grace between the end of the event and the pass expiring. An overrunning
     * night and a late scan both still work; a pass sitting in someone's wallet
     * a year later does not.
     */
    private static final Duration EXPIRY_SLACK = Duration.ofHours(12);

    /**
     * ISO-8601 with an explicit offset and explicit seconds.
     *
     * <p>{@code OffsetDateTime.toString()} and {@code ISO_OFFSET_DATE_TIME} both
     * drop the seconds component when it is zero, which is legal ISO but makes
     * the emitted string vary with the data. A pass field value is parsed by
     * iOS, so pin the shape.
     */
    private static final DateTimeFormatter PASS_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    // Night Kit tokens, matching imin-public's globals.css: --bg #08070d,
    // --text #f4f2fb, --text2 #9a96ad. Deliberately --text2 and NOT the kit's
    // --text3 #5f5b70, which failed AA contrast and was removed in the
    // 2026-06-10 critique — do not re-import it here.
    private static final String BG_COLOR = "rgb(8,7,13)";
    private static final String FG_COLOR = "rgb(244,242,251)";
    private static final String LABEL_COLOR = "rgb(154,150,173)";

    private final AppleWalletProperties props;
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final EventRepository events;
    private final OrganizationRepository organizations;
    private final QrPayloadSigner qrSigner;
    private final EmailProperties emailProps;

    /** Cached artwork bytes — generated once at first use. */
    private volatile byte[] iconPng;
    private volatile byte[] icon2xPng;
    private volatile byte[] icon3xPng;
    private volatile byte[] logoPng;
    private volatile byte[] logo2xPng;
    private volatile byte[] logo3xPng;

    public AppleWalletPassService(AppleWalletProperties props,
                                   TicketRepository tickets,
                                   OrderRepository orders,
                                   EventRepository events,
                                   OrganizationRepository organizations,
                                   QrPayloadSigner qrSigner,
                                   EmailProperties emailProps) {
        this.props = props;
        this.tickets = tickets;
        this.orders = orders;
        this.events = events;
        this.organizations = organizations;
        this.qrSigner = qrSigner;
        this.emailProps = emailProps;

        // Fail loudly at boot rather than silently on the first buyer's tap.
        // Deliberately a log line and NOT a throw: an unusable certificate must
        // not stop the application from booting — checkout, issuance, email and
        // the door are all unaffected by a broken pass.
        WalletCredentialCheck.validate(props).ifPresentOrElse(
                reason -> log.error("[wallet] Apple Wallet is configured but UNUSABLE: {} "
                        + "— /apple-wallet.pkpass will fail. Fix the credentials or set "
                        + "APPLE_WALLET_ENABLED=false.", reason),
                () -> log.info("[wallet] Apple Wallet {}", props.fullyConfigured()
                        ? "configured and credentials load OK"
                        : "not configured"));
    }

    public boolean isConfigured() {
        return props.fullyConfigured();
    }

    public byte[] generatePass(String ticketToken) {
        if (!isConfigured()) {
            throw new IllegalStateException("Apple Wallet not configured");
        }
        Ticket t = tickets.findByToken(ticketToken)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketToken));

        // Before anything else, and before any further reads: a refunded or
        // revoked ticket does not get a fresh, signed, official-looking artifact.
        // Throws a 409 ApiException, which is deliberately NOT wrapped by the
        // catch below — it is a handled answer, not a signing failure.
        WalletEligibility.assertLive(t);

        Order order = orders.findById(t.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order missing for ticket " + ticketToken));
        Event event = events.findById(t.getEventId())
                .orElseThrow(() -> new IllegalStateException("Event missing for ticket " + ticketToken));
        // Nullable on purpose: a missing organization row must degrade the logo
        // text, not 500 a ticket endpoint.
        Organization org = organizations.findById(order.getOrgId()).orElse(null);

        // Same payload as the web QR and the email — single source of truth so a
        // gate scanner sees identical bytes regardless of how the buyer presented
        // the ticket.
        String qrPayload = qrSigner.sign(t.getToken());

        ZoneId zone = zoneOf(event);
        Instant doors = event.getStartsAt();
        Instant ends = eventEnd(event);
        Instant expiry = ends == null ? null : ends.plus(EXPIRY_SLACK);

        // PKEventTicket.builder() returns a PKGenericPassBuilder pre-typed as
        // EventTicket. We add fields directly on it and hand the builder to
        // PKPass via the pass(PKGenericPassBuilder) overload.
        PKGenericPassBuilder eventTicket = PKEventTicket.builder()
                .headerField(field("tier", "Ticket", nullSafe(t.getTierName())))
                .primaryField(field("event", "Event", nullSafe(event.getName())));

        if (doors != null) {
            eventTicket.secondaryField(PKField.builder()
                    .key("when")
                    .label("Doors")
                    // A date-typed value, not a pre-formatted string, so iOS
                    // renders it in the buyer's locale and calendar.
                    //
                    // The string carries the VENUE's offset, which is what makes
                    // ignoresTimeZone correct rather than merely present: Apple
                    // reads that flag as "display the time in the zone attached
                    // to the value". Handing it a UTC instant with the flag on
                    // renders the UTC wall-clock time — 20:00 for a 22:00 Paris
                    // door — which is a wrong door time on a ticket, and the
                    // failure looks like a working feature.
                    .value(PASS_DATE.format(OffsetDateTime.ofInstant(doors, zone)))
                    .dateStyle(PKDateStyle.PKDateStyleMedium)
                    .timeStyle(PKDateStyle.PKDateStyleShort)
                    .ignoresTimeZone(true)
                    .build());
        }

        String where = formatWhere(event);
        if (!where.isBlank()) {
            eventTicket.secondaryField(field("where", "Venue", where));
        }

        String address = formatFullAddress(event);
        if (!address.isBlank()) {
            eventTicket.backField(PKField.builder()
                    .key("address")
                    .label("Address")
                    .value(address)
                    // Lets iOS turn the address into a Maps tap on the back of
                    // the pass — the "get directions" affordance for free, and
                    // the only navigation this pass can offer for the ~all
                    // events whose coordinates are NULL because geocoding is
                    // off by default.
                    .dataDetectorType(PKDataDetectorType.PKDataDetectorTypeAddress)
                    .build());
        }
        if (order.getToken() != null && !order.getToken().isBlank()) {
            eventTicket.backField(field("order", "Order", order.getToken()));
        }
        eventTicket.backField(PKField.builder()
                .key("manage")
                .label("Manage this ticket")
                .value(buyerTicketUrl(t))
                .dataDetectorType(PKDataDetectorType.PKDataDetectorTypeLink)
                .build());

        PKPassBuilder builder = PKPass.builder()
                .passTypeIdentifier(props.getPassTypeId())
                .teamIdentifier(props.getTeamId())
                .serialNumber(t.getToken())
                // The merchant of record, which is imin regardless of who is
                // throwing the party. The organizer's name goes on logoText.
                .organizationName("imin")
                .logoText(brandOrImin(org))
                .description(nullSafe(event.getName()) + " — " + nullSafe(t.getTierName()))
                .foregroundColor(FG_COLOR)
                .backgroundColor(BG_COLOR)
                .labelColor(LABEL_COLOR)
                .barcodeBuilder(PKBarcode.builder()
                        .format(PKBarcodeFormat.PKBarcodeFormatQR)
                        .message(qrPayload)
                        // Apple's documented recommendation for QR. The payload
                        // is base64url + dots — pure ASCII, so this is lossless.
                        .messageEncoding("iso-8859-1")
                        .altText(t.getToken()))
                .semantics(semanticsFor(event, doors, ends))
                .pass(eventTicket);

        if (order.getId() != null) {
            // Groups every ticket on one order into one stack in Wallet instead
            // of N loose cards. Order id, not event id: a buyer at the same
            // event on two separate orders genuinely has two stacks.
            builder.groupingIdentifier("order-" + order.getId());
        }

        List<PKRelevantDate> relevancy = relevantWindow(doors, ends);
        if (!relevancy.isEmpty()) {
            builder.relevantDates(relevancy);
        }
        if (expiry != null) {
            // Apple demotes an expired pass instead of leaving it in rotation.
            // With no update web service this is the only self-cleaning the pass
            // gets (ADR-0004).
            builder.expirationDate(expiry);
        }

        // Locations are opt-in data: IMIN_GEOCODING_ENABLED defaults to false, so
        // venue_latitude/longitude are NULL on most rows. Emitting a locations
        // array with nulls in it produces a pass Apple rejects, so this is a
        // conditional, not a mapping.
        if (event.getVenueLatitude() != null && event.getVenueLongitude() != null) {
            builder.locations(List.of(PKLocation.builder()
                    .latitude(event.getVenueLatitude())
                    .longitude(event.getVenueLongitude())
                    .relevantText(nullSafe(event.getVenueName()))
                    .build()));
        }

        PKPass pass = builder.build();

        try {
            PKPassTemplateInMemory template = new PKPassTemplateInMemory();
            template.addFile(PKPassTemplateInMemory.PK_ICON, new ByteArrayInputStream(iconArt()));
            template.addFile(PKPassTemplateInMemory.PK_ICON_RETINA, new ByteArrayInputStream(icon2xArt()));
            template.addFile(PKPassTemplateInMemory.PK_ICON_RETINAHD, new ByteArrayInputStream(icon3xArt()));
            template.addFile(PKPassTemplateInMemory.PK_LOGO, new ByteArrayInputStream(logoArt()));
            template.addFile(PKPassTemplateInMemory.PK_LOGO_RETINA, new ByteArrayInputStream(logo2xArt()));
            template.addFile(PKPassTemplateInMemory.PK_LOGO_RETINAHD, new ByteArrayInputStream(logo3xArt()));

            byte[] p12Bytes = Base64.getDecoder().decode(props.getCertP12Base64());
            byte[] wwdrBytes = Base64.getDecoder().decode(props.getWwdrPemBase64());

            PKSigningInformation signing = new PKSigningInformationUtil()
                    .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                            new ByteArrayInputStream(p12Bytes),
                            // Never null: jpasskit does keyStorePassword.toCharArray()
                            // and an empty-password P12 wants an empty char[].
                            props.certPasswordOrEmpty(),
                            new ByteArrayInputStream(wwdrBytes));

            return new PKInMemorySigningUtil()
                    .createSignedAndZippedPkPassArchive(pass, template, signing);
        } catch (Exception e) {
            log.error("Failed to build Apple Wallet pass for token {}: {}",
                    ticketToken, e.getMessage(), e);
            throw new IllegalStateException("Failed to build Apple Wallet pass", e);
        }
    }

    // ─── pass.json field derivation ──────────────────────────────────────────

    /**
     * The pass's relevancy interval, or nothing at all.
     *
     * <p><b>This is the enforcement Apple requires and jpasskit does not do.</b>
     * {@code endDate} is documented as "Required when providing startDate", but
     * {@code PKRelevantDateBuilder} implements {@code IPKBuilder} only — not
     * {@code IPKValidateable} — and {@code PKPassBuilder.build()} never runs
     * validation anyway. A half interval therefore serialises happily, and the
     * defect surfaces as a pass that quietly never appears on a lock screen: no
     * exception, no log line, no rejected archive. So the pairing is enforced
     * here, at the only place that knows both ends.
     *
     * <p>Package-private and static so the "start with no end" case is directly
     * testable. It is unreachable through {@link #generatePass} today —
     * {@link #eventEnd} always derives an end when a start exists — and that is
     * the point: the guard has to keep holding when a later caller stops being
     * so careful.
     */
    static List<PKRelevantDate> relevantWindow(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return List.of();
        }
        return List.of(PKRelevantDate.builder()
                .startDate(start)
                .endDate(end)
                .build());
    }

    /**
     * When the event is over: the declared end, or a night's worth after doors.
     * Null only when the event has no start at all (a draft), in which case the
     * pass carries neither a relevancy interval nor an expiry rather than
     * inventing one.
     */
    private static Instant eventEnd(Event e) {
        Instant start = e.getStartsAt();
        if (start == null) {
            return null;
        }
        Instant declared = e.getEndsAt();
        return (declared != null && declared.isAfter(start)) ? declared : start.plus(ASSUMED_RUN);
    }

    /**
     * The semantic tags that drive iOS's richer event-ticket presentation.
     *
     * <p>Every tag here traces to a real column. Apple also documents
     * {@code venueRoom}, {@code venueEntrance}, {@code venuePhoneNumber},
     * {@code performerNames} and {@code seats} for event tickets and this pass
     * carries none of them — {@code Event} has no backing field for any, and a
     * plausible-looking invented value on a ticket is worse than an absent one.
     */
    private static PKSemantics semanticsFor(Event e, Instant doors, Instant ends) {
        PKSemanticsBuilder semantics = PKSemantics.builder()
                // Generic, not LivePerformance: events.type is a free-text
                // column with no closed vocabulary, so anything more specific
                // would be a guess. LivePerformance additionally expects
                // performerNames, which does not exist.
                .eventType(PKEventType.PKEventTypeGeneric);
        if (notBlank(e.getName())) {
            semantics.eventName(e.getName());
        }
        if (notBlank(e.getVenueName())) {
            semantics.venueName(e.getVenueName());
        }
        if (notBlank(e.getGenre())) {
            semantics.genre(e.getGenre());
        }
        if (doors != null) {
            // The one place java.util.Date is allowed to exist: PKSemanticsBuilder
            // takes Date, not Instant, even on 0.5.8. Convert at the boundary and
            // let nothing inward see it.
            semantics.eventStartDate(Date.from(doors));
        }
        if (ends != null) {
            semantics.eventEndDate(Date.from(ends));
        }
        if (e.getVenueLatitude() != null && e.getVenueLongitude() != null) {
            semantics.venueLocation(PKSemanticLocation.builder()
                    .latitude(e.getVenueLatitude())
                    .longitude(e.getVenueLongitude())
                    .build());
        }
        return semantics.build();
    }

    private String buyerTicketUrl(Ticket t) {
        String base = emailProps.getBuyerSiteBaseUrl();
        if (base == null) {
            base = "";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/tickets/" + t.getToken();
    }

    /** The organizer's brand name when they have one, otherwise ours. */
    private static String brandOrImin(Organization org) {
        String brand = org == null ? null : org.getBrandName();
        return notBlank(brand) ? brand : "imin";
    }

    // ─── artwork (lazily generated solid-colour placeholders) ────────────────

    private byte[] iconArt() throws IOException {
        if (iconPng == null) iconPng = solidSquare(29);
        return iconPng;
    }

    private byte[] icon2xArt() throws IOException {
        if (icon2xPng == null) icon2xPng = solidSquare(58);
        return icon2xPng;
    }

    private byte[] icon3xArt() throws IOException {
        if (icon3xPng == null) icon3xPng = solidSquare(87);
        return icon3xPng;
    }

    private byte[] logoArt() throws IOException {
        if (logoPng == null) logoPng = solidRect(160, 50);
        return logoPng;
    }

    private byte[] logo2xArt() throws IOException {
        if (logo2xPng == null) logo2xPng = solidRect(320, 100);
        return logo2xPng;
    }

    private byte[] logo3xArt() throws IOException {
        if (logo3xPng == null) logo3xPng = solidRect(480, 150);
        return logo3xPng;
    }

    private static byte[] solidSquare(int size) throws IOException {
        return solidRect(size, size);
    }

    private static byte[] solidRect(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // imin brand near-black with subtle off-white "i" mark. Apple is
            // lenient about content; only the file shape matters.
            g.setColor(new Color(20, 20, 24));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(245, 245, 247));
            int markSize = Math.max(2, Math.min(w, h) / 4);
            int markX = (w - markSize) / 2;
            int markY = (h - markSize) / 2;
            g.fillOval(markX, markY, markSize, markSize);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }

    // ─── small helpers ───────────────────────────────────────────────────────

    private static PKField field(String key, String label, String value) {
        return PKField.builder()
                .key(key)
                .label(label)
                .value(value == null ? "" : value)
                .build();
    }

    /**
     * The event's timezone, or UTC.
     *
     * <p>The previous shape resolved this with a bare {@code ZoneId.of(...)}
     * outside the {@code try} in {@link #generatePass(String)}, so a malformed
     * {@code events.timezone} escaped as an unhandled 500 while the
     * {@code systemDefault()} fallback beside it read like a safety net and
     * caught nothing. The trigger is narrow — the column is {@code NOT NULL
     * DEFAULT 'UTC'} and values come from a fixed country map — but a pass is a
     * decoration and must never be able to 500 a ticket endpoint.
     *
     * <p>The fallback is UTC rather than {@code ZoneId.systemDefault()} on
     * purpose: the column's own default is {@code 'UTC'}, and a door time that
     * depends on which machine rendered it is a wrong door time.
     */
    private static ZoneId zoneOf(Event e) {
        String tz = e.getTimezone();
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException ex) {
            log.warn("[wallet] event {} has an unusable timezone '{}' — rendering the "
                    + "pass date in UTC", e.getId(), tz);
            return ZoneOffset.UTC;
        }
    }

    private static String formatWhere(Event e) {
        String n = e.getVenueName();
        String c = e.getVenueCity();
        if (notBlank(n) && notBlank(c)) return n + ", " + c;
        if (notBlank(n)) return n;
        if (notBlank(c)) return c;
        return "";
    }

    /**
     * The postal address for the back of the pass, assembled only from the parts
     * that are actually filled in. Returns "" when the event has no address at
     * all, and the caller then omits the field entirely rather than shipping an
     * "Address" row with nothing under it.
     */
    private static String formatFullAddress(Event e) {
        List<String> parts = new ArrayList<>(3);
        if (notBlank(e.getVenueStreet())) {
            parts.add(e.getVenueStreet().trim());
        }
        String postal = notBlank(e.getVenuePostalCode()) ? e.getVenuePostalCode().trim() : "";
        String city = notBlank(e.getVenueCity()) ? e.getVenueCity().trim() : "";
        String locality = (postal + " " + city).trim();
        if (!locality.isEmpty()) {
            parts.add(locality);
        }
        if (notBlank(e.getVenueCountry())) {
            parts.add(e.getVenueCountry().trim());
        }
        return String.join(", ", parts);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
