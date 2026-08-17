package com.imin.iminapi.service.ticket;

import com.imin.iminapi.dto.publicapi.TicketWallets;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.springframework.stereotype.Service;

/**
 * The single place that decides whether to offer a buyer a wallet pass, and at
 * what URL.
 *
 * <p>Three surfaces answer this question — the ticket response, every entry of
 * the order response, and the issuance email — and before this existed each of
 * them concatenated its own {@code /apple-wallet.pkpass} path off its own copy
 * of the base URL, gated on its own reading of "configured". Three copies of one
 * rule is how the email came to advertise a pass for a ticket the API would
 * refuse: {@code BuyerOrderActionsController} can re-send an issuance email at
 * any time, including after a refund, and the emailer's gate was the wallet
 * config alone.
 *
 * <p>Deliberately <b>not</b> a place where any pass is generated. It asks two
 * booleans and formats two strings; it opens no socket, signs nothing, and
 * touches no repository. That is what makes it safe to call once per ticket
 * inside the order read.
 */
@Service
public class WalletOffers {

    private final AppleWalletPassService apple;
    private final GoogleWalletPassService google;
    private final TicketProperties props;

    public WalletOffers(AppleWalletPassService apple,
                        GoogleWalletPassService google,
                        TicketProperties props) {
        this.apple = apple;
        this.google = google;
        this.props = props;
    }

    /**
     * What may be offered for this ticket, per wallet.
     *
     * <p>Eligibility is evaluated <b>per ticket</b>, never per order: an order
     * with one refunded ticket and three live ones must show three buttons, and
     * an order-wide flag would show four or none.
     */
    public TicketWallets forTicket(Ticket ticket) {
        boolean live = WalletEligibility.isLive(ticket);
        String token = ticket == null ? null : ticket.getToken();
        return new TicketWallets(
                pass(live && apple.isConfigured(), token, "/apple-wallet.pkpass"),
                pass(live && google.isConfigured(), token, "/google-wallet"));
    }

    private TicketWallets.WalletPass pass(boolean available, String token, String suffix) {
        if (!available || token == null) {
            return TicketWallets.WalletPass.UNAVAILABLE;
        }
        return TicketWallets.WalletPass.at(
                apiBase() + "/api/v1/public/tickets/" + token + suffix);
    }

    /**
     * The API's own public base, not the buyer site. These URLs are handed to
     * email clients and to native apps, so a relative path is not an option, and
     * pointing them at {@code imin.email.buyer-site-base-url} would send a
     * {@code .pkpass} request to a Next.js app that has no such route.
     *
     * <p>Not URL-encoded, matching {@code qrUrl} on the same responses: tokens
     * are 24 random bytes in base64url ({@code A-Za-z0-9-_}) and encoding one
     * surface but not the other would be worse than encoding neither.
     */
    private String apiBase() {
        String base = props.getApiPublicBaseUrl();
        if (base == null) return "";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
