package com.imin.iminapi.service.ticket.google;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The two resources Google Wallet needs for an event ticket — the
 * {@code EventTicketClass} (one per event, the template) and the
 * {@code EventTicketObject} (one per ticket) — plus the JSON they are sent as.
 *
 * <h2>Why the JSON is produced here rather than by a message converter</h2>
 *
 * <p>Spring Boot 4 wires HTTP message conversion to <b>Jackson 3</b>
 * ({@code tools.jackson}) while Jackson 2 ({@code com.fasterxml}) is also on the
 * classpath transitively through jpasskit. Handing a typed body to
 * {@code RestClient} and letting it pick a converter is therefore a runtime
 * coin-flip that a {@code catch} would swallow — the exact defect that made the
 * mobile Phase 0 push sender report {@code accepted=0} forever with no error
 * until {@code ExpoPushSender} was changed to serialise and parse explicitly.
 *
 * <p>So nothing here ever reaches a converter: every request body leaves as a
 * {@code String} and every response arrives as a {@code String}, both handled by
 * {@code StringHttpMessageConverter}, which has no Jackson generation. The one
 * {@link ObjectMapper} below is constructed by hand, so the generation is
 * decided at compile time by the import and cannot vary with what Boot happens
 * to auto-configure.
 *
 * <h2>Absent fields are absent on purpose</h2>
 *
 * <p>Google's event ticket accepts a good deal more than this emits —
 * {@code ticketHolderName}, {@code seatInfo}, {@code logo}, {@code venue.*}
 * beyond name and address. Every one of those is omitted because no column backs
 * it. A plausible-looking invented value on a ticket is worse than an absent
 * one, and {@code @JsonInclude(NON_NULL)} means an absent value never even
 * serialises as {@code null}.
 *
 * <p><b>{@code heroImage} is the one omission that is not about missing data,
 * and the plan asked for it, so the reason is written down here.</b>
 * {@code events.poster_url} exists and is a public R2 URL, so it could be sent.
 * It is not, for two reasons that compound. Google's hero image is a full-width
 * banner at roughly 3:1; every imin poster is generated at 4:5 portrait, so what
 * a buyer would see is a horizontal slice out of the middle of their poster,
 * frequently across a line of the baked-in typography. And Google fetches the
 * URL itself when the class is inserted — a poster that has been deleted or
 * moved would take the <em>class</em> insert down with it, and because a class
 * is created once per event and nothing ever patches it (ADR-0004), that
 * failure is permanent for every ticket to that event. The Apple side reached
 * the same place from the other direction: Task 4 cut the poster event ticket
 * ({@code a498ac1}), so neither wallet carries the poster and the two agree.
 */
public final class GoogleWalletModels {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletModels.class);

    private GoogleWalletModels() {}

    /**
     * The only review status this code will ever send.
     *
     * <p><b>{@code DRAFT} is the value that silently breaks everything.</b> A
     * class in {@code DRAFT} cannot be used to create any object, so every
     * object insert against it fails — and the transition off {@code draft} is
     * one-way, so it cannot be undone by re-inserting. {@code UNDER_REVIEW} is
     * not a human queue at class level: the platform promotes it to
     * {@code APPROVED} by itself and it is usable immediately. The human queue
     * is the account-level publishing-access request, which is a different gate
     * entirely.
     */
    static final String REVIEW_STATUS = "UNDER_REVIEW";

    /** What a class that cannot be used to create objects looks like. */
    static final String REVIEW_STATUS_DRAFT = "DRAFT";

    /** A live ticket. The only object state this code writes. */
    static final String STATE_ACTIVE = "ACTIVE";

    /**
     * The merchant of record, which is imin regardless of who is throwing the
     * party — the same call {@code AppleWalletPassService} makes with
     * {@code organizationName("imin")}.
     *
     * <p>Three reasons it is not the organizer's brand name. The Google issuer
     * account is imin's, so an organizer name here would misstate who issued the
     * pass to the one party that verifies it. Google's own note is that
     * {@code issuerName} should stay under about 20 characters or it truncates
     * on small screens, and organizer brand names are unbounded. And it is a
     * <em>class</em> field, so it is fixed for the life of every object created
     * from it. The organizer's name goes in a text module instead, which is
     * where {@code logoText} puts it on the Apple pass.
     */
    static final String ISSUER_NAME = "imin";

    /**
     * Night Kit {@code --bg}, matching {@code imin-public}'s {@code globals.css}
     * and the {@code backgroundColor} on the Apple pass. Google takes hex where
     * Apple takes {@code rgb()}; same colour.
     */
    static final String BACKGROUND_HEX = "#08070d";

    /**
     * How long an event runs when {@code events.ends_at} is NULL, and the grace
     * between the end of the event and the pass ageing out.
     *
     * <p><b>These mirror {@code AppleWalletPassService.ASSUMED_RUN} and
     * {@code EXPIRY_SLACK} and must stay equal to them</b>: the same ticket
     * ageing out at two different times on two platforms is a support incident
     * that nothing would report. {@code GoogleWalletModelsTest} reads both pairs
     * and fails if they drift.
     */
    static final Duration ASSUMED_RUN = Duration.ofHours(12);
    static final Duration EXPIRY_SLACK = Duration.ofHours(12);

    /**
     * ISO-8601 with an explicit offset and explicit seconds — the same shape the
     * Apple pass pins, and for the same reason: {@code ISO_OFFSET_DATE_TIME}
     * drops the seconds component when it is zero, which is legal ISO but makes
     * the emitted string vary with the data.
     *
     * <p>The offset is the <b>venue's</b>, never the server's. Google reads an
     * offset-carrying string as an absolute instant and renders it in the
     * venue's local time; a UTC instant would put 20:00 on a ticket for a 22:00
     * Paris door, and the failure looks like a working feature.
     */
    private static final DateTimeFormatter ISO_OFFSET_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * Google's id charset: the identifier half of a resource id may only contain
     * alphanumerics, {@code .}, {@code _} and {@code -}.
     *
     * <p>The plan asserted this was safe because "ticket tokens are
     * {@code TKT_<uuid>}". They are not — {@code PaidCheckoutService.randomToken()}
     * emits 24 random bytes as <b>base64url without padding</b>, so the alphabet
     * is {@code A-Za-z0-9-_}. The conclusion survives (base64<b>url</b>'s two
     * non-alphanumerics are exactly two of Google's three), but it survives by
     * one character: plain base64 would emit {@code +} and {@code /} and every
     * id would be rejected. So this is checked at runtime on every id rather
     * than trusted, and a violation throws here instead of becoming an opaque
     * 400 from Google.
     */
    private static final Pattern GOOGLE_ID_CHARSET = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Serialisation is explicit and Jackson 2, matching {@code ExpoPushSender}
     * and {@code NominatimGeocoder}. See the class Javadoc for why it is not a
     * message converter's job.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── wire records ─────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TranslatedString(String language, String value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocalizedString(TranslatedString defaultValue) {
        /** Null — and therefore an absent field — for a blank value. */
        static LocalizedString of(String value) {
            return isBlank(value) ? null : new LocalizedString(
                    new TranslatedString("en-US", value.trim()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventDateTime(String start, String end) {}

    /**
     * Google requires <b>both</b> {@code name} and {@code address} on a venue.
     * A venue with only one of them is a 400, so the caller omits the whole
     * block rather than sending half of it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventVenue(LocalizedString name, LocalizedString address) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextModule(String id, String header, String body) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LinkUri(String id, String uri, String description) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LinksModule(List<LinkUri> uris) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Barcode(String type, String value, String alternateText) {}

    /** Google's {@code DateTime} wrapper — a bare {@code {"date": "…"}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletDate(String date) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimeInterval(WalletDate start, WalletDate end) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventTicketClass(String id,
                                   String issuerName,
                                   String reviewStatus,
                                   String eventId,
                                   LocalizedString eventName,
                                   EventVenue venue,
                                   EventDateTime dateTime,
                                   String hexBackgroundColor,
                                   List<TextModule> textModulesData) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventTicketObject(String id,
                                    String classId,
                                    String state,
                                    Barcode barcode,
                                    LocalizedString ticketType,
                                    TimeInterval validTimeInterval,
                                    LinksModule linksModuleData) {}

    // ── ids ──────────────────────────────────────────────────────────────────

    /** {@code {issuerId}.evt_{eventId}} — one class per event, see the provisioner. */
    public static String classId(String issuerId, Object eventId) {
        return resourceId(issuerId, "evt_" + eventId);
    }

    /** {@code {issuerId}.tkt_{ticketToken}} — one object per ticket. */
    public static String objectId(String issuerId, String ticketToken) {
        return resourceId(issuerId, "tkt_" + ticketToken);
    }

    private static String resourceId(String issuerId, String identifier) {
        if (isBlank(issuerId)) {
            throw new IllegalArgumentException("GOOGLE_WALLET_ISSUER_ID is blank");
        }
        if (!GOOGLE_ID_CHARSET.matcher(issuerId).matches()) {
            throw new IllegalArgumentException(
                    "GOOGLE_WALLET_ISSUER_ID is not a usable Google resource id");
        }
        if (!GOOGLE_ID_CHARSET.matcher(identifier).matches()) {
            // Deliberately does not echo the identifier: for an object id it
            // contains the ticket token, which is the bearer credential for
            // that ticket.
            throw new IllegalArgumentException(
                    "A Google Wallet resource identifier contains characters outside "
                            + "[A-Za-z0-9._-] and cannot be used as an id");
        }
        return issuerId + "." + identifier;
    }

    // ── payload builders ─────────────────────────────────────────────────────

    /**
     * The class for one event.
     *
     * @param org the event's organization, nullable — a missing row degrades the
     *            "Presented by" module, it does not fail a pass
     */
    public static EventTicketClass eventTicketClass(String issuerId, Event event, Organization org) {
        ZoneId zone = zoneOf(event);
        Instant start = event.getStartsAt();
        Instant end = eventEnd(event);

        EventDateTime dateTime = start == null ? null
                : new EventDateTime(format(start, zone), format(end, zone));

        // Both halves or neither: Google rejects a venue carrying only a name.
        LocalizedString venueName = LocalizedString.of(event.getVenueName());
        LocalizedString venueAddress = LocalizedString.of(formatAddress(event));
        EventVenue venue = (venueName == null || venueAddress == null)
                ? null : new EventVenue(venueName, venueAddress);

        List<TextModule> modules = new ArrayList<>(1);
        String brand = org == null ? null : org.getBrandName();
        if (!isBlank(brand)) {
            modules.add(new TextModule("organiser", "Presented by", brand.trim()));
        }

        return new EventTicketClass(
                classId(issuerId, event.getId()),
                ISSUER_NAME,
                REVIEW_STATUS,
                event.getId() == null ? null : event.getId().toString(),
                LocalizedString.of(event.getName()),
                venue,
                dateTime,
                BACKGROUND_HEX,
                modules.isEmpty() ? null : List.copyOf(modules));
    }

    /**
     * The object for one ticket.
     *
     * @param qrPayload the {@code imin1.<token>.<hmac>} string the emailed PNG,
     *                  the web QR and the pkpass all carry — one canonical
     *                  payload, four transports
     * @param manageUrl the buyer's own ticket page, or blank for no links module
     */
    public static EventTicketObject eventTicketObject(String issuerId,
                                                      Ticket ticket,
                                                      Event event,
                                                      String qrPayload,
                                                      String manageUrl) {
        Instant end = eventEnd(event);

        // End only, no start — and that asymmetry with the Apple pass is
        // deliberate. Apple's relevantDates is a RELEVANCY hint (surface me on
        // the lock screen near door time); Google's validTimeInterval is a
        // VALIDITY window. A start in the future would mark the pass not-yet-
        // valid for the entire period in which the buyer adds it and shows it to
        // people, which is most of its life. What we actually want is Apple's
        // expirationDate, which is also end-only: let the OS age the pass out
        // once the night is over (ADR-0004 — nothing ever updates it again).
        TimeInterval validity = end == null ? null
                : new TimeInterval(null, new WalletDate(format(end.plus(EXPIRY_SLACK), zoneOf(event))));

        LinksModule links = isBlank(manageUrl) ? null
                : new LinksModule(List.of(new LinkUri("manage", manageUrl.trim(), "Manage this ticket")));

        return new EventTicketObject(
                objectId(issuerId, ticket.getToken()),
                classId(issuerId, event.getId()),
                STATE_ACTIVE,
                new Barcode("QR_CODE", qrPayload, ticket.getToken()),
                LocalizedString.of(ticket.getTierName()),
                validity,
                links);
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    /** The exact bytes that go on the wire. */
    public static String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // Unreachable for these records — they hold only Strings, Lists and
            // each other — but a serialisation failure must not surface as
            // something a caller mistakes for a Google outage.
            throw new IllegalStateException(
                    "Could not serialise a Google Wallet payload (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The {@code reviewStatus} of a class Google handed back, uppercased, or
     * {@code null} when the body does not carry one.
     *
     * <p>Read explicitly rather than deserialised into a record: the class we
     * fetch may have been created by an older version of this code or by a human
     * in the console, so it can carry fields this class does not model, and a
     * strict binding would fail on exactly the case this exists to diagnose.
     */
    public static String reviewStatusOf(String classJson) {
        if (isBlank(classJson)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(classJson).path("reviewStatus");
            return node.isTextual() ? node.asText().toUpperCase() : null;
        } catch (Exception e) {
            log.warn("[wallet] could not read reviewStatus off a Google Wallet class ({})",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    // ── derivation, shared with the Apple pass by value and not by code ───────

    /**
     * When the event is over: the declared end, or a night's worth after doors.
     * Null only when the event has no start at all (a draft), in which case the
     * pass carries no validity window rather than inventing one.
     */
    static Instant eventEnd(Event e) {
        Instant start = e.getStartsAt();
        if (start == null) {
            return null;
        }
        Instant declared = e.getEndsAt();
        return (declared != null && declared.isAfter(start)) ? declared : start.plus(ASSUMED_RUN);
    }

    /**
     * The event's timezone, or UTC — never {@code ZoneId.systemDefault()}. The
     * column is {@code NOT NULL DEFAULT 'UTC'} and its values come from a fixed
     * country map, so a bad value is unlikely; a door time that depends on which
     * machine rendered it would be wrong every time.
     */
    static ZoneId zoneOf(Event e) {
        String tz = e.getTimezone();
        if (isBlank(tz)) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException ex) {
            log.warn("[wallet] event {} has an unusable timezone '{}' — rendering the Google "
                    + "pass dates in UTC", e.getId(), tz);
            return ZoneOffset.UTC;
        }
    }

    private static String format(Instant instant, ZoneId zone) {
        return instant == null ? null : ISO_OFFSET_SECONDS.format(OffsetDateTime.ofInstant(instant, zone));
    }

    /**
     * The postal address, assembled only from the parts that are filled in.
     * Blank when the event has no address at all, which drops the venue block
     * entirely rather than shipping a venue Google will reject.
     */
    static String formatAddress(Event e) {
        List<String> parts = new ArrayList<>(3);
        if (!isBlank(e.getVenueStreet())) {
            parts.add(e.getVenueStreet().trim());
        }
        String postal = isBlank(e.getVenuePostalCode()) ? "" : e.getVenuePostalCode().trim();
        String city = isBlank(e.getVenueCity()) ? "" : e.getVenueCity().trim();
        String locality = (postal + " " + city).trim();
        if (!locality.isEmpty()) {
            parts.add(locality);
        }
        if (!isBlank(e.getVenueCountry())) {
            parts.add(e.getVenueCountry().trim());
        }
        return String.join(", ", parts);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
