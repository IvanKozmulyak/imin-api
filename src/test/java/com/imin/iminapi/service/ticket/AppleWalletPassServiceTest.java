package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppleWalletPassServiceTest {

    @Test
    void unconfigured_isConfigured_false_and_generate_throws() {
        AppleWalletProperties props = new AppleWalletProperties(); // all blank
        AppleWalletPassService svc = new AppleWalletPassService(
                props,
                mock(TicketRepository.class),
                mock(OrderRepository.class),
                mock(EventRepository.class),
                signer());

        assertThat(svc.isConfigured()).isFalse();
        assertThatThrownBy(() -> svc.generatePass("any-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void configured_generates_signed_zip_containing_pass_json_and_signature() throws Exception {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword(bundle.password());
        props.setWwdrPemBase64(bundle.wwdrPemBase64());

        TicketRepository tickets = mock(TicketRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);

        Ticket t = new Ticket();
        t.setToken("TKT_X");
        t.setOrderId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState("issued");
        when(tickets.findByToken("TKT_X")).thenReturn(Optional.of(t));

        Order o = new Order();
        o.setId(t.getOrderId());
        o.setEventId(t.getEventId());
        o.setOrgId(UUID.randomUUID());
        when(orders.findById(t.getOrderId())).thenReturn(Optional.of(o));

        Event e = new Event();
        e.setId(t.getEventId());
        e.setName("Saturn Night");
        e.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        e.setTimezone("Europe/Paris");
        e.setVenueName("Le Petit Bain");
        e.setVenueCity("Paris");
        when(events.findById(t.getEventId())).thenReturn(Optional.of(e));

        AppleWalletPassService svc = new AppleWalletPassService(
                props, tickets, orders, events, signer());
        assertThat(svc.isConfigured()).isTrue();

        byte[] pkpass = svc.generatePass("TKT_X");
        assertThat(pkpass).isNotEmpty();

        Set<String> entries = listZipEntries(pkpass);
        // Apple's required pkpass entries:
        assertThat(entries).contains("pass.json", "manifest.json", "signature");
        // Bundled artwork should land in the archive:
        assertThat(entries).contains("icon.png", "icon@2x.png", "icon@3x.png",
                "logo.png", "logo@2x.png", "logo@3x.png");

        // pass.json must mention the signed QR payload format so the gate scanner sees
        // the same string the email and web ticket present.
        String passJson = readZipEntry(pkpass, "pass.json");
        assertThat(passJson).contains("imin1.TKT_X.");
        assertThat(passJson).contains("pass.test.imin");
        assertThat(passJson).contains("Saturn Night");
        assertThat(passJson).contains("Le Petit Bain");
        assertThat(passJson).contains("GA");
    }

    private static QrPayloadSigner signer() {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret("walletservice-test-signing-secret-32b");
        return new QrPayloadSigner(p);
    }

    private static Set<String> listZipEntries(byte[] zipBytes) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
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
