package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class AttendeeExportServiceTest {

    @Autowired AttendeeExportService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;

    private Organization org;
    private User owner;
    private Event event;
    private TicketTier ga;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);
        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Night");
        event.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);
        ga = new TicketTier();
        ga.setEventId(event.getId());
        ga.setName("GA");
        ga.setPriceMinor(1500);
        ga.setQuantity(100);
        ga.setEnabled(true);
        ga = tiers.save(ga);
        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        tickets.deleteAll(); orders.deleteAll(); tiers.deleteAll();
        events.deleteAll(); users.deleteAll(); orgs.deleteAll();
    }

    private Order order() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(1500);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        return orders.save(o);
    }

    private void ticket(Order o, String state, Instant redeemedAt) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(event.getId());
        t.setTierId(ga.getId());
        t.setTierName("GA");
        t.setPriceMinor(1500);
        t.setState(state);
        t.setRedeemedAt(redeemedAt);
        tickets.save(t);
    }

    @Test
    void cross_org_returns_404() {
        AuthPrincipal other = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());
        assertThatThrownBy(() -> service.toCsv(other, event.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void csv_has_header_plus_one_row_per_sold_ticket_with_status() {
        Order o = order();
        ticket(o, Ticket.STATE_ISSUED, null);
        ticket(o, Ticket.STATE_REDEEMED, Instant.now());
        ticket(o, Ticket.STATE_REFUNDED, null); // excluded

        String csv = service.toCsv(principal, event.getId());
        String[] lines = csv.strip().split("\r\n");

        assertThat(lines[0]).isEqualTo(
                "order_ref,buyer_email,tier,status,checked_in_at,price,purchased_at");
        // 2 sold tickets → 2 data rows (refunded excluded)
        assertThat(lines.length).isEqualTo(3);
        assertThat(csv).contains("Issued");
        assertThat(csv).contains("Checked-in");
        assertThat(csv).contains("GA");
        assertThat(csv).contains("buyer@example.com");
    }
}
