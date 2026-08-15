package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.passes.PKEventTicket;
import de.brendamour.jpasskit.passes.PKGenericPassBuilder;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Builds and signs Apple Wallet {@code .pkpass} archives on demand.
 *
 * <p>The pass embeds the same HMAC-signed QR payload that the web ticket page
 * and email use, so the gate scanner sees one canonical token. The pass uses
 * the {@code eventTicket} style and surfaces the event name, date, venue, and
 * tier name. No web-service URL is registered — passes do not auto-update on
 * the device when the ticket is redeemed at the gate (the gate is the
 * authoritative source of truth; this is a v2 follow-up).
 *
 * <p>Artwork (icon + logo) is generated programmatically at boot as solid
 * brand-coloured squares. Replacing the bundled art with real branded PNGs at
 * {@code src/main/resources/wallet/icon.png} etc. is a future enhancement; the
 * placeholders satisfy Apple's pkpass schema and keep the pass scannable.
 *
 * <p>When any of the {@code APPLE_WALLET_*} env vars are missing,
 * {@link #isConfigured()} returns false; callers (controller + email
 * template) gate on this so the missing-cert case 503s cleanly rather than
 * generating a broken pass.
 */
@Service
public class AppleWalletPassService {

    private static final Logger log = LoggerFactory.getLogger(AppleWalletPassService.class);

    private final AppleWalletProperties props;
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final EventRepository events;
    private final QrPayloadSigner qrSigner;

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
                                   QrPayloadSigner qrSigner) {
        this.props = props;
        this.tickets = tickets;
        this.orders = orders;
        this.events = events;
        this.qrSigner = qrSigner;

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
        Order order = orders.findById(t.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order missing for ticket " + ticketToken));
        Event event = events.findById(t.getEventId())
                .orElseThrow(() -> new IllegalStateException("Event missing for ticket " + ticketToken));

        // Same payload as the web QR and the email — single source of truth so a
        // gate scanner sees identical bytes regardless of how the buyer presented
        // the ticket.
        String qrPayload = qrSigner.sign(t.getToken());

        // PKEventTicket.builder() returns a PKGenericPassBuilder pre-typed as
        // EventTicket. We add fields directly on it and hand the builder to
        // PKPass via the pass(PKGenericPassBuilder) overload.
        PKGenericPassBuilder eventTicket = PKEventTicket.builder()
                .primaryField(field("event", "Event", nullSafe(event.getName())))
                .secondaryField(field("when", "Date", formatWhen(event)))
                .secondaryField(field("where", "Venue", formatWhere(event)))
                .auxiliaryField(field("tier", "Tier", nullSafe(t.getTierName())));

        PKPass pass = PKPass.builder()
                .passTypeIdentifier(props.getPassTypeId())
                .teamIdentifier(props.getTeamId())
                .serialNumber(t.getToken())
                .organizationName("imin")
                .description(nullSafe(event.getName()) + " — " + nullSafe(t.getTierName()))
                .barcodeBuilder(PKBarcode.builder()
                        .format(PKBarcodeFormat.PKBarcodeFormatQR)
                        .message(qrPayload)
                        .messageEncoding("iso-8859-1")
                        .altText(t.getToken()))
                .pass(eventTicket)
                .build();

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

    private static String formatWhen(Event e) {
        if (e.getStartsAt() == null) return "";
        return DateTimeFormatter.ofPattern("EEE d LLL · HH:mm")
                .withZone(zoneOf(e))
                .format(e.getStartsAt());
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
        if (n != null && !n.isBlank() && c != null && !c.isBlank()) return n + ", " + c;
        if (n != null && !n.isBlank()) return n;
        if (c != null && !c.isBlank()) return c;
        return "";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
