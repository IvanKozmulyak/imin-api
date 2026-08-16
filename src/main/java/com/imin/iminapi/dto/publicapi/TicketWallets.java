package com.imin.iminapi.dto.publicapi;

/**
 * What this server can mint for one ticket, per wallet. Serialised as the
 * {@code wallet} object on {@code PublicTicketResponse} and on each entry of
 * {@code PublicOrderResponse.tickets}.
 *
 * <h2>Keyed by wallet vendor, not by device platform</h2>
 *
 * <p>{@code apple} and {@code google}, never {@code ios} and {@code android}.
 * The two are not the same partition: a Google Wallet save is an <b>account</b>
 * action — it completes in a desktop browser and the pass lands on whichever
 * phone that Google account is signed into — while a {@code .pkpass} is a
 * <b>file</b> that opens in Wallet on iOS <i>and</i> macOS. Keying by platform
 * would bake one client's device test into the wire, and the same ticket URL is
 * legitimately opened on a laptop and forwarded to a phone.
 *
 * <p>So the server says what it can mint and the client says what the device in
 * its hand can open. {@code imin-public} already does that half with a
 * {@code navigator.userAgent} test; a native app reads its own
 * {@code Platform.OS}; neither needs the server to guess, and nothing here
 * varies by {@code User-Agent}.
 *
 * <h2>Two wallets, independent in both directions</h2>
 *
 * <p>Either wallet can be live while the other is off, and that is the expected
 * state for a long time: {@code GOOGLE_WALLET_ENABLED} defaults to false and
 * stays false through the whole of Google's demo-mode window, while Apple is
 * gated on an entirely separate certificate. One block per wallet, each with its
 * own boolean, is what lets a client light exactly the CTA that works.
 */
public record TicketWallets(WalletPass apple, WalletPass google) {

    /**
     * One wallet's answer for one ticket.
     *
     * <p><b>{@code available} means "tap this and it works"</b>, not "the env
     * vars are set". It is the conjunction of two independent facts: this
     * deployment can sign for that wallet (credentials present <i>and</i>
     * loadable — a complete-but-corrupt credential is not availability), and
     * this particular ticket is one we will mint a pass for at all
     * ({@code WalletEligibility.isLive} — refunded and revoked are refused,
     * redeemed deliberately is not).
     *
     * <p><b>{@code url} is non-null if and only if {@code available} is true.</b>
     * The path itself is a routing constant and could be emitted unconditionally,
     * but two independently-checkable encodings of one fact is how a client ends
     * up gating on the wrong one — and the failure that shape produces is a lit
     * CTA that 409s or 503s, which is the exact thing this contract exists to
     * prevent. A client that forgets the boolean and builds its button from the
     * URL alone still renders nothing. {@code WalletContractTest} pins the
     * biconditional in every state.
     *
     * <p><b>Absolute and server-built.</b> No client concatenates an API path
     * again: {@code imin-public} does today
     * ({@code lib/api/public-events.ts:30-32}) and a native app must not, because
     * a shipped binary outlives a route change. The URL points at this API's
     * {@code imin.ticket.api-public-base-url}, never at the buyer site.
     *
     * <p><b>It is our endpoint, not the vendor's artifact.</b> The Google entry
     * is {@code …/google-wallet}, which 302s to a freshly signed
     * {@code pay.google.com} save link at tap time; it is <i>not</i> that link.
     * That matters because this response is cached — {@code imin-public}'s
     * service worker keeps {@code /tickets/*} for the door — and a cached save
     * JWT would carry a stale {@code iat} and a bearer credential in a cache. A
     * cached {@code wallet.google.url} keeps working because the mint happens on
     * the far side of it.
     *
     * <h2>What a closed gate does <i>not</i> say, on purpose</h2>
     *
     * <p>Google has three closed states — nobody configured it, credentials
     * present but unparseable, and the demo-mode hold where everything is correct
     * and {@code GOOGLE_WALLET_ENABLED} is still false — and Apple has its own
     * two. {@code GoogleWalletProperties.gateReason()} tells them apart, in env
     * var names, <b>for the log</b>. None of that reaches this record. A buyer
     * can take exactly one action for all five ("no button"), so a field they
     * cannot act on would only ever be acted on wrongly — as a "coming soon"
     * promise about a Google review we do not control — and it would put our
     * deployment state on an unauthenticated endpoint that anyone who has ever
     * bought a ticket can read.
     *
     * <p>The one distinction a client <i>does</i> need is already on the response
     * and is not in this block: {@code state}. "False because your ticket was
     * refunded" and "false because the wallet is off" are different sentences to
     * a buyer, and {@code state} is what separates them.
     */
    public record WalletPass(boolean available, String url) {

        /** The closed answer, shaped once so no caller invents {@code (false, "…")}. */
        public static final WalletPass UNAVAILABLE = new WalletPass(false, null);

        /** The open answer. Private-by-convention: build through {@code WalletOffers}. */
        public static WalletPass at(String url) {
            return new WalletPass(true, url);
        }
    }
}
