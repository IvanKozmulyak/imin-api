package com.imin.iminapi.controller.order;

import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The orders CSV leaves the platform with every buyer's address, so it is audited with its size,
 * guarded against formula injection, and follows the same filters as the list.
 */
@IminIntegrationTest
class EventOrdersExportTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows auditRows;
    @Autowired OrderRepository orders;
    @Autowired PromoCodeRepository promos;
    @Autowired UserRepository users;
    @Autowired DisputeRepository disputes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    private Event event;

    @BeforeEach
    void setUp() {
        org = newOrg();
        owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400L * 10));
    }

    /** Disputes are swept across every org, so the open ones this class leaves must go with their org. */
    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    private Organization newOrg() {
        Organization o = fx.org();
        orgIds.add(o.getId());
        return o;
    }

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(fx.principal(u), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    private String export(User u, String... params) throws Exception {
        var req = get("/api/v1/events/{id}/orders/export", event.getId());
        for (int i = 0; i < params.length; i += 2) req = req.param(params[i], params[i + 1]);
        return mvc.perform(req.with(as(u)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
    }

    private static String shortCode(Order o) {
        return o.getId().toString().substring(0, 8);
    }

    @Test
    void a_member_exports_every_order_with_the_specified_columns_and_it_is_audited_with_the_count() throws Exception {
        User member = fx.owner(org);
        member.setRole(UserRole.MEMBER);
        member = users.save(member);

        PromoCode code = new PromoCode();
        code.setEventId(event.getId());
        code.setCode("EARLY" + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        code.setDiscountPct(10);
        code.setMaxUses(5);
        code = promos.save(code);

        Order withCode = fx.order(event, fx.email("buyer"));
        withCode.setPromoCodeId(code.getId());
        withCode.setTotalMinor(2349);
        withCode = orders.save(withCode);
        fx.ticket(withCode, Ticket.STATE_ISSUED);
        fx.ticket(withCode, Ticket.STATE_REFUNDED);
        Order plain = fx.order(event, fx.email("other"));
        fx.ticket(plain, Ticket.STATE_ISSUED);
        Order yen = fx.order(event, fx.email("yen"));
        yen.setCurrency("jpy");
        yen = orders.save(yen);
        fx.ticket(yen, Ticket.STATE_ISSUED);
        Order stored = orders.findById(withCode.getId()).orElseThrow();

        String csv = export(member);

        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo(
                "order_ref,order_id,buyer_email,created_at,status,tickets,tickets_refunded,total,currency,promo_code");
        assertThat(lines).contains(String.join(",", shortCode(stored), stored.getId().toString(),
                stored.getEmail(), stored.getCreatedAt().toString(), "partially_refunded", "2", "1",
                "23.49", "EUR", code.getCode()));
        assertThat(lines).anyMatch(l -> l.startsWith(shortCode(plain) + ",") && l.endsWith(",paid,1,0,15.00,EUR,"));
        // Zero-decimal: 1500 minor units of JPY are 1500 yen, not 15.00.
        Order yenOrder = yen;
        assertThat(lines).anyMatch(l -> l.startsWith(shortCode(yenOrder) + ",") && l.contains(",1500,JPY,"));

        AuditLog row = auditRows.assertRecorded(org.getId(), AuditActions.ORDERS_EXPORTED, "event", event.getId());
        assertThat(row.getSummary()).contains("3 row(s)").doesNotContain(stored.getEmail());
        assertThat(row.getActorId()).isEqualTo(member.getId());
    }

    @Test
    void an_email_starting_with_a_formula_character_is_exported_as_text() throws Exception {
        String evil = "=cmd|'/C calc'!A0-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        Order o = fx.order(event, evil);
        fx.ticket(o, Ticket.STATE_ISSUED);

        String csv = export(owner);

        assertThat(csv).contains("," + o.getId() + ",'" + evil + ",");
    }

    @Test
    void the_export_follows_the_list_filters_and_counts_only_what_it_wrote() throws Exception {
        Order disputed = fx.order(event, fx.email("buyer"));
        fx.ticket(disputed, Ticket.STATE_REVOKED);
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(org.getId());
        d.setEventId(event.getId());
        d.setOrderId(disputed.getId());
        d.setAmountMinor(1500);
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.OPEN);
        disputes.save(d);
        Order paid = fx.order(event, fx.email("buyer"));
        fx.ticket(paid, Ticket.STATE_ISSUED);

        String csv = export(owner, "status", "disputed");

        assertThat(csv.lines().skip(1).toList()).singleElement()
                .satisfies(l -> assertThat(l).startsWith(shortCode(disputed) + ",").contains(",disputed,"));
        assertThat(auditRows.assertRecorded(org.getId(), AuditActions.ORDERS_EXPORTED, "event", event.getId())
                .getSummary()).contains("1 row(s)");
    }

    /** A refused export is not an export: 404 for another org's event and no row on either org. */
    @Test
    void another_orgs_event_is_404_and_writes_no_row() throws Exception {
        Order o = fx.order(event, fx.email("buyer"));
        fx.ticket(o, Ticket.STATE_ISSUED);
        Organization other = newOrg();
        User stranger = fx.owner(other);

        mvc.perform(get("/api/v1/events/{id}/orders/export", event.getId()).with(as(stranger)))
                .andExpect(status().isNotFound());

        assertThat(auditRows.forOrg(org.getId())).noneMatch(r -> AuditActions.ORDERS_EXPORTED.equals(r.getAction()));
        assertThat(auditRows.forOrg(other.getId())).noneMatch(r -> AuditActions.ORDERS_EXPORTED.equals(r.getAction()));
    }
}
