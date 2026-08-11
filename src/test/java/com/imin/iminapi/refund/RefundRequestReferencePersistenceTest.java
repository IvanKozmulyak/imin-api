package com.imin.iminapi.refund;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V81 at the schema level: the reference column is NOT NULL and UNIQUE, and the
 * operator search actually resolves a quoted code on H2 (PG-compat) the same way it
 * will on Postgres.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefundRequestReferencePersistenceTest {

    @Autowired RefundRequestRepository requests;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    Organization org;
    Event event;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Test Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Great Event");
        e.setSlug("event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCreatedBy(owner.getId());
        event = events.save(e);
    }

    private Order order(String email) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString());
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail(email);
        o.setTotalMinor(2500);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        return orders.save(o);
    }

    private RefundRequest request(String reference, String email) {
        Order o = order(email);
        RefundRequest rr = new RefundRequest();
        rr.setReference(reference);
        rr.setOrderId(o.getId());
        rr.setOrgId(org.getId());
        rr.setEventId(event.getId());
        rr.setBuyerEmail(email);
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("can't make it");
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setPendingMarker(o.getId());
        return requests.saveAndFlush(rr);
    }

    @Test
    void the_reference_is_unique() {
        request("REQ-8K2M-26", "a@example.com");
        assertThatThrownBy(() -> request("REQ-8K2M-26", "b@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_request_without_a_reference_is_rejected() {
        assertThatThrownBy(() -> request(null, "a@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void operator_search_finds_the_request_by_the_code_the_customer_quoted() {
        RefundRequest target = request("REQ-8K2M-26", "buyer@example.com");
        request("REQ-4TXW-26", "someone.else@example.com");

        List<RefundRequest> hits = requests.pageSearch(
                org.getId(), null, List.of(RefundRequestStatus.values()),
                "REQ-8K2M-26", PageRequest.of(0, 25));

        assertThat(hits).extracting(RefundRequest::getId).containsExactly(target.getId());
    }

    @Test
    void operator_search_also_matches_a_buyer_email_fragment() {
        RefundRequest target = request("REQ-8K2M-26", "Buyer@Example.com");
        request("REQ-4TXW-26", "other@example.org");

        List<RefundRequest> hits = requests.pageSearch(
                org.getId(), null, List.of(RefundRequestStatus.values()),
                "example.com", PageRequest.of(0, 25));

        assertThat(hits).extracting(RefundRequest::getId).containsExactly(target.getId());
    }

    @Test
    void operator_search_stays_inside_the_org() {
        request("REQ-8K2M-26", "buyer@example.com");

        List<RefundRequest> hits = requests.pageSearch(
                UUID.randomUUID(), null, List.of(RefundRequestStatus.values()),
                "REQ-8K2M-26", PageRequest.of(0, 25));

        assertThat(hits).isEmpty();
    }

    @Test
    void unsearched_listing_is_unchanged() {
        request("REQ-8K2M-26", "a@example.com");
        request("REQ-4TXW-26", "b@example.com");

        assertThat(requests.page(org.getId(), null, List.of(RefundRequestStatus.values()),
                PageRequest.of(0, 25))).hasSize(2);
    }

    @Test
    void existsByReference_is_the_pre_check_the_generator_loop_relies_on() {
        request("REQ-8K2M-26", "a@example.com");
        assertThat(requests.existsByReference("REQ-8K2M-26")).isTrue();
        assertThat(requests.existsByReference("REQ-4TXW-26")).isFalse();
    }
}
