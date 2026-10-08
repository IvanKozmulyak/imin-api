package com.imin.iminapi.controller.event;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The attendee CSV is the largest disclosure of personal data this API makes and it leaves the platform,
 * so each export leaves an audit row naming the event and the size, never the contents.
 */
@IminIntegrationTest
class AttendeeExportAuditTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows auditRows;
    @Autowired MutableClock clock;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    private Event eventWithTwoAttendees(Organization org, User owner, String buyerEmail) {
        Event e = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        Order o = fx.order(e, buyerEmail);
        fx.ticket(o, Ticket.STATE_ISSUED);
        fx.ticket(o, Ticket.STATE_ISSUED);
        return e;
    }

    @Test
    void exporting_the_attendee_list_writes_an_audit_row_naming_the_event_and_the_size() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        String buyer = fx.email("buyer");
        Event e = eventWithTwoAttendees(org, owner, buyer);

        mvc.perform(get("/api/v1/events/{id}/attendees/export", e.getId()).with(as(owner)))
                .andExpect(status().isOk());

        AuditLog row = auditRows.assertRecorded(org.getId(), AuditActions.ATTENDEES_EXPORTED, "event", e.getId());
        assertThat(row.getSummary()).contains("2 row(s)").doesNotContain(buyer);
    }

    /** A request the export refuses is not an export and must not read as one. */
    @Test
    void a_refused_export_writes_no_row() throws Exception {
        Organization victim = fx.org();
        Event foreign = eventWithTwoAttendees(victim, fx.owner(victim), fx.email("buyer"));
        Organization org = fx.org();
        User owner = fx.owner(org);

        mvc.perform(get("/api/v1/events/{id}/attendees/export", foreign.getId()).with(as(owner)))
                .andExpect(status().isNotFound());

        assertThat(auditRows.forOrg(org.getId()))
                .noneMatch(r -> AuditActions.ATTENDEES_EXPORTED.equals(r.getAction()));
        assertThat(auditRows.forOrg(victim.getId()))
                .noneMatch(r -> AuditActions.ATTENDEES_EXPORTED.equals(r.getAction()));
    }
}
