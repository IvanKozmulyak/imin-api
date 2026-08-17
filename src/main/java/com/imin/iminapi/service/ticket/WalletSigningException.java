package com.imin.iminapi.service.ticket;

/**
 * Signing a wallet pass failed at request time, with a configuration that passed
 * every gate.
 *
 * <p><b>Why this is its own type and not the {@code IllegalStateException} it
 * replaced.</b> {@code AppleWalletPassService.isConfigured()} is memoised from
 * construction — sound for a credential swap, which is a redeploy, and unsound
 * for the one thing a certificate does on its own: expire. A process that boots
 * before {@code notAfter} and is still running after it keeps answering
 * {@code isConfigured() == true} while every signature throws. That was a
 * <b>500 on an unauthenticated endpoint</b>, contradicting this repo's own
 * contract in three places at once — {@code CLAUDE.md} ("any upstream failure ⇒
 * 503 UPSTREAM_UNAVAILABLE, never a 500"), ADR-0004's Consequences ("it produces
 * an absent CTA and a 503"), and the javadoc on {@code isConfigured()}, which
 * describes this exact 500 as the defect it was introduced to fix. It fixed the
 * boot case only. Found by minting an expired certificate and driving the real
 * service, not by reading.
 *
 * <p>A distinct type is what lets {@code PublicTicketAssetController} answer 503
 * for <i>this</i> and nothing else. Catching {@code IllegalStateException} there
 * would also swallow a genuine programming error inside pass construction and
 * report it as an upstream outage — turning a bug we should see into a silence.
 *
 * <p><b>The CTA still lies for the rest of the process's life.</b>
 * {@code wallet.apple.available} is derived from the same memoised answer, so it
 * stays {@code true} and the button stays lit on the buyer page and in the
 * issuance email. That is deliberate and is the same shape ADR-0004 already
 * accepts for a refund: <i>"The endpoints' 409 is not redundancy behind the
 * field — it is the only thing true at the moment of the tap."</i> Re-deriving
 * availability per read means opening a PKCS#12 on every ticket render, which is
 * a different decision with a cost attached, and it is not made here.
 */
public class WalletSigningException extends RuntimeException {

    /**
     * @param cause kept for the stack, never rendered into a log line or a
     *              response body — see the catch block in
     *              {@code AppleWalletPassService} for why the message is dropped
     *              rather than forwarded.
     */
    public WalletSigningException(Throwable cause) {
        super("Failed to sign an Apple Wallet pass", cause);
    }
}
