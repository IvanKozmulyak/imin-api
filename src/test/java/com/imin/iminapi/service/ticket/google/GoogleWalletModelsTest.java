package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What Google actually receives.
 *
 * <p>The value of this layer is entirely in the mapping, and the mapping is only
 * visible after serialisation — a test that asserted on the records would stay
 * green through a field named wrong, a required field omitted, a {@code null}
 * serialised where Google wants absence, and a date rendered in the server's
 * timezone. So the assertions here are on the JSON, in {@code STRICT} mode:
 * every field that appears must be expected and every expected field must
 * appear. An accidental extra field fails this file, which is the point — the
 * one thing worse than a missing field on a pass is an invented one.
 */
class GoogleWalletModelsTest {

    private static final String ISSUER = "3388000000000000000";
    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TOKEN = "abc-DEF_123";
    private static final String QR = "imin1." + TOKEN + ".QUJD";
    private static final String MANAGE = "https://app.imin.wtf/tickets/" + TOKEN;

    /** 2026-06-15T20:00 in Paris. Stored as an instant, rendered with the venue's offset. */
    private static final Instant DOORS = Instant.parse("2026-06-15T18:00:00Z");

    // ── the bodies, pinned whole ─────────────────────────────────────────────

    /**
     * The exact class body. If Google ever rejects a class, this is the string to
     * compare against the error.
     */
    @Test
    void theClassBodyIsExactlyWhatGoogleReceives() throws Exception {
        String json = GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, event(), organization()));

        JSONAssert.assertEquals("""
                {
                  "id": "3388000000000000000.evt_11111111-2222-3333-4444-555555555555",
                  "issuerName": "imin",
                  "reviewStatus": "UNDER_REVIEW",
                  "eventId": "11111111-2222-3333-4444-555555555555",
                  "eventName": {"defaultValue": {"language": "en-US", "value": "Vechirka"}},
                  "venue": {
                    "name": {"defaultValue": {"language": "en-US", "value": "Club Zenith"}},
                    "address": {"defaultValue": {"language": "en-US",
                                "value": "12 Rue de la Nuit, 75011 Paris, FR"}}
                  },
                  "dateTime": {"start": "2026-06-15T20:00:00+02:00", "end": "2026-06-16T08:00:00+02:00"},
                  "hexBackgroundColor": "#08070d",
                  "textModulesData": [
                    {"id": "organiser", "header": "Presented by", "body": "Nocturne"}
                  ]
                }
                """, json, JSONCompareMode.STRICT);
    }

    /** The exact object body. */
    @Test
    void theObjectBodyIsExactlyWhatGoogleReceives() throws Exception {
        String json = GoogleWalletModels.toJson(GoogleWalletModels.eventTicketObject(
                ISSUER, ticket(), event(), QR, MANAGE));

        JSONAssert.assertEquals("""
                {
                  "id": "3388000000000000000.tkt_abc-DEF_123",
                  "classId": "3388000000000000000.evt_11111111-2222-3333-4444-555555555555",
                  "state": "ACTIVE",
                  "barcode": {
                    "type": "QR_CODE",
                    "value": "imin1.abc-DEF_123.QUJD",
                    "alternateText": "abc-DEF_123"
                  },
                  "ticketType": {"defaultValue": {"language": "en-US", "value": "Early Bird"}},
                  "validTimeInterval": {"end": {"date": "2026-06-16T20:00:00+02:00"}},
                  "linksModuleData": {"uris": [
                    {"id": "manage",
                     "uri": "https://app.imin.wtf/tickets/abc-DEF_123",
                     "description": "Manage this ticket"}
                  ]}
                }
                """, json, JSONCompareMode.STRICT);
    }

    // ── reviewStatus: the value that silently breaks object creation ─────────

    /**
     * {@code DRAFT} is the one value that makes every object insert fail, and
     * leaving {@code draft} is one-way. Nothing in this codebase may ever write
     * it.
     */
    @Test
    void theClassIsInsertedUnderReviewAndNeverAsADraft() {
        assertThat(GoogleWalletModels.REVIEW_STATUS).isEqualTo("UNDER_REVIEW");

        String json = GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, event(), organization()));
        assertThat(json)
                .as("a DRAFT class cannot have objects attached, and the transition off "
                        + "draft is one-way — see GoogleWalletProvisioner#ensureClass")
                .doesNotContain("DRAFT")
                .contains("\"reviewStatus\":\"UNDER_REVIEW\"");
    }

    @Test
    void aDraftClassIsRecognisedWhateverCaseGoogleReturnsIt() {
        assertThat(GoogleWalletModels.reviewStatusOf("{\"reviewStatus\":\"draft\"}"))
                .isEqualTo("DRAFT");
        assertThat(GoogleWalletModels.reviewStatusOf("{\"reviewStatus\":\"UNDER_REVIEW\"}"))
                .isEqualTo("UNDER_REVIEW");
    }

    /**
     * The class Google hands back carries fields this codebase does not model —
     * it may have been created by an older deploy or by a human in the console.
     * Reading one field must not become a strict binding that fails on exactly
     * the class we are trying to diagnose.
     */
    @Test
    void reviewStatusSurvivesAClassCarryingFieldsWeDoNotModel() {
        assertThat(GoogleWalletModels.reviewStatusOf("""
                {"kind":"walletobjects#eventTicketClass","reviewStatus":"APPROVED",
                 "review":{"comments":[]},"messages":[],"allowMultipleUsersPerObject":true}
                """)).isEqualTo("APPROVED");
    }

    @Test
    void anUnreadableClassBodyYieldsNoStatusRatherThanAnException() {
        assertThat(GoogleWalletModels.reviewStatusOf(null)).isNull();
        assertThat(GoogleWalletModels.reviewStatusOf("")).isNull();
        assertThat(GoogleWalletModels.reviewStatusOf("not json at all")).isNull();
        assertThat(GoogleWalletModels.reviewStatusOf("{\"reviewStatus\":42}")).isNull();
    }

    // ── ids ──────────────────────────────────────────────────────────────────

    @Test
    void idsAreIssuerPrefixedAndUseThePrefixesTheJwtWillCarry() {
        assertThat(GoogleWalletModels.classId(ISSUER, EVENT_ID))
                .isEqualTo(ISSUER + ".evt_" + EVENT_ID);
        assertThat(GoogleWalletModels.objectId(ISSUER, TOKEN))
                .isEqualTo(ISSUER + ".tkt_" + TOKEN);
    }

    /**
     * <b>The plan's claim was wrong and the conclusion happened to survive.</b> It
     * said "ticket tokens are {@code TKT_<uuid>}" — that is the shape of a test
     * fixture, not of production data. {@code PaidCheckoutService.randomToken()}
     * emits 24 random bytes as base64<b>url</b> without padding, so the alphabet
     * is {@code A-Za-z0-9-_}: still inside Google's {@code [A-Za-z0-9._-]}, but by
     * exactly the two characters that distinguish base64url from base64. This
     * drives real tokens rather than trusting either sentence.
     */
    @Test
    void everyShapeOfRealTicketTokenSurvivesGooglesIdCharset() {
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 500; i++) {
            byte[] bytes = new byte[24];
            rng.nextBytes(bytes);
            // Exactly PaidCheckoutService.randomToken().
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            assertThat(GoogleWalletModels.objectId(ISSUER, token))
                    .matches("[A-Za-z0-9._-]+");
        }
    }

    /**
     * The guard is not vacuous. Had the token generator used plain base64 — one
     * character different in {@code PaidCheckoutService} — every id would carry
     * {@code +} or {@code /} and Google would reject it. This must fail here,
     * with an error naming the problem, rather than as an opaque 400 on a
     * buyer's request.
     */
    @Test
    void aTokenOutsideGooglesCharsetIsRefusedHereRatherThanSent() {
        assertThatThrownBy(() -> GoogleWalletModels.objectId(ISSUER, "ab+cd/ef=="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[A-Za-z0-9._-]")
                // The identifier is the ticket token, which is the bearer
                // credential for that ticket. It must not appear in a message
                // that will end up in a log.
                .hasMessageNotContaining("ab+cd/ef");
    }

    @Test
    void aBlankIssuerIdCannotProduceAnId() {
        assertThatThrownBy(() -> GoogleWalletModels.classId("", EVENT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GOOGLE_WALLET_ISSUER_ID");
    }

    // ── the barcode is the one canonical payload ─────────────────────────────

    /**
     * The pkpass, the emailed PNG, the web QR and now the Google object all carry
     * the same signed string, so the gate scanner sees identical bytes however
     * the buyer presented the ticket.
     */
    @Test
    void theBarcodeCarriesTheSameSignedPayloadAsThePkpassAndTheEmail() throws Exception {
        JSONObject object = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketObject(ISSUER, ticket(), event(), QR, MANAGE)));

        JSONObject barcode = object.getJSONObject("barcode");
        assertThat(barcode.getString("type")).isEqualTo("QR_CODE");
        assertThat(barcode.getString("value")).isEqualTo(QR).startsWith("imin1.");
        assertThat(barcode.getString("alternateText")).isEqualTo(TOKEN);
    }

    @Test
    void aLiveTicketIsActive() throws Exception {
        JSONObject object = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketObject(ISSUER, ticket(), event(), QR, MANAGE)));
        assertThat(object.getString("state")).isEqualTo("ACTIVE");
    }

    // ── time ─────────────────────────────────────────────────────────────────

    /**
     * The door-time invariant, the same one {@code ignoresTimeZone} protects on
     * the Apple pass. Google renders an offset-carrying string in the offset it
     * was given, so a UTC instant would put 18:00 on a ticket for a 20:00 Paris
     * door — a wrong door time that looks like a working feature.
     */
    @Test
    void theEventStartCarriesTheVenuesOffsetAndNotTheServers() throws Exception {
        JSONObject cls = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, event(), organization())));

        assertThat(cls.getJSONObject("dateTime").getString("start"))
                .isEqualTo("2026-06-15T20:00:00+02:00");
    }

    /**
     * A bad {@code events.timezone} falls back to UTC — the column's own default
     * — and never to {@code ZoneId.systemDefault()}. A door time that depends on
     * which machine rendered it is wrong on every machine but one.
     */
    @Test
    void anUnusableTimezoneRendersInUtcAndNotTheServersZone() throws Exception {
        Event e = event();
        e.setTimezone("Mars/Olympus_Mons");

        JSONObject cls = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, e, organization())));

        assertThat(cls.getJSONObject("dateTime").getString("start"))
                .isEqualTo("2026-06-15T18:00:00Z");
    }

    /**
     * {@code validTimeInterval} carries an end and no start, deliberately. It is
     * a <em>validity</em> window, not Apple's relevancy hint: a start in the
     * future would mark the pass not-yet-valid for the whole period in which the
     * buyer adds it.
     */
    @Test
    void theValidityWindowIsAnExpiryAndNotAWindowThatStartsInTheFuture() throws Exception {
        JSONObject object = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketObject(ISSUER, ticket(), event(), QR, MANAGE)));

        JSONObject validity = object.getJSONObject("validTimeInterval");
        assertThat(validity.has("start"))
                .as("a start in the future would read as 'not valid yet' for most of the "
                        + "pass's life — see GoogleWalletModels#eventTicketObject")
                .isFalse();
        assertThat(validity.getJSONObject("end").getString("date"))
                .isEqualTo("2026-06-16T20:00:00+02:00");
    }

    /**
     * The two wallets must age a pass out at the same moment. Google's window is
     * computed in this package and Apple's inside {@code AppleWalletPassService};
     * nothing but this test connects them, so drift would otherwise ship as one
     * ticket that expires twelve hours apart on two phones.
     */
    @Test
    void theGoogleAndAppleAgeingWindowsAreTheSameDurations() throws Exception {
        assertThat(GoogleWalletModels.ASSUMED_RUN).isEqualTo(appleDuration("ASSUMED_RUN"));
        assertThat(GoogleWalletModels.EXPIRY_SLACK).isEqualTo(appleDuration("EXPIRY_SLACK"));
    }

    private static Duration appleDuration(String name) throws Exception {
        Field f = AppleWalletPassService.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Duration) f.get(null);
    }

    // ── absence, never invention ─────────────────────────────────────────────

    /**
     * Google requires a venue to carry <b>both</b> a name and an address. An
     * event with a venue name and no address must drop the whole block rather
     * than send half of one and take a 400 the buyer sees as a broken button.
     */
    @Test
    void aVenueWithNoAddressIsOmittedEntirelyRatherThanSentHalfBuilt() throws Exception {
        Event e = event();
        e.setVenueStreet("");
        e.setVenuePostalCode("");
        e.setVenueCity("");
        e.setVenueCountry(null);

        JSONObject cls = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, e, organization())));

        assertThat(cls.has("venue")).isFalse();
        assertThat(cls.getJSONObject("eventName").getJSONObject("defaultValue").getString("value"))
                .as("the rest of the class is unaffected")
                .isEqualTo("Vechirka");
    }

    @Test
    void aVenueWithNoNameIsOmittedTooEvenWhenTheAddressIsComplete() throws Exception {
        Event e = event();
        e.setVenueName(null);

        JSONObject cls = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, e, organization())));

        assertThat(cls.has("venue")).isFalse();
    }

    /**
     * The no-fabricated-data rule, in its wallet form: a field with nothing
     * behind it is absent, not {@code null} and not a plausible default. This
     * mirrors {@code semanticTagsWithNoBackingColumnAreAbsentRatherThanInvented}
     * on the Apple side.
     */
    @Test
    void fieldsWithNothingBehindThemAreAbsentRatherThanNullOrInvented() throws Exception {
        Event bare = new Event();
        bare.setId(EVENT_ID);
        bare.setName("");
        bare.setVenueName(null);
        bare.setVenueStreet("");
        bare.setVenuePostalCode("");
        bare.setVenueCity("");
        bare.setVenueCountry(null);
        bare.setStartsAt(null);
        bare.setEndsAt(null);

        Ticket t = new Ticket();
        t.setToken(TOKEN);
        t.setTierName(null);

        String classJson = GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, bare, null));
        String objectJson = GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketObject(ISSUER, t, bare, QR, ""));

        assertThat(classJson).doesNotContain("null");
        assertThat(objectJson).doesNotContain("null");

        JSONObject cls = new JSONObject(classJson);
        assertThat(cls.has("eventName")).as("no name ⇒ no eventName").isFalse();
        assertThat(cls.has("venue")).isFalse();
        assertThat(cls.has("dateTime")).as("a draft event has no start to render").isFalse();
        assertThat(cls.has("textModulesData"))
                .as("no organization row ⇒ no 'Presented by' module, not an empty one").isFalse();
        // The four Google requires are still there.
        assertThat(cls.getString("id")).isNotBlank();
        assertThat(cls.getString("issuerName")).isEqualTo("imin");
        assertThat(cls.getString("reviewStatus")).isEqualTo("UNDER_REVIEW");

        JSONObject object = new JSONObject(objectJson);
        assertThat(object.has("ticketType")).as("no tier name ⇒ no ticketType").isFalse();
        assertThat(object.has("validTimeInterval"))
                .as("no start ⇒ no derived expiry rather than an invented one").isFalse();
        assertThat(object.has("linksModuleData")).as("no buyer site ⇒ no links module").isFalse();
        assertThat(object.getString("state")).isEqualTo("ACTIVE");
    }

    /**
     * <b>The plan asked for {@code posterUrl} null-safety on a {@code heroImage}
     * this build does not send at all, so the honest assertion is that the poster
     * never reaches Google in either direction.</b> Two reasons, both in
     * {@link GoogleWalletModels}'s Javadoc: a 4:5 poster in Google's ~3:1 banner
     * is a horizontal slice through the middle of the artwork, and Google fetches
     * the URL when the <em>class</em> is inserted — a poster that has since moved
     * would fail the class insert permanently, for every ticket to that event,
     * because nothing ever re-creates a class. Apple reached the same place from
     * the other end when Task 4 cut the poster event ticket.
     *
     * <p>If a hero image is ever wanted, this test is the one to delete, and
     * deleting it should require answering both objections.
     */
    @Test
    void theEventPosterIsNotSentAsAHeroImage_setOrUnset() throws Exception {
        Event withPoster = event();
        withPoster.setPosterUrl("https://media.imin.wtf/ai-posters/abc.png");
        Event withoutPoster = event();
        withoutPoster.setPosterUrl(null);

        for (Event e : new Event[] {withPoster, withoutPoster}) {
            String json = GoogleWalletModels.toJson(
                    GoogleWalletModels.eventTicketClass(ISSUER, e, organization()));
            JSONObject cls = new JSONObject(json);
            assertThat(cls.has("heroImage")).isFalse();
            assertThat(cls.has("logo")).as("no publicly hosted issuer logo exists").isFalse();
            assertThat(json).doesNotContain("ai-posters");
        }
    }

    @Test
    void aDeclaredEndBeatsTheAssumedNightLength() throws Exception {
        Event e = event();
        e.setEndsAt(DOORS.plus(Duration.ofHours(4)));

        JSONObject cls = new JSONObject(GoogleWalletModels.toJson(
                GoogleWalletModels.eventTicketClass(ISSUER, e, organization())));

        assertThat(cls.getJSONObject("dateTime").getString("end"))
                .isEqualTo("2026-06-16T00:00:00+02:00");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Event event() {
        Event e = new Event();
        e.setId(EVENT_ID);
        e.setName("Vechirka");
        e.setVenueName("Club Zenith");
        e.setVenueStreet("12 Rue de la Nuit");
        e.setVenuePostalCode("75011");
        e.setVenueCity("Paris");
        // Two letters, because events.venue_country is length = 2 and the Apple
        // fixture uses the same. A fixture that could not be stored is a fixture
        // that proves nothing about production.
        e.setVenueCountry("FR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(DOORS);
        e.setEndsAt(null);
        return e;
    }

    private static Ticket ticket() {
        Ticket t = new Ticket();
        t.setToken(TOKEN);
        t.setTierName("Early Bird");
        return t;
    }

    private static Organization organization() {
        Organization o = new Organization();
        o.setBrandName("Nocturne");
        return o;
    }
}
