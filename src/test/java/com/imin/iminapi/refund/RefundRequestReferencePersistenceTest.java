package com.imin.iminapi.refund;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V81 on Postgres: the reference column is UNIQUE, a null reference is refused, and the operator search
 * resolves a quoted code. Rows are committed; every search is scoped to this test's org.
 */
@IminIntegrationTest
class RefundRequestReferencePersistenceTest {

    @Autowired RefundRequestRepository requests;
    @Autowired RefundReferenceGenerator references;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    Organization org;
    Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        User owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, null);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, List.of(org.getId()));
    }

    private Order order(String email) {
        return fx.order(event, email);
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
        String reference = references.next();
        request(reference, "a@example.com");
        // Last statement: on Postgres the violation would abort anything after it in one transaction.
        assertThatThrownBy(() -> request(reference, "b@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_request_without_a_reference_is_rejected() {
        // The column ships NULLABLE (V81 is expand/contract, for the deploy overlap), so @NotNull on the
        // entity is what refuses a null: Bean Validation disables Hibernate's own nullability check.
        assertThatThrownBy(() -> request(null, "a@example.com"))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    void a_pre_V81_insert_that_omits_the_reference_is_accepted_by_the_database() {
        // The deploy-overlap window: the old container's INSERT has no `reference` column at all, and it
        // must land. Plain JDBC so the entity's stricter mapping does not mask what the schema allows.
        Order o = order("legacy@example.com");
        int inserted = jdbc.update("""
                INSERT INTO refund_requests
                  (id, order_id, org_id, event_id, buyer_email, reason, explanation, status,
                   pending_marker, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'CANT_ATTEND', 'legacy', 'PENDING', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(), o.getId(), org.getId(), event.getId(), "legacy@example.com", o.getId());

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void operator_search_finds_the_request_by_the_code_the_customer_quoted() {
        String quoted = references.next();
        RefundRequest target = request(quoted, "buyer@example.com");
        request(references.next(), "someone.else@example.com");

        List<RefundRequest> hits = requests.pageSearch(
                org.getId(), null, List.of(RefundRequestStatus.values()),
                quoted, quoted, PageRequest.of(0, 25));

        assertThat(hits).extracting(RefundRequest::getId).containsExactly(target.getId());
    }

    @Test
    void operator_search_also_matches_a_buyer_email_fragment() {
        RefundRequest target = request(references.next(), "Buyer@Example.com");
        request(references.next(), "other@example.org");

        List<RefundRequest> hits = requests.pageSearch(
                org.getId(), null, List.of(RefundRequestStatus.values()),
                "example.com", "example.com", PageRequest.of(0, 25));

        assertThat(hits).extracting(RefundRequest::getId).containsExactly(target.getId());
    }

    @Test
    void operator_search_stays_inside_the_org() {
        String quoted = references.next();
        request(quoted, "buyer@example.com");

        List<RefundRequest> hits = requests.pageSearch(
                UUID.randomUUID(), null, List.of(RefundRequestStatus.values()),
                quoted, quoted, PageRequest.of(0, 25));

        assertThat(hits).isEmpty();
    }

    @Test
    void unsearched_listing_is_unchanged() {
        RefundRequest a = request(references.next(), "a@example.com");
        RefundRequest b = request(references.next(), "b@example.com");

        assertThat(requests.page(org.getId(), null, List.of(RefundRequestStatus.values()),
                PageRequest.of(0, 25))).extracting(RefundRequest::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void an_email_fragment_shaped_like_a_reference_still_searches_the_email() {
        // "mark-22" normalises to REQ-MARK-22, a valid reference shape. When the normalised form REPLACED
        // the term instead of accompanying it, the email LIKE ran against a string nobody typed.
        RefundRequest target = request(references.next(), "mark-22@example.com");
        request(references.next(), "someone.else@example.org");

        String term = "mark-22";
        String normalized = RefundReferenceGenerator.normalize(term);
        assertThat(normalized).as("the fragment really is reference-shaped").isEqualTo("REQ-MARK-22");

        List<RefundRequest> hits = requests.pageSearch(
                org.getId(), null, List.of(RefundRequestStatus.values()),
                normalized, term, PageRequest.of(0, 25));

        assertThat(hits).extracting(RefundRequest::getId).containsExactly(target.getId());
    }
}
