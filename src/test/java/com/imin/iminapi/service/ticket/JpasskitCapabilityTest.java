package com.imin.iminapi.service.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.PKRelevantDate;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.passes.PKEventTicket;
import de.brendamour.jpasskit.passes.PKGenericPassBuilder;
import de.brendamour.jpasskit.signing.PKInMemorySigningUtil;
import de.brendamour.jpasskit.signing.PKPassTemplateInMemory;
import de.brendamour.jpasskit.signing.PKSigningInformation;
import de.brendamour.jpasskit.signing.PKSigningInformationUtil;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two library capabilities the jpasskit 0.4.1 &rarr; 0.5.8 upgrade was
 * done for, and the one behaviour it must <b>not</b> have changed.
 *
 * <p>This tests jpasskit, not {@link AppleWalletPassService} — deliberately.
 * The upgrade landed as its own commit ahead of the pass.json work, so its
 * justification ("0.4.1 cannot emit {@code relevantDates}") needs a proof that
 * does not wait on the service being rewritten. When that rewrite lands, the
 * service-level assertions live in {@code ApplePassContentTest} and this file
 * stays as the dependency guard: it fails on a downgrade, which is the failure
 * mode a plain version string in {@code pom.xml} cannot announce.
 *
 * <p>Everything here drives the real signing path over synthetic key material
 * from {@link WalletTestCerts} — nothing is stubbed.
 */
class JpasskitCapabilityTest {

    private static final Instant DOORS = Instant.parse("2026-06-15T20:00:00Z");
    private static final Instant CURFEW = Instant.parse("2026-06-16T04:00:00Z");

    /**
     * The reason the upgrade is a prerequisite rather than a tidy-up.
     *
     * <p>Apple deprecated the scalar {@code relevantDate} in favour of a
     * {@code relevantDates} array of relevancy intervals, and 0.4.1 has no
     * {@code PKRelevantDate} type at all — {@code PKPassBuilder} there offers
     * only {@code relevantDate(Date|Instant)}. Reading the field back out of a
     * signed archive is the only assertion that proves the array actually
     * reaches pass.json, since 0.5.x briefly had the list present on the model
     * but dropped during serialization.
     */
    @Test
    void relevantDatesSurvivesIntoTheSignedPassJson() throws Exception {
        JsonNode pass = passJson(signPass(PKPass.builder()
                .relevantDates(List.of(PKRelevantDate.builder()
                        .startDate(DOORS)
                        .endDate(CURFEW)
                        .build()))));

        assertThat(pass.path("relevantDates")).hasSize(1);
        JsonNode window = pass.path("relevantDates").get(0);
        assertThat(window.path("startDate").asText()).isEqualTo("2026-06-15T20:00:00Z");
        assertThat(window.path("endDate").asText()).isEqualTo("2026-06-16T04:00:00Z");

        assertThat(pass.has("relevantDate"))
                .as("the scalar form is deprecated but PKPassBuilder still offers it — "
                        + "a pass must not carry both")
                .isFalse();
    }

    /**
     * The iOS 18 poster event ticket opt-in, absent from 0.4.1. Task 4 of the
     * wallet plan sets it; this proves the wire field exists to be set.
     */
    @Test
    void preferredStyleSchemesSurvivesIntoTheSignedPassJson() throws Exception {
        JsonNode pass = passJson(signPass(
                PKPass.builder().preferredStyleSchemes(List.of("posterEventTicket"))));

        assertThat(pass.path("preferredStyleSchemes")).hasSize(1);
        assertThat(pass.path("preferredStyleSchemes").get(0).asText())
                .isEqualTo("posterEventTicket");
    }

    /**
     * <b>The thing the upgrade must not have modernised.</b>
     *
     * <p>Apple's pkpass manifest is SHA-1, and 0.4.1 and 0.5.8 both use
     * {@code Hashing.sha1()} for it. SHA-1 looks like a defect and is not one:
     * the SHA-256 digest belongs to Apple Wallet <i>Orders</i>, a different
     * product. Swapping it produces passes Apple rejects, and no synthetic-cert
     * test would catch that — hence pinning the digest here rather than trusting
     * a comment.
     */
    @Test
    void theManifestIsStillSha1OverEachFile() throws Exception {
        byte[] pkpass = signPass(PKPass.builder());

        JsonNode manifest = new ObjectMapper()
                .readTree(readZipEntry(pkpass, "manifest.json"));
        byte[] passJsonBytes = readZipEntry(pkpass, "pass.json");

        String sha1 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(passJsonBytes));
        assertThat(manifest.path("pass.json").asText())
                .as("a 40-char SHA-1 hex digest — SHA-256 here is a pass Apple rejects")
                .isEqualTo(sha1);

        assertThat(listZipEntries(pkpass))
                .as("the detached CMS signature over manifest.json")
                .contains("signature");
    }

    // ─── plumbing ────────────────────────────────────────────────────────────

    /** Signs a minimal but Apple-shaped event ticket with synthetic certs. */
    private static byte[] signPass(de.brendamour.jpasskit.PKPassBuilder builder) throws Exception {
        PKGenericPassBuilder eventTicket = PKEventTicket.builder()
                .primaryField(PKField.builder().key("event").label("Event").value("Saturn Night").build());

        PKPass pass = builder
                .passTypeIdentifier("pass.test.imin")
                .teamIdentifier("TESTTEAMID")
                .serialNumber("TKT_CAP")
                .organizationName("imin")
                .description("Saturn Night — GA")
                .barcodeBuilder(PKBarcode.builder()
                        .format(PKBarcodeFormat.PKBarcodeFormatQR)
                        .message("imin1.TKT_CAP.deadbeef")
                        .messageEncoding("iso-8859-1")
                        .altText("TKT_CAP"))
                .pass(eventTicket)
                .build();

        PKPassTemplateInMemory template = new PKPassTemplateInMemory();
        byte[] png = onePixelPng();
        for (String slot : List.of(PKPassTemplateInMemory.PK_ICON,
                PKPassTemplateInMemory.PK_ICON_RETINA,
                PKPassTemplateInMemory.PK_LOGO)) {
            template.addFile(slot, new ByteArrayInputStream(png));
        }

        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        PKSigningInformation signing = new PKSigningInformationUtil()
                .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                        new ByteArrayInputStream(Base64.getDecoder().decode(bundle.p12Base64())),
                        bundle.password(),
                        new ByteArrayInputStream(Base64.getDecoder().decode(bundle.wwdrPemBase64())));

        return new PKInMemorySigningUtil()
                .createSignedAndZippedPkPassArchive(pass, template, signing);
    }

    private static JsonNode passJson(byte[] pkpass) throws Exception {
        return new ObjectMapper().readTree(readZipEntry(pkpass, "pass.json"));
    }

    private static byte[] onePixelPng() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "PNG", out);
        return out.toByteArray();
    }

    private static java.util.Set<String> listZipEntries(byte[] zipBytes) throws Exception {
        java.util.Set<String> names = new java.util.HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static byte[] readZipEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return zis.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("Entry not found: " + name
                + " (archive has " + listZipEntries(zipBytes) + ")");
    }
}
