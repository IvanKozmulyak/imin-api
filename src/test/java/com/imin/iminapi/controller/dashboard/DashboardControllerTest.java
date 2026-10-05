package com.imin.iminapi.controller.dashboard;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.dashboard.DashboardPulseResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse.*;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.dashboard.DashboardPulseService;
import com.imin.iminapi.service.dashboard.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class DashboardControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DashboardService service;
    @MockitoBean DashboardPulseService pulseService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubUser
    void get_dashboard_returns_aggregate() throws Exception {
        when(service.build(any(), any(), any())).thenReturn(new DashboardResponse(
                new Greeting("Jaune"),
                new Now(null, 0, 0, 0),
                new Cycle("30d", 0L, 0, 0, new Deltas(0, 0)),
                new LastEvent(null, new LastEventMetrics(0, 0, 0, null)),
                null,
                new Business(0L, 0L, 0L, 0, 0),
                List.of()));

        mvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.greeting.name").value("Jaune"))
                .andExpect(jsonPath("$.cycle.period").value("30d"))
                .andExpect(jsonPath("$.activity.length()").value(0));
    }

    @Test
    @WithStubUser
    void null_deltas_and_avg_ticket_are_sent_as_null_not_dropped() throws Exception {
        when(service.build(any(), any(), any())).thenReturn(new DashboardResponse(
                new Greeting("Jaune"),
                new Now(null, 0, 0, 0),
                new Cycle("all", 2_502L, 3, 1, new Deltas(null, null)),
                new LastEvent(null, new LastEventMetrics(0, 0, null, null)),
                null,
                new Business(2_502L, 1L, 0L, 7L, 0),
                List.of()));

        mvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.deltas", hasKey("revenuePct")))
                .andExpect(jsonPath("$.cycle.deltas.revenuePct").value(nullValue()))
                .andExpect(jsonPath("$.cycle.deltas", hasKey("ticketsPct")))
                .andExpect(jsonPath("$.cycle.deltas.ticketsPct").value(nullValue()))
                .andExpect(jsonPath("$.lastEvent.metrics", hasKey("avgTicketMinor")))
                .andExpect(jsonPath("$.lastEvent.metrics.avgTicketMinor").value(nullValue()))
                .andExpect(jsonPath("$.business.audienceCount").value(7));
    }

    @Test
    void openapi_publishes_the_nullable_delta_marker_and_a_64_bit_audience_count() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.Deltas.properties.revenuePct.description",
                        containsString("Null when the prior window is empty")))
                .andExpect(jsonPath("$.components.schemas.Deltas.properties.ticketsPct.description",
                        containsString("Null when the prior window is empty")))
                .andExpect(jsonPath("$.components.schemas.Business.properties.audienceCount.format").value("int64"));
    }

    @Test
    @WithStubUser
    void pulse_sends_a_null_last_sale_as_null_not_dropped() throws Exception {
        when(pulseService.pulse(any(), isNull())).thenReturn(new DashboardPulseResponse(false, 0, null));

        mvc.perform(get("/api/v1/dashboard/pulse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onSale").value(false))
                .andExpect(jsonPath("$.onSaleCount").value(0))
                .andExpect(jsonPath("$", hasKey("lastSale")))
                .andExpect(jsonPath("$.lastSale").value(nullValue()));
        verify(pulseService).pulse(argThat(p -> ORG.equals(p.orgId())), isNull());
    }

    @Test
    @WithStubUser
    void pulse_passes_the_event_id_through_and_serialises_the_last_sale() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(pulseService.pulse(any(), eq(eventId))).thenReturn(new DashboardPulseResponse(true, 1,
                new DashboardPulseResponse.LastSale(Instant.parse("2026-05-25T19:37:00Z"), eventId,
                        "Party Na Haty", List.of("Early Bird"), 2)));

        mvc.perform(get("/api/v1/dashboard/pulse").param("eventId", eventId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onSale").value(true))
                .andExpect(jsonPath("$.lastSale.at").value("2026-05-25T19:37:00Z"))
                .andExpect(jsonPath("$.lastSale.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.lastSale.eventName").value("Party Na Haty"))
                .andExpect(jsonPath("$.lastSale.tierNames[0]").value("Early Bird"))
                .andExpect(jsonPath("$.lastSale.ticketCount").value(2));
        verify(pulseService).pulse(argThat(p -> ORG.equals(p.orgId())), eq(eventId));
    }

    @Test
    void pulse_without_a_token_is_401() throws Exception {
        mvc.perform(get("/api/v1/dashboard/pulse"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pulseService);
    }

    @Test
    void openapi_publishes_the_pulse_path_and_schema() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard/pulse'].get.parameters[0].name").value("eventId"))
                .andExpect(jsonPath("$.paths['/api/v1/dashboard/pulse'].get.parameters[0].required").value(false))
                .andExpect(jsonPath("$.components.schemas.DashboardPulseResponse.properties.lastSale.type[1]").value("null"))
                .andExpect(jsonPath("$.components.schemas", hasKey("LastSale")));
    }
}
