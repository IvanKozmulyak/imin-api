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
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The artwork that actually ships, read back out of a <b>generated, signed
 * archive</b> — never off the builder and never off the classpath alone.
 *
 * <p>An image claim asserted against the loader proves the loader works. Apple
 * only ever sees the zip, and the two can be wrong in the same direction: a file
 * committed at the right size that never reaches the archive, or one that
 * reaches it re-encoded, both pass a loader test and both ship broken art. So
 * every dimension here is decoded from the bytes inside the pkpass, and the
 * bytes are additionally compared against the committed file to prove nothing
 * silently substituted the placeholder.
 *
 * <p>Sizes are the current Apple guide, not the archived one: <b>icon is 38pt</b>
 * (the 29 this service shipped for its first life is from the archived Wallet
 * guide), and the logo is capped at 160×50pt.
 */
class WalletArtworkTest {

    /** Every image the pass is supposed to carry, and its size in pixels. */
    private static final Map<String, int[]> EXPECTED = Map.of(
            "icon.png", new int[]{38, 38},
            "icon@2x.png", new int[]{76, 76},
            "icon@3x.png", new int[]{114, 114},
            "logo.png", new int[]{42, 50},
            "logo@2x.png", new int[]{84, 100},
            "logo@3x.png", new int[]{126, 150});

    @Test
    void iconsAreInTheArchiveAtTheThreeAppleScales() throws Exception {
        Map<String, byte[]> archive = imagesInArchive();

        assertSize(archive, "icon.png", 38, 38);
        assertSize(archive, "icon@2x.png", 76, 76);
        assertSize(archive, "icon@3x.png", 114, 114);
    }

    /**
     * Apple caps the logo at 160×50 points. The retina files are the same size in
     * points and two/three times that in pixels — a @2x that is not exactly twice
     * its @1x is a logo that changes size when the buyer changes phone.
     */
    @Test
    void logosAreInTheArchive_withinApplesMaximum_andTheSamePointSizeAtEveryScale() throws Exception {
        Map<String, byte[]> archive = imagesInArchive();

        BufferedImage logo = decode(archive, "logo.png");
        assertThat(logo.getWidth()).as("logo width in points").isLessThanOrEqualTo(160);
        assertThat(logo.getHeight()).as("logo height in points").isLessThanOrEqualTo(50);

        for (int scale : List.of(2, 3)) {
            BufferedImage retina = decode(archive, "logo@" + scale + "x.png");
            assertThat(retina.getWidth()).as("logo@%dx width", scale)
                    .isEqualTo(logo.getWidth() * scale);
            assertThat(retina.getHeight()).as("logo@%dx height", scale)
                    .isEqualTo(logo.getHeight() * scale);
        }
    }

    /**
     * <b>The invariant the source file makes easy to get wrong.</b>
     *
     * <p>{@code logo-mark-light.png} is a black mark on an <i>opaque white</i>
     * field: its alpha is 255 across the interior and carries no shape. Keying a
     * mask off alpha instead of luminance produces a fully opaque white
     * rectangle, which on this pass's {@code rgb(8,7,13)} background is a white
     * box where the logo should be — and it looks deliberate, so nobody reports
     * it. Two things have to hold: the logo must be mostly transparent, and the
     * ink that is left must be light enough to read on a near-black pass.
     */
    @Test
    void theLogoIsAMarkOnTransparency_notAnOpaqueBoxOfBackgroundColour() throws Exception {
        BufferedImage logo = decode(imagesInArchive(), "logo.png");

        assertThat(logo.getColorModel().hasAlpha())
                .as("an opaque logo sits on the pass's near-black background as a visible rectangle")
                .isTrue();

        long opaque = 0;
        long transparent = 0;
        long inkLuminance = 0;
        for (int y = 0; y < logo.getHeight(); y++) {
            for (int x = 0; x < logo.getWidth(); x++) {
                int argb = logo.getRGB(x, y);
                int a = (argb >>> 24);
                if (a < 16) {
                    transparent++;
                } else if (a > 240) {
                    opaque++;
                    int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
                    inkLuminance += Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
                }
            }
        }
        assertThat(transparent)
                .as("the mark is a letterform grid — a logo with no transparent pixels "
                        + "is the alpha-keying mistake, not a logo")
                .isGreaterThan(0);
        assertThat(opaque).as("some ink").isGreaterThan(0);
        assertThat(inkLuminance / opaque)
                .as("mean ink luminance — a dark mark is invisible on an rgb(8,7,13) pass")
                .isGreaterThan(200);
    }

    /**
     * The archive must carry the committed file <b>byte for byte</b>.
     *
     * <p>This is what separates "the art shipped" from "something the right size
     * shipped". If a resource is dropped from the jar, or the loader silently
     * falls back, or anything re-encodes the PNG on the way through, the sizes
     * above can still all pass while the buyer gets a black square with a dot.
     */
    @Test
    void theArchiveShipsTheCommittedFilesAndNotTheGeneratedPlaceholder() throws Exception {
        Map<String, byte[]> archive = imagesInArchive();

        for (String name : EXPECTED.keySet()) {
            byte[] committed;
            try (InputStream in = WalletArtworkTest.class.getResourceAsStream("/wallet/" + name)) {
                assertThat(in).as("classpath:/wallet/%s must be committed", name).isNotNull();
                committed = in.readAllBytes();
            }
            assertThat(archive.get(name))
                    .as("%s in the pkpass must be the committed file, unmodified", name)
                    .isEqualTo(committed);
        }
    }

    /**
     * The manifest is what the detached signature actually covers, so an image
     * that is in the zip but not in the manifest is an image Apple will reject
     * the pass over. jpasskit builds the manifest; nothing verifies it did.
     *
     * <p>SHA-1 is correct here and is pinned elsewhere — Apple's <i>Building a
     * Pass</i> specifies SHA-1 manifest digests. Do not "upgrade" it.
     */
    @Test
    void everyImageIsCoveredByTheManifestUnderItsOwnDigest() throws Exception {
        byte[] pkpass = fixture().generate();
        Map<String, byte[]> archive = imagesInArchive(pkpass);
        JsonNode manifest = new ObjectMapper().readTree(
                new String(entry(pkpass, "manifest.json"), StandardCharsets.UTF_8));

        for (Map.Entry<String, byte[]> image : archive.entrySet()) {
            String sha1 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(image.getValue()));
            assertThat(manifest.path(image.getKey()).asText())
                    .as("manifest digest for %s", image.getKey())
                    .isEqualTo(sha1);
        }
    }

    /**
     * A deleted or corrupt file must not 500 a ticket endpoint. Artwork is
     * decoration; a scannable barcode at a door is not.
     */
    @Test
    void aMissingFileFallsBackToAGeneratedPlaceholderInsteadOfThrowing() throws Exception {
        byte[] bytes = WalletArtwork.load("does-not-exist.png", 38, 38);

        assertThat(bytes).isNotEmpty();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).as("the placeholder must still be a decodable PNG").isNotNull();
        assertThat(img.getWidth()).isEqualTo(38);
        assertThat(img.getHeight()).isEqualTo(38);
    }

    /**
     * The poster event ticket is deliberately not adopted, and this pins the
     * decision so a later reader does not "fix" a missing key.
     *
     * <p>Apple, <i>Creating a poster event pass using semantic tags</i>: "Poster
     * event tickets aren't compatible with tickets that require a QR code or
     * barcode for entry." Every imin ticket is redeemed by scanning its QR at the
     * door. Adding {@code preferredStyleSchemes} would change nothing on a device
     * and would read, in the tree, like a shipped feature.
     */
    @Test
    void thePassDoesNotClaimAPosterEventTicketItCannotRender() throws Exception {
        byte[] pkpass = fixture().generate();
        JsonNode pass = new ObjectMapper().readTree(
                new String(entry(pkpass, "pass.json"), StandardCharsets.UTF_8));

        assertThat(pass.has("preferredStyleSchemes")).isFalse();
        assertThat(pass.path("barcodes")).as("the QR is the ticket").isNotEmpty();
        assertThat(pass.path("eventTicket").path("primaryFields")).isNotEmpty();
    }

    // ── fixture + helpers ────────────────────────────────────────────────────

    private static void assertSize(Map<String, byte[]> archive, String name, int w, int h)
            throws Exception {
        BufferedImage img = decode(archive, name);
        assertThat(img.getWidth()).as("%s width", name).isEqualTo(w);
        assertThat(img.getHeight()).as("%s height", name).isEqualTo(h);
    }

    private static BufferedImage decode(Map<String, byte[]> archive, String name) throws Exception {
        assertThat(archive).as("pkpass entries").containsKey(name);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(archive.get(name)));
        assertThat(img).as("%s must decode as an image", name).isNotNull();
        return img;
    }

    private static Map<String, byte[]> imagesInArchive() {
        return imagesInArchive(fixture().generate());
    }

    private static Map<String, byte[]> imagesInArchive(byte[] pkpass) {
        Map<String, byte[]> images = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(pkpass))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().endsWith(".png")) {
                    images.put(e.getName(), zis.readAllBytes());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the pkpass archive", e);
        }
        return images;
    }

    private static byte[] entry(byte[] pkpass, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(pkpass))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return zis.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("Entry not found: " + name);
    }

    /**
     * Real key material from {@link WalletTestCerts} — the archive has to be
     * genuinely signed, because the manifest and the zip are only produced on
     * that path.
     */
    private static Fixture fixture() {
        return new Fixture();
    }

    private static final class Fixture {
        private final AppleWalletPassService service;

        Fixture() {
            UUID orderId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            Ticket ticket = new Ticket();
            ticket.setToken("TKT_ART");
            ticket.setOrderId(orderId);
            ticket.setEventId(eventId);
            ticket.setTierId(UUID.randomUUID());
            ticket.setTierName("GA");
            ticket.setState(Ticket.STATE_ISSUED);

            Order order = new Order();
            order.setId(orderId);
            order.setToken("ORD_ART");
            order.setEventId(eventId);
            order.setOrgId(orgId);

            Event event = new Event();
            event.setId(eventId);
            event.setName("Saturn Night");
            event.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
            event.setTimezone("Europe/Paris");
            event.setVenueName("Le Petit Bain");
            event.setVenueCity("Paris");

            Organization organization = new Organization();
            organization.setId(orgId);
            organization.setBrandName("Saturn Collective");

            TicketRepository tickets = mock(TicketRepository.class);
            OrderRepository orders = mock(OrderRepository.class);
            EventRepository events = mock(EventRepository.class);
            OrganizationRepository orgs = mock(OrganizationRepository.class);
            when(tickets.findByToken("TKT_ART")).thenReturn(Optional.of(ticket));
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
            tp.setSigningSecret("walletartwork-test-signing-secret");

            service = new AppleWalletPassService(props, tickets, orders, events, orgs,
                    new QrPayloadSigner(tp), new EmailProperties());
        }

        byte[] generate() {
            return service.generatePass("TKT_ART");
        }
    }
}
