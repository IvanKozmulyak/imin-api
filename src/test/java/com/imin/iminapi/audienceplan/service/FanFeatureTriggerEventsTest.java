package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentChanged;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.MembershipProjected;
import com.imin.iminapi.audience.service.MembershipProjector;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The audience writes that must re-trigger the fan-feature projection publish their event. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@RecordApplicationEvents
class FanFeatureTriggerEventsTest {

    @Autowired ConsentService consentService;
    @Autowired ApplicationEvents published;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanLogic planLogic;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired MembershipProjector membershipProjector;

    private FanFeatureFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new FanFeatureFixtures(orgs, users, events, orders, tickets, consumers, memberships);
    }

    @Test
    void consentCapture_publishesConsentChanged() {
        UUID orgId = UUID.randomUUID();
        Membership m = fx.membership(orgId, FanFeatureFixtures.email("capture"));

        consentService.capture(orgId, m.getMembershipId(), "explicit", "checkout", "Ticked the box",
                "email", null, null, ConsentOrigin.DATA_SUBJECT, null);

        assertThat(published.stream(ConsentChanged.class))
                .containsExactly(new ConsentChanged(orgId, m.getMembershipId(), false));
    }

    @Test
    void consentUnsubscribe_publishesConsentChanged() {
        UUID orgId = UUID.randomUUID();
        Membership m = fx.membership(orgId, FanFeatureFixtures.email("unsub"));

        consentService.unsubscribe(orgId, m.getMembershipId(), "one_click", "email", ConsentOrigin.OPERATOR, null);

        assertThat(published.stream(ConsentChanged.class))
                .containsExactly(new ConsentChanged(orgId, m.getMembershipId(), false));
    }

    @Test
    void importCapture_publishesADeferrableConsentChanged() {
        UUID orgId = UUID.randomUUID();
        Membership m = fx.membership(orgId, FanFeatureFixtures.email("imported"));

        consentService.capture(orgId, m.getMembershipId(), "explicit", ImportProvenanceWriter.SOURCE, "Attested",
                "email", null);

        assertThat(published.stream(ConsentChanged.class))
                .containsExactly(new ConsentChanged(orgId, m.getMembershipId(), true));
    }

    @Test
    void globalUnsubscribe_publishesADeferrableConsentChanged() {
        UUID orgId = UUID.randomUUID();
        Membership m = fx.membership(orgId, FanFeatureFixtures.email("global"));

        consentService.unsubscribe(orgId, m.getMembershipId(), "preference_centre_global", "email",
                ConsentOrigin.DATA_SUBJECT_GLOBAL, null);

        assertThat(published.stream(ConsentChanged.class))
                .containsExactly(new ConsentChanged(orgId, m.getMembershipId(), true));
    }

    // Plain projector instances so onTicketsIssued runs on this thread with a capturing publisher.

    @Test
    void ticketsIssued_publishesMembershipProjectedWithTheNormalizedEmail() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("issued");
        Order o = fx.paidOrder(fx.event(org, "Pop", Instant.now().plus(3, ChronoUnit.DAYS)),
                "  " + email.toUpperCase() + " ", Instant.now(), Ticket.STATE_ISSUED);
        List<Object> captured = new ArrayList<>();

        new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, captured::add, orgs, planLogic)
                .onTicketsIssued(new TicketsIssuedEvent(o.getId()));

        assertThat(captured).containsExactly(new MembershipProjected(org.id(), email));
    }

    @Test
    void ticketsIssued_unknownOrder_publishesNothing() {
        List<Object> captured = new ArrayList<>();

        new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, captured::add, orgs, planLogic)
                .onTicketsIssued(new TicketsIssuedEvent(UUID.randomUUID()));

        assertThat(captured).isEmpty();
    }
}
