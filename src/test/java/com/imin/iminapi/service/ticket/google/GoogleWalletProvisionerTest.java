package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Idempotency, ordering and error mapping — the three things that decide whether
 * a buyer gets a pass, a truthful refusal, or a half-created resource that
 * nothing will ever clean up.
 *
 * <h2>The HTTP layer is mocked; the client is not</h2>
 *
 * <p>{@link GoogleWalletApiClient} is the real one, bound to a
 * {@link MockRestServiceServer}. A Mockito double of it would let the assertions
 * about <em>what Google is sent</em> pass vacuously, and it would let "no call is
 * made while the gate is closed" become "no call is made on an object that could
 * not make one anyway". Here an unexpected request fails the mock server
 * outright, so "nothing was sent" is an assertion rather than a hope.
 */
class GoogleWalletProvisionerTest {

    private static final String ISSUER = "3388000000000000000";
    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TOKEN = "abc-DEF_123";
    private static final String QR = "imin1." + TOKEN + ".QUJD";

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CLASS_URL =
            "https://walletobjects.googleapis.com/walletobjects/v1/eventTicketClass";
    private static final String OBJECT_URL =
            "https://walletobjects.googleapis.com/walletobjects/v1/eventTicketObject";
    private static final String CLASS_ID = ISSUER + ".evt_" + EVENT_ID;
    private static final String OBJECT_ID = ISSUER + ".tkt_" + TOKEN;

    private static final GoogleTestKeys.Bundle KEYS = GoogleTestKeys.generate();

    private MockRestServiceServer server;
    private GoogleWalletProvisioner provisioner;

    @BeforeEach
    void setUp() {
        provisioner = provisionerFor(liveProperties());
    }

    /**
     * A provisioner over a real client over a mocked transport. Rebuilding it
     * per test also resets the per-JVM class memo, which is state a later test
     * must not inherit.
     */
    private GoogleWalletProvisioner provisionerFor(GoogleWalletProperties props) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GoogleWalletApiClient api = new GoogleWalletApiClient(props, builder.build());
        return new GoogleWalletProvisioner(props, api, new EmailProperties());
    }

    private static GoogleWalletProperties liveProperties() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId(ISSUER);
        p.setServiceAccountJsonBase64(KEYS.serviceAccountJsonBase64());
        return p;
    }

    private void expectToken() {
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(withSuccess(
                "{\"access_token\":\"ya29.t\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));
    }

    // ── the happy path, and the bodies that go with it ───────────────────────

    /**
     * Read the class, create it, create the object, hand back the object id —
     * which is the only thing the save JWT ever carries.
     */
    @Test
    void aFirstSaveCreatesTheClassThenTheObjectAndReturnsTheObjectId() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.id").value(CLASS_ID))
                .andExpect(jsonPath("$.reviewStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.issuerName").value("imin"))
                .andExpect(jsonPath("$.eventName.defaultValue.value").value("Vechirka"))
                .andRespond(withStatus(HttpStatus.OK));
        server.expect(once(), requestTo(OBJECT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.id").value(OBJECT_ID))
                .andExpect(jsonPath("$.classId").value(CLASS_ID))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.barcode.value").value(QR))
                .andExpect(jsonPath("$.barcode.alternateText").value(TOKEN))
                .andExpect(jsonPath("$.linksModuleData.uris[0].uri")
                        .value("https://app.imin.wtf/tickets/" + TOKEN))
                .andRespond(withStatus(HttpStatus.OK));

        assertThat(provisioner.provision(ticket(), event(), organization(), QR))
                .isEqualTo(OBJECT_ID);
        server.verify();
    }

    /**
     * The second buyer for the same event does not re-read the class. The class
     * expectation is registered {@code once()} and two objects are inserted, so a
     * second GET would fail the mock server.
     */
    @Test
    void aSecondTicketForTheSameEventDoesNotTouchTheClassAgain() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(twice(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.OK));

        provisioner.provision(ticket(), event(), organization(), QR);

        Ticket second = ticket();
        second.setToken("zzz_SECOND-9");
        provisioner.provision(second, event(), organization(), "imin1.zzz_SECOND-9.QUJD");

        server.verify();
    }

    /**
     * A class Google already holds is used as it stands. Nothing patches a class
     * (ADR-0004), so re-inserting would be both pointless and a fan-out to every
     * existing holder of a ticket for that event.
     */
    @Test
    void anExistingApprovedClassIsUsedAndNeverReInserted() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID)).andRespond(withSuccess(
                "{\"id\":\"" + CLASS_ID + "\",\"reviewStatus\":\"APPROVED\"}",
                MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.OK));

        assertThat(provisioner.provision(ticket(), event(), organization(), QR))
                .isEqualTo(OBJECT_ID);
        server.verify();
    }

    /**
     * Two buyers, two replicas, one event, the same second: whoever loses the
     * race gets a 409 and must still end up with a working pass.
     */
    @Test
    void aConflictCreatingTheClassIsSuccessBecauseAnotherReplicaWonTheRace() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(once(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.OK));

        assertThat(provisioner.provision(ticket(), event(), organization(), QR))
                .isEqualTo(OBJECT_ID);
        server.verify();
    }

    /** The buyer who taps Add twice gets the same pass twice, not a 503. */
    @Test
    void aConflictCreatingTheObjectIsSuccessBecauseTheBuyerAlreadySavedIt() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(once(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(provisioner.provision(ticket(), event(), organization(), QR))
                .isEqualTo(OBJECT_ID);
        server.verify();
    }

    // ── DRAFT: the state that cannot have objects attached ───────────────────

    /**
     * A {@code DRAFT} class cannot have objects attached, so the object insert
     * would fail with an error naming the <em>object</em> — pointing at the wrong
     * resource, on a buyer's request, at a door. It is refused here instead, and
     * the object insert is never attempted: an unexpected POST would fail the
     * mock server.
     *
     * <p>Refused rather than promoted. Leaving {@code draft} is one-way and this
     * code never writes it, so a draft class was put there deliberately by a
     * human — not a transition to make on their behalf inside a GET.
     */
    @Test
    void aDraftClassIsRefusedAndTheObjectInsertIsNeverAttempted() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID)).andRespond(withSuccess(
                "{\"id\":\"" + CLASS_ID + "\",\"reviewStatus\":\"DRAFT\"}",
                MediaType.APPLICATION_JSON));

        assertThat(refusal(() -> provisioner.provision(ticket(), event(), organization(), QR))
                .status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    /** A draft class is not remembered, so promoting it in the console takes effect
     * without waiting for a restart. */
    @Test
    void aDraftClassIsNotMemoisedSoAPromotionTakesEffectImmediately() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID)).andRespond(withSuccess(
                "{\"reviewStatus\":\"DRAFT\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID)).andRespond(withSuccess(
                "{\"reviewStatus\":\"APPROVED\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.OK));

        refusal(() -> provisioner.provision(ticket(), event(), organization(), QR));
        assertThat(provisioner.provision(ticket(), event(), organization(), QR))
                .isEqualTo(OBJECT_ID);
        server.verify();
    }

    // ── failure never becomes a 500, and never a half-created pass ───────────

    /**
     * A class that will not be created must stop the sequence. Attempting the
     * object anyway would create a Google object pointing at a class that does
     * not exist — a resource nothing ever deletes, for a pass that can never
     * render.
     */
    @Test
    void aFailureCreatingTheClassNeverGoesOnToCreateTheObject() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("backend error"));

        ApiException e = refusal(() -> provisioner.provision(ticket(), event(), organization(), QR));
        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        server.verify();
    }

    @Test
    void aFailureReadingTheClassIs503AndStopsThere() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(refusal(() -> provisioner.provision(ticket(), event(), organization(), QR))
                .status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void aFailureCreatingTheObjectIs503AndNot500() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(once(), requestTo(OBJECT_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(refusal(() -> provisioner.provision(ticket(), event(), organization(), QR))
                .status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    // ── the closed-wallet property: 503, and no socket ───────────────────────
    //
    // These three are about the OUTCOME a closed wallet produces, in the wiring
    // production actually uses. Two independent guards deliver it — the
    // provisioner's config gate and GoogleWalletApiClient.accessToken()'s own
    // no-credential check — and which one fires is not what is asserted here.
    // So aDisabledWallet… and aConfiguredButUnparseableCredential… both survive
    // the provisioner's gate being deleted, while anUnconfiguredWallet… does not
    // (it reaches a null issuer id). The section after this one is where the gate
    // itself is pinned. Said out loud because an audit found it and it is not
    // visible from the names: a reader who assumes these cover the gate is wrong.

    /**
     * <b>Nothing reaches Google, and therefore nothing reaches a buyer, while the
     * wallet is off.</b> Not "a call is made and its result discarded" — no HTTP
     * request is prepared at all, which {@code server.verify()} proves because
     * the mock server has no expectations and fails on any request.
     *
     * <p><b>What this does not pin.</b> With {@code GOOGLE_WALLET_ENABLED=false}
     * the client built from the same properties parses no credential, so
     * {@code accessToken()} refuses with the same 503 before opening a socket.
     * Delete the provisioner's gate and this test stays green. That is a true
     * and desirable redundancy — the property is what a buyer experiences, and
     * it holds twice over — but it is not evidence about the gate. See
     * {@link #theProvisionersOwnGateRefusesEvenWhenTheClientCouldHaveCalled}.
     */
    @Test
    void aDisabledWalletMakesNoHttpCallAtAllAndRefusesWith503() {
        GoogleWalletProperties off = liveProperties();
        off.setEnabled(false);
        GoogleWalletProvisioner gated = provisionerFor(off);

        assertThat(refusal(() -> gated.provision(ticket(), event(), organization(), QR)).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void anUnconfiguredWalletMakesNoHttpCallAtAll() {
        GoogleWalletProvisioner gated = provisionerFor(new GoogleWalletProperties());

        assertThat(refusal(() -> gated.provision(ticket(), event(), organization(), QR)).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    /**
     * Enabled, issuer set, and a credential that will not parse. This is the
     * state {@code fullyConfigured()} alone calls open, and it is exactly the
     * Apple-side defect that produced a permanent 503 reported as "not
     * configured".
     *
     * <p>Same caveat as above, and here it is sharper: this state closes both
     * guards at once, because the client and the provisioner parse the same
     * broken credential. Deleting {@code !api.isUsable()} from the provisioner's
     * gate leaves this green. The half-gate is pinned by
     * {@link #aCredentialThatWillNotParseIs503AndNotA500EvenInsideATransaction}.
     */
    @Test
    void aConfiguredButUnparseableCredentialMakesNoHttpCallEither() {
        GoogleWalletProvisioner gated = provisionerFor(unparseableCredential());

        assertThat(refusal(() -> gated.provision(ticket(), event(), organization(), QR)).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    // ── the gate itself, one test per half ───────────────────────────────────

    /**
     * <b>{@code props.fullyConfigured()}, isolated from every other guard.</b>
     *
     * <p>The client here is staged with a credential that <em>does</em> parse, so
     * its own no-credential check cannot fire. Production never pairs a disabled
     * wallet with a usable client — the client is built from the same properties
     * — and that is exactly why the pairing has to be built by hand: it is the
     * only arrangement in which the provisioner's gate is the sole thing
     * standing between this call and a socket.
     *
     * <p>Delete {@code !props.fullyConfigured()} and the call runs on to
     * {@code accessToken()}, which now has a key, signs an assertion and posts it
     * to Google's token endpoint — an unexpected request the mock server fails on.
     * That is the red this test is worth, and it is the red the two tests above
     * do not produce.
     */
    @Test
    void theProvisionersOwnGateRefusesEvenWhenTheClientCouldHaveCalled() {
        GoogleWalletProperties off = liveProperties();
        off.setEnabled(false);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GoogleWalletApiClient usable = new GoogleWalletApiClient(
                GoogleServiceAccountKey.parseBase64(KEYS.serviceAccountJsonBase64()),
                builder.build());
        assertThat(usable.isUsable())
                .as("the client's own guard must be open, or this tests the wrong thing")
                .isTrue();
        GoogleWalletProvisioner gated =
                new GoogleWalletProvisioner(off, usable, new EmailProperties());

        assertThat(refusal(() -> gated.provision(ticket(), event(), organization(), QR)).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    /**
     * <b>{@code !api.isUsable()}, isolated — via the one thing it orders itself
     * in front of.</b>
     *
     * <p>For every call that reaches Google, this half of the gate is
     * behaviourally redundant with {@code accessToken()}'s own check: both
     * produce 503 and neither opens a socket, so no assertion on the outcome can
     * tell them apart. What is <em>not</em> redundant is where it sits — the gate
     * runs <b>before</b> {@code assertNoTransactionOpen()}, which throws
     * {@link IllegalStateException} and would surface as a 500.
     *
     * <p>So: a deployment whose credential will not parse, called from inside a
     * transaction, must still answer the buyer 503 and not 500. Delete
     * {@code !api.isUsable()} and the transaction assertion fires first, the
     * refusal stops being an {@code ApiException}, and this goes red. It is the
     * only assertion in the file that distinguishes that half of the gate from
     * nothing at all — which is itself the finding: the rest of its work is
     * defence in depth, and that is worth knowing before someone "simplifies" it.
     */
    @Test
    void aCredentialThatWillNotParseIs503AndNotA500EvenInsideATransaction() {
        GoogleWalletProvisioner gated = provisionerFor(unparseableCredential());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(refusal(() -> gated.provision(ticket(), event(), organization(), QR))
                    .status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            server.verify();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** Enabled, issuer set, and a service-account value that cannot be parsed into a key. */
    private static GoogleWalletProperties unparseableCredential() {
        GoogleWalletProperties broken = new GoogleWalletProperties();
        broken.setEnabled(true);
        broken.setIssuerId(ISSUER);
        broken.setServiceAccountJsonBase64("{\"client_email\":\"a@b.c\"}");
        return broken;
    }

    // ── dead tickets ─────────────────────────────────────────────────────────

    /**
     * A refunded ticket gets no fresh, official-looking artifact minted for it,
     * and no request is sent.
     */
    @Test
    void aRefundedTicketIs409AndNothingIsSentToGoogle() {
        Ticket refunded = ticket();
        refunded.setState(Ticket.STATE_REFUNDED);

        ApiException e = refusal(() -> provisioner.provision(refunded, event(), organization(), QR));
        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.code()).isEqualTo(ErrorCode.TICKET_ALREADY_REFUNDED);
        server.verify();
    }

    @Test
    void aRevokedTicketIs409WithItsOwnCode() {
        Ticket revoked = ticket();
        revoked.setState(Ticket.STATE_REVOKED);

        ApiException e = refusal(() -> provisioner.provision(revoked, event(), organization(), QR));
        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.code())
                .as("a revocation is something the organizer did, not something the buyer asked for")
                .isEqualTo(ErrorCode.INVALID_STATE);
        server.verify();
    }

    /**
     * <b>The ordering, pinned.</b> A refunded ticket on a server with the wallet
     * switched off is still 409 and not 503: what a buyer learns about their own
     * ticket must not depend on our deployment state, and "temporarily
     * unavailable" would be false and would invite a retry that can never
     * succeed. Same reason the endpoint checks the token before the config.
     */
    @Test
    void aRefundedTicketIs409EvenWhenTheWalletIsOff() {
        GoogleWalletProvisioner gated = provisionerFor(new GoogleWalletProperties());
        Ticket refunded = ticket();
        refunded.setState(Ticket.STATE_REFUNDED);

        assertThat(refusal(() -> gated.provision(refunded, event(), organization(), QR)).status())
                .isEqualTo(HttpStatus.CONFLICT);
        server.verify();
    }

    /** A redeemed ticket is amber at the door, not red: re-adding the pass after
     * entry must keep working for a buyer whose phone died in the queue. */
    @Test
    void aRedeemedTicketStillGetsAPass() {
        Ticket redeemed = ticket();
        redeemed.setState(Ticket.STATE_REDEEMED);

        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(once(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.OK));

        assertThat(provisioner.provision(redeemed, event(), organization(), QR))
                .isEqualTo(OBJECT_ID);
        server.verify();
    }

    // ── the transaction constraint ───────────────────────────────────────────

    /**
     * <b>The plan's one concurrency constraint, given an owner and a test.</b>
     *
     * <p>"Must not run inside the read transaction of the ticket lookup" was
     * written into Task 6 and enforced by nothing: this class carries no
     * {@code @Transactional}, and while it had no caller at all the property held
     * by accident. Task 7 gives it a caller, so it is asserted at runtime now,
     * and this is the test that proves the assertion bites.
     *
     * <p>What it catches: someone putting {@code @Transactional(readOnly = true)}
     * on {@code GoogleWalletPassService#saveUrl} — the annotation any reviewer
     * would suggest for a method that only reads rows. It would look correct,
     * pass every existing test, and hold a pooled JDBC connection open across up
     * to three 5s-timeout calls to Google on an unauthenticated endpoint. That is
     * a whole-API pool-exhaustion outage triggered by Google being slow, and it
     * only appears under concurrency plus a slow upstream.
     *
     * <p>No database is involved here: {@code isActualTransactionActive()} reads a
     * thread-local, so the condition can be created directly. The mock server has
     * no expectations, so "no socket was opened" is asserted rather than assumed.
     */
    @Test
    void provisioningInsideATransactionIsRefusedBeforeAnySocketOpens() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> provisioner.provision(ticket(), event(), organization(), QR))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run inside a database transaction");
            server.verify();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * The guard is about outbound calls, not about the method. A refused ticket
     * has opened no socket, so it gets its true answer — 409 — even from inside a
     * transaction, rather than an {@code IllegalStateException} that would turn a
     * correct 409 into a 500 for a caller that did nothing wrong to this buyer.
     */
    @Test
    void aDeadTicketInsideATransactionStillGetsItsOwnAnswer() {
        Ticket refunded = ticket();
        refunded.setState(Ticket.STATE_REFUNDED);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(refusal(() -> provisioner.provision(refunded, event(), organization(), QR))
                    .status()).isEqualTo(HttpStatus.CONFLICT);
            server.verify();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** Same reasoning for a closed gate: nothing was sent, so nothing was violated. */
    @Test
    void aClosedGateInsideATransactionIsStill503() {
        GoogleWalletProvisioner gated = provisionerFor(new GoogleWalletProperties());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(refusal(() -> gated.provision(ticket(), event(), organization(), QR))
                    .status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            server.verify();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static ApiException refusal(Runnable call) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("expected a refusal, not a silent success").isNotNull();
        return e;
    }

    private static Event event() {
        Event e = new Event();
        e.setId(EVENT_ID);
        e.setName("Vechirka");
        e.setVenueName("Club Zenith");
        e.setVenueStreet("12 Rue de la Nuit");
        e.setVenuePostalCode("75011");
        e.setVenueCity("Paris");
        e.setVenueCountry("FR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(Instant.parse("2026-06-15T18:00:00Z"));
        return e;
    }

    private static Ticket ticket() {
        Ticket t = new Ticket();
        t.setToken(TOKEN);
        t.setTierName("Early Bird");
        t.setState(Ticket.STATE_ISSUED);
        return t;
    }

    private static Organization organization() {
        Organization o = new Organization();
        o.setBrandName("Nocturne");
        return o;
    }
}
