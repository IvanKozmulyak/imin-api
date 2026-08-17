package com.imin.iminapi.service.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What ends up in {@code pass.json}, asserted from the <b>signed archive</b>
 * rather than from the builder.
 *
 * <p>The archive is the only artifact Apple ever sees. Asserting on a builder
 * would prove the code we wrote does what we wrote, and nothing about the wire
 * format — the two can be wrong in the same direction. So every case here builds
 * a real pass, signs it with real cryptography over {@link WalletTestCerts}'s
 * synthetic key material, unzips it, and reads the JSON back out.
 */
class ApplePassContentTest {

    // 22:00 Paris on a June night = 20:00 UTC. The gap between the two is the
    // whole point of several assertions below, so the fixture keeps a zone whose
    // offset is not zero.
    private static final Instant DOORS = OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant();

    // ── the field the whole feature turns on ─────────────────────────────────

    /**
     * Without a relevancy interval iOS never surfaces the pass on the lock
     * screen, which is the entire reason a pass beats a screenshot at a door with
     * no signal.
     *
     * <p>It is the ARRAY form: Apple deprecated the scalar {@code relevantDate},
     * and jpasskit only serialises the array correctly from 0.5.5 — hence the
     * library upgrade being a prerequisite rather than a tidy-up.
     */
    @Test
    void relevantDatesCarriesTheEventWindow() throws Exception {
        JsonNode pass = passJson(fixture());

        assertThat(pass.path("relevantDates")).hasSize(1);
        JsonNode window = pass.path("relevantDates").get(0);
        assertThat(window.path("startDate").asText()).isEqualTo("2026-06-15T20:00:00Z");
        assertThat(window.path("endDate").asText()).isGreaterThan(window.path("startDate").asText());

        assertThat(pass.has("relevantDate"))
                .as("the scalar form is deprecated and PKPassBuilder still offers it — "
                        + "a pass must not carry both")
                .isFalse();
    }

    /**
     * {@code events.ends_at} is nullable and usually null, so the interval's end
     * is derived. Where it is set, it is used verbatim.
     */
    @Test
    void aDeclaredEndTimeIsUsedForTheIntervalInsteadOfTheAssumedRun() throws Exception {
        Fixture f = fixture();
        f.event.setEndsAt(OffsetDateTime.parse("2026-06-16T04:00:00+02:00").toInstant());

        JsonNode window = passJson(f).path("relevantDates").get(0);
        assertThat(window.path("endDate").asText()).isEqualTo("2026-06-16T02:00:00Z");
    }

    /**
     * <b>The pairing the library will not enforce.</b>
     *
     * <p>Apple documents {@code endDate} as "Required when providing startDate",
     * and {@code PKRelevantDateBuilder} implements {@code IPKBuilder} only — not
     * {@code IPKValidateable} — while {@code PKPassBuilder.build()} runs no
     * validation at all. A half interval therefore serialises without complaint
     * and fails silently on the device: no exception, no rejected archive, just a
     * pass that never appears when the buyer reaches the door. The guard lives in
     * the service, so it is tested at the service.
     */
    @Test
    void aStartWithNoEndProducesNoRelevancyIntervalAtAll() {
        assertThat(AppleWalletPassService.relevantWindow(DOORS, null)).isEmpty();
        assertThat(AppleWalletPassService.relevantWindow(null, DOORS)).isEmpty();
        assertThat(AppleWalletPassService.relevantWindow(DOORS, DOORS))
                .as("a zero-length interval is not an interval")
                .isEmpty();
        assertThat(AppleWalletPassService.relevantWindow(DOORS, DOORS.plusSeconds(60)))
                .hasSize(1);
    }

    /**
     * A draft event with no start date has nothing to be relevant to. It must
     * produce an empty array rather than a half-populated one — and no
     * expirationDate, rather than an invented one.
     */
    @Test
    void anEventWithNoStartTimeEmitsNeitherARelevancyIntervalNorAnExpiry() throws Exception {
        Fixture f = fixture();
        f.event.setStartsAt(null);

        JsonNode pass = passJson(f);
        assertThat(pass.path("relevantDates")).isEmpty();
        assertThat(pass.hasNonNull("expirationDate")).isFalse();
    }

    /**
     * A pass that never expires is still in the wallet a year later. Apple demotes
     * an expired pass instead of leaving it in rotation, which is the closest
     * thing to an update we get without a web service.
     */
    @Test
    void expirationDateIsSetAndAfterTheRelevancyWindowCloses() throws Exception {
        JsonNode pass = passJson(fixture());

        assertThat(pass.hasNonNull("expirationDate")).isTrue();
        assertThat(pass.path("expirationDate").asText())
                .isGreaterThan(pass.path("relevantDates").get(0).path("endDate").asText());
    }

    // ── the door time ────────────────────────────────────────────────────────

    /**
     * A door time rendered in the DEVICE's timezone is a wrong door time. A buyer
     * who flew in the night before must see the venue's 22:00, not theirs.
     *
     * <p>{@code ignoresTimeZone} alone does not achieve that: Apple reads it as
     * "render in the zone attached to the value", so the value has to carry the
     * venue's offset. A bare UTC instant with the flag on renders 20:00 for a
     * 22:00 Paris door — a wrong answer that looks like a working feature.
     */
    @Test
    void theDoorTimeCarriesTheVenueOffsetAndIgnoresTheDeviceTimezone() throws Exception {
        JsonNode when = fieldByKey(passJson(fixture()), "secondaryFields", "when");

        assertThat(when.path("value").asText()).isEqualTo("2026-06-15T22:00:00+02:00");
        assertThat(when.path("ignoresTimeZone").asBoolean())
                .as("without this iOS converts the instant into the device's zone")
                .isTrue();
        assertThat(when.path("dateStyle").asText()).isEqualTo("PKDateStyleMedium");
        assertThat(when.path("timeStyle").asText()).isEqualTo("PKDateStyleShort");
    }

    /**
     * Latent 500 closed earlier in this branch, pinned here at the pass-content
     * level: {@code ZoneId.of()} on a malformed {@code events.timezone} used to
     * run outside {@code generatePass}'s try block and escape unhandled. It now
     * degrades to UTC — not to the host's zone, which would make the door time
     * depend on which machine rendered it.
     */
    @Test
    void aMalformedTimezoneRendersTheDoorTimeInUtcRatherThanThrowing() throws Exception {
        Fixture f = fixture();
        f.event.setTimezone("Not/AZone");

        JsonNode pass = passJson(f);
        assertThat(pass.path("serialNumber").asText()).isEqualTo("TKT_X");
        assertThat(fieldByKey(pass, "secondaryFields", "when").path("value").asText())
                .isEqualTo("2026-06-15T20:00:00Z");
    }

    // ── the back of the pass ─────────────────────────────────────────────────

    @Test
    void venueAddressIsOnTheBackAndTappableIntoMaps() throws Exception {
        JsonNode venue = fieldByKey(passJson(fixture()), "backFields", "address");

        assertThat(venue.path("value").asText())
                .isEqualTo("12 Quai de la Gare, 75013 Paris, FR");
        assertThat(venue.path("dataDetectorTypes").get(0).asText())
                .isEqualTo("PKDataDetectorTypeAddress");
    }

    /**
     * Every address column is nullable or defaults to "". An "Address" row with
     * nothing under it is worse than no row, so the field is omitted rather than
     * emitted empty.
     */
    @Test
    void anEventWithNoAddressGetsNoAddressField() throws Exception {
        Fixture f = fixture();
        f.event.setVenueStreet("");
        f.event.setVenuePostalCode("");
        f.event.setVenueCity("");
        f.event.setVenueCountry(null);

        assertThat(hasFieldKey(passJson(f), "backFields", "address")).isFalse();
    }

    @Test
    void theBackCarriesTheOrderTokenAndALinkBackToTheTicketPage() throws Exception {
        JsonNode pass = passJson(fixture());

        assertThat(fieldByKey(pass, "backFields", "order").path("value").asText())
                .isEqualTo("ORD_TOKEN_1");

        JsonNode manage = fieldByKey(pass, "backFields", "manage");
        assertThat(manage.path("value").asText())
                .isEqualTo("https://app.imin.wtf/tickets/TKT_X");
        assertThat(manage.path("dataDetectorTypes").get(0).asText())
                .isEqualTo("PKDataDetectorTypeLink");
    }

    // ── presentation ─────────────────────────────────────────────────────────

    @Test
    void brandColoursAreSetSoThePassIsNotDefaultWhite() throws Exception {
        JsonNode pass = passJson(fixture());

        assertThat(pass.path("backgroundColor").asText()).isEqualTo("rgb(8,7,13)");
        assertThat(pass.path("foregroundColor").asText()).isEqualTo("rgb(244,242,251)");
        assertThat(pass.path("labelColor").asText()).isEqualTo("rgb(154,150,173)");
    }

    /**
     * The organizer's name belongs on the ticket; {@code organizationName} stays
     * "imin" because that is the merchant of record, and Apple shows it in the
     * pass's system-level attribution.
     */
    @Test
    void theOrganizerBrandNameIsTheLogoTextAndIminStaysTheOrganization() throws Exception {
        JsonNode pass = passJson(fixture());

        assertThat(pass.path("logoText").asText()).isEqualTo("Saturn Collective");
        assertThat(pass.path("organizationName").asText()).isEqualTo("imin");
    }

    /** {@code organizations.brand_name} is nullable — most orgs have never set one. */
    @Test
    void aBrandlessOrganizerFallsBackToImin() throws Exception {
        Fixture f = fixture();
        f.organization.setBrandName(null);

        assertThat(passJson(f).path("logoText").asText()).isEqualTo("imin");
    }

    /**
     * Groups every ticket on one order into one stack in Wallet instead of N
     * loose cards. Order id, not event id: a buyer at the same event on two
     * separate orders genuinely has two stacks.
     */
    @Test
    void ticketsFromOneOrderShareAGroupingIdentifier() throws Exception {
        Fixture f = fixture();

        assertThat(passJson(f).path("groupingIdentifier").asText())
                .isEqualTo("order-" + f.order.getId());
    }

    // ── semantics ────────────────────────────────────────────────────────────

    @Test
    void semanticsCarryTheEventVenueGenreAndDates() throws Exception {
        JsonNode semantics = passJson(fixture()).path("semantics");

        assertThat(semantics.path("eventName").asText()).isEqualTo("Saturn Night");
        assertThat(semantics.path("venueName").asText()).isEqualTo("Le Petit Bain");
        assertThat(semantics.path("genre").asText()).isEqualTo("Techno");
        assertThat(semantics.path("eventType").asText()).isEqualTo("PKEventTypeGeneric");
        assertThat(semantics.hasNonNull("eventStartDate")).isTrue();
        assertThat(semantics.hasNonNull("eventEndDate")).isTrue();
    }

    /**
     * No fabricated data. Apple documents {@code venueRoom}, {@code venueEntrance},
     * {@code venuePhoneNumber}, {@code performerNames} and {@code seats} for event
     * tickets; {@code Event} has a backing column for none of them, so the pass
     * ships without them rather than with a plausible-looking guess.
     */
    @Test
    void semanticTagsWithNoBackingColumnAreAbsentRatherThanInvented() throws Exception {
        JsonNode semantics = passJson(fixture()).path("semantics");

        assertThat(semantics.has("venueRoom")).isFalse();
        assertThat(semantics.has("venueEntrance")).isFalse();
        assertThat(semantics.has("venuePhoneNumber")).isFalse();
        assertThat(semantics.has("performerNames")).isFalse();
        assertThat(semantics.has("seats")).isFalse();
    }

    /** A blank genre is "" in the column, not null — it must not reach the pass. */
    @Test
    void aBlankGenreIsOmittedFromSemantics() throws Exception {
        Fixture f = fixture();
        f.event.setGenre("");

        assertThat(passJson(f).path("semantics").has("genre")).isFalse();
    }

    // ── locations ────────────────────────────────────────────────────────────

    /**
     * Location is opt-in data: {@code IMIN_GEOCODING_ENABLED} is false by default
     * and {@code events.venue_latitude/longitude} stay NULL. Emitting a locations
     * array with nulls in it produces a pass Apple rejects, so the absence is
     * handled rather than assumed away.
     */
    @Test
    void locationsAreOmittedEntirelyWhenTheVenueHasNoCoordinates() throws Exception {
        JsonNode pass = passJson(fixture());

        // An EMPTY array, not an absent key: jpasskit initialises locations,
        // beacons, relevantDates and associatedApps to Collections.emptyList()
        // and the signing mapper is Include.NON_NULL, so the key is always
        // present. What must never appear is an entry — a locations element with
        // null coordinates is a pass Apple rejects.
        assertThat(pass.path("locations")).isEmpty();
        assertThat(pass.path("semantics").has("venueLocation")).isFalse();
    }

    @Test
    void locationsArePresentWhenTheVenueHasCoordinates() throws Exception {
        Fixture f = fixture();
        f.event.setVenueLatitude(48.8330);
        f.event.setVenueLongitude(2.3760);

        JsonNode pass = passJson(f);
        assertThat(pass.path("locations")).hasSize(1);
        assertThat(pass.path("locations").get(0).path("latitude").asDouble()).isEqualTo(48.8330);
        assertThat(pass.path("locations").get(0).path("relevantText").asText())
                .isEqualTo("Le Petit Bain");
        assertThat(pass.path("semantics").path("venueLocation").path("longitude").asDouble())
                .isEqualTo(2.3760);
    }

    // ── the dead-ticket refusal ──────────────────────────────────────────────

    /**
     * <b>The correctness defect this task closes.</b> {@code generatePass} never
     * looked at {@code ticket.state}, so a refunded ticket was signed into a real,
     * official-looking pass. It is not a security hole — {@code TicketRedeemService}
     * re-reads state in a transaction and the gate paints refunded red — but
     * handing someone a fresh artifact for a ticket we already refunded puts them
     * at a door believing they are fine.
     */
    @Test
    void aRefundedTicketIsRefusedRatherThanSigned() {
        Fixture f = fixture();
        f.ticket.setState(Ticket.STATE_REFUNDED);

        assertThatThrownBy(f::generate)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status().value()).isEqualTo(409);
                    assertThat(api.code()).isEqualTo(ErrorCode.TICKET_ALREADY_REFUNDED);
                });
    }

    @Test
    void aRevokedTicketIsRefusedWithItsOwnCode() {
        Fixture f = fixture();
        f.ticket.setState(Ticket.STATE_REVOKED);

        assertThatThrownBy(f::generate)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    /**
     * A redeemed ticket is NOT refused. Re-adding the pass after the door is
     * harmless — the gate maps {@code already_redeemed} to amber, not red — and a
     * buyer whose phone died mid-queue must not be locked out of their own ticket
     * record.
     */
    @Test
    void aRedeemedTicketStillMintsAPass() throws Exception {
        Fixture f = fixture();
        f.ticket.setState(Ticket.STATE_REDEEMED);

        assertThat(passJson(f).path("serialNumber").asText()).isEqualTo("TKT_X");
    }

    /** Legacy rows carry "pre" as a synonym for "issued". Neither is dead. */
    @Test
    void theLegacyPreStateIsStillLive() throws Exception {
        Fixture f = fixture();
        f.ticket.setState("pre");

        assertThat(passJson(f).path("serialNumber").asText()).isEqualTo("TKT_X");
    }

    /** The same rule as a predicate, for the response contract that gates the CTA. */
    @Test
    void isLiveAgreesWithAssertLiveOnEveryState() {
        assertThat(WalletEligibility.isLive(ticketIn(Ticket.STATE_ISSUED))).isTrue();
        assertThat(WalletEligibility.isLive(ticketIn(Ticket.STATE_REDEEMED))).isTrue();
        assertThat(WalletEligibility.isLive(ticketIn("pre"))).isTrue();
        assertThat(WalletEligibility.isLive(ticketIn(Ticket.STATE_REFUNDED))).isFalse();
        assertThat(WalletEligibility.isLive(ticketIn(Ticket.STATE_REVOKED))).isFalse();
    }

    // ── fixture + helpers ────────────────────────────────────────────────────

    private static Ticket ticketIn(String state) {
        Ticket t = new Ticket();
        t.setState(state);
        return t;
    }

    /**
     * Mutable rows plus a service wired over them. Real key material from
     * {@link WalletTestCerts} — never a stub signer: a pass a stub "signed" proves
     * nothing about a pass Apple will accept.
     */
    private static final class Fixture {
        final Ticket ticket = new Ticket();
        final Order order = new Order();
        final Event event = new Event();
        final Organization organization = new Organization();
        private final AppleWalletPassService service;

        Fixture() {
            UUID orderId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            ticket.setToken("TKT_X");
            ticket.setOrderId(orderId);
            ticket.setEventId(eventId);
            ticket.setTierId(UUID.randomUUID());
            ticket.setTierName("GA");
            ticket.setState(Ticket.STATE_ISSUED);

            order.setId(orderId);
            order.setToken("ORD_TOKEN_1");
            order.setEventId(eventId);
            order.setOrgId(orgId);

            event.setId(eventId);
            event.setName("Saturn Night");
            event.setGenre("Techno");
            event.setStartsAt(DOORS);
            event.setTimezone("Europe/Paris");
            event.setVenueName("Le Petit Bain");
            event.setVenueStreet("12 Quai de la Gare");
            event.setVenuePostalCode("75013");
            event.setVenueCity("Paris");
            event.setVenueCountry("FR");

            organization.setId(orgId);
            organization.setBrandName("Saturn Collective");

            TicketRepository tickets = mock(TicketRepository.class);
            OrderRepository orders = mock(OrderRepository.class);
            EventRepository events = mock(EventRepository.class);
            OrganizationRepository orgs = mock(OrganizationRepository.class);
            when(tickets.findByToken("TKT_X")).thenReturn(Optional.of(ticket));
            when(orders.findById(orderId)).thenReturn(Optional.of(order));
            when(events.findById(eventId)).thenReturn(Optional.of(event));
            when(orgs.findById(orgId)).thenReturn(Optional.of(organization));

            WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
            AppleWalletProperties props = new AppleWalletProperties();
            props.setPassTypeId("pass.test.imin");
            props.setTeamId("TESTTEAMID");
            props.setCertP12Base64(bundle.p12Base64());
            props.setCertPassword(bundle.password());
            props.setWwdrPemBase64(bundle.wwdrPemBase64());

            TicketProperties tp = new TicketProperties();
            tp.setSigningSecret("applepasscontent-test-signing-secret");

            service = new AppleWalletPassService(props, tickets, orders, events, orgs,
                    new QrPayloadSigner(tp), new EmailProperties());
        }

        byte[] generate() {
            return service.generatePass("TKT_X");
        }
    }

    private static Fixture fixture() {
        return new Fixture();
    }

    private static JsonNode passJson(Fixture f) throws Exception {
        return new ObjectMapper().readTree(readZipEntry(f.generate(), "pass.json"));
    }

    private static JsonNode fieldByKey(JsonNode pass, String group, String key) {
        for (JsonNode n : pass.path("eventTicket").path(group)) {
            if (key.equals(n.path("key").asText())) return n;
        }
        throw new AssertionError("no " + group + " field with key " + key
                + " in " + pass.path("eventTicket").path(group));
    }

    private static boolean hasFieldKey(JsonNode pass, String group, String key) {
        for (JsonNode n : pass.path("eventTicket").path(group)) {
            if (key.equals(n.path("key").asText())) return true;
        }
        return false;
    }

    private static String readZipEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("Entry not found: " + name);
    }
}
