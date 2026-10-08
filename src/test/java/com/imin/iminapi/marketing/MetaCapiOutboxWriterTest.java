package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.marketing.service.MetaCapiOutboxWriter;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class MetaCapiOutboxWriterTest {

    @Autowired MetaCapiOutboxWriter writer;
    @Autowired MetaCapiEventRepository capiRepo;
    @Autowired MetaPixelConnectionRepository connRepo;
    @Autowired OrderRepository orders;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final Set<UUID> orgIds = new LinkedHashSet<>();

    @AfterEach
    void deleteOwnOutboxRows() {
        // The poller drains every org's due rows; these carry an undecryptable token.
        for (UUID orgId : orgIds) jdbc.update("delete from meta_capi_events where org_id = ?", orgId);
    }

    private Organization org() {
        Organization o = fx.org();
        orgIds.add(o.getId());
        return o;
    }

    private void orgConnection(UUID orgId) {
        MetaPixelConnection c = new MetaPixelConnection();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setEventId(null);
        c.setPixelId("1234567890");
        c.setCapiAccessTokenEnc("enc");
        connRepo.save(c);
    }

    private Order paidOrder(Organization org, boolean adsConsent, String currency, String email) {
        Event e = fx.event(org, fx.owner(org), EventStatus.LIVE, null);
        Order o = fx.order(e, email);
        o.setTotalMinor(4200L);
        o.setCurrency(currency);
        o.setAdsConsent(adsConsent);
        return orders.save(o);
    }

    private Order paidOrder(Organization org, boolean adsConsent, String currency) {
        return paidOrder(org, adsConsent, currency, fx.email("buyer"));
    }

    @Test
    void insertsOutboxRowWhenConsentAndConnectionPresent() {
        Organization org = org();
        orgConnection(org.getId());
        String address = fx.email("buyer");
        // Deliberately mixed-case with a trailing space: the hash is of the normalized address.
        Order o = paidOrder(org, true, "uah", address.toUpperCase(Locale.ROOT) + " ");

        writer.writeForOrder(o.getId());

        List<MetaCapiEvent> all = capiRepo.findByOrgAndOrderIds(org.getId(), List.of(o.getId()));
        assertThat(all).hasSize(1);
        MetaCapiEvent e = all.get(0);
        assertThat(e.getCurrency()).isEqualTo("uah");           // from orders.currency, not defaulted
        assertThat(e.getValueMinor()).isEqualTo(4200L);
        assertThat(e.getPixelId()).isEqualTo("1234567890");
        assertThat(e.getOrderToken()).isEqualTo(o.getToken());  // dedup key = order token, copied from the order
        // sha256 of the NORMALIZED email (lower+trim) — 64 hex chars.
        assertThat(e.getEmailSha256()).hasSize(64)
                .isEqualTo(sha256Hex(address.toLowerCase(Locale.ROOT)));
        assertThat(e.getStatus()).isEqualTo(MetaCapiEvent.STATUS_PENDING);
    }

    @Test
    void skipsWhenNoAdsConsent() {
        Organization org = org();
        orgConnection(org.getId());
        Order o = paidOrder(org, false, "eur");
        writer.writeForOrder(o.getId());
        assertThat(capiRepo.existsByOrderId(o.getId())).isFalse();
    }

    @Test
    void skipsWhenNoConnection() {
        Organization org = org(); // real org, but no pixel connection saved
        Order o = paidOrder(org, true, "eur");
        writer.writeForOrder(o.getId());
        assertThat(capiRepo.existsByOrderId(o.getId())).isFalse();
    }

    @Test
    void skipsWhenCurrencyBlank() {
        Organization org = org();
        orgConnection(org.getId());
        Order o = paidOrder(org, true, "  ");
        writer.writeForOrder(o.getId());
        // Currency guard: a blank currency must NOT produce a silently-mis-valued row.
        assertThat(capiRepo.existsByOrderId(o.getId())).isFalse();
    }

    @Test
    void isIdempotentPerOrder() {
        Organization org = org();
        orgConnection(org.getId());
        Order o = paidOrder(org, true, "eur");
        writer.writeForOrder(o.getId());
        writer.writeForOrder(o.getId()); // second call must not duplicate
        assertThat(capiRepo.findByOrgAndOrderIds(org.getId(), List.of(o.getId()))).hasSize(1);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
