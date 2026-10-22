package com.imin.iminapi.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IminIntegrationTest
class MetaConnectionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired MetaPixelConnectionRepository connRepo;
    @Autowired MetaCapiEventRepository capiEvents;
    @Autowired FunnelEventRepository funnel;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditRows audit;
    @Autowired MetaGraphClient graph;
    final ObjectMapper json = new ObjectMapper();

    private final List<UUID> outboxOrgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    private AuthPrincipal principal;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void owner() {
        org = fx.org();
        owner = fx.owner(org);
        principal = fx.principal(owner);
        auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @AfterEach
    void deleteOwnOutboxRows() {
        for (UUID orgId : outboxOrgIds) jdbc.update("delete from meta_capi_events where org_id = ?", orgId);
    }

    private void outboxRow(UUID orgId, String status, int attempts, String lastError, Instant createdAt) {
        outboxOrgIds.add(orgId);
        MetaCapiEvent e = new MetaCapiEvent();
        e.setId(UUID.randomUUID());
        e.setOrgId(orgId);
        e.setOrderId(UUID.randomUUID());
        e.setOrderToken("tok-" + UUID.randomUUID());
        e.setPixelId("123456");
        e.setEmailSha256("a".repeat(64));
        e.setValueMinor(1000L);
        e.setCurrency("eur");
        e.setEventTime(createdAt.getEpochSecond());
        e.setStatus(status);
        e.setAttempts((short) attempts);
        e.setLastError(lastError);
        e.setCreatedAt(createdAt);
        // Not due, so no other class's drain picks it up before the cleanup.
        e.setNextAttemptAt(Instant.now().plus(Duration.ofDays(1)));
        if (MetaCapiEvent.STATUS_SENT.equals(status)) e.setSentAt(createdAt);
        capiEvents.save(e);
    }

    @Test
    void putConnectsAndNeverReturnsToken() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "pixelId", "1234567890",
                "capiAccessToken", "EAAG-super-secret",
                "testEventCode", "TEST123"));
        mvc.perform(put("/api/v1/marketing/meta/connection").with(authentication(auth))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.pixelId").value("1234567890"))
                .andExpect(jsonPath("$.hasToken").value(true))
                // the plaintext token must NEVER appear in the response body
                .andExpect(jsonPath("$.capiAccessToken").doesNotExist())
                .andExpect(jsonPath("$.capiAccessTokenEnc").doesNotExist());
    }

    @Test
    void putRequiresTokenOnFirstConnect() throws Exception {
        String body = json.writeValueAsString(Map.of("pixelId", "1234567890"));
        mvc.perform(put("/api/v1/marketing/meta/connection").with(authentication(auth))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertThat(connRepo.findByOrgIdAndEventIdIsNull(principal.orgId())).isEmpty();
    }

    @Test
    void deleteRemovesConnection() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "pixelId", "999", "capiAccessToken", "tok"));
        mvc.perform(put("/api/v1/marketing/meta/connection").with(authentication(auth))
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/marketing/meta/connection").with(authentication(auth)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/marketing/meta/connection").with(authentication(auth)))
                .andExpect(jsonPath("$.connected").value(false));
    }

    /** The card's delivery health is counted from the caller's own outbox rows over the last 24 hours. */
    @Test
    void statsCountTheCallersOutbox() throws Exception {
        Instant now = Instant.now();
        UUID orgId = principal.orgId();
        outboxRow(orgId, MetaCapiEvent.STATUS_SENT, 0, null, now.minus(Duration.ofHours(1)));
        outboxRow(orgId, MetaCapiEvent.STATUS_SENT, 0, null, now.minus(Duration.ofHours(30))); // outside 24h
        outboxRow(orgId, MetaCapiEvent.STATUS_PENDING, 2, "graph 503", now.minus(Duration.ofHours(2)));
        outboxRow(orgId, MetaCapiEvent.STATUS_DEAD, 5, "dead: boom", now.minus(Duration.ofHours(3)));
        outboxRow(UUID.randomUUID(), MetaCapiEvent.STATUS_SENT, 0, "other org", now); // another org

        mvc.perform(get("/api/v1/marketing/meta/stats").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent24h").value(1))
                // failed24h counts every retried, unsent row created in the window, the dead one too.
                .andExpect(jsonPath("$.failed24h").value(2))
                .andExpect(jsonPath("$.dead").value(1))
                .andExpect(jsonPath("$.lastError").value("graph 503"));
    }

    @Test
    void funnelIsTheCallersOrg() throws Exception {
        Event mine = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(Duration.ofDays(1)));
        FunnelEvent view = new FunnelEvent();
        view.setEventId(mine.getId());
        view.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        view.setAnonId("s1");
        view.setCreatedAt(Instant.now());
        funnel.save(view);

        mvc.perform(get("/api/v1/marketing/meta/funnel").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].iminCount").value(1));
    }

    private UsernamePasswordAuthenticationToken as(UserRole role) {
        AuthPrincipal p = new AuthPrincipal(owner.getId(), org.getId(), role, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"memberPut", "memberDelete", "memberTest", "adminPut"})
    void pixelChangesNeedAnAdminAndAreAudited(String call) throws Exception {
        mvc.perform(put("/api/v1/marketing/meta/connection").with(authentication(auth)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("pixelId", "111", "capiAccessToken", "EAAG-first-secret"))))
                .andExpect(status().isOk());
        String replace = json.writeValueAsString(Map.of("pixelId", "222", "capiAccessToken", "EAAG-second-secret"));

        switch (call) {
            case "memberPut" -> mvc.perform(put("/api/v1/marketing/meta/connection").with(authentication(as(UserRole.MEMBER)))
                    .contentType("application/json").content(replace)).andExpect(status().isForbidden());
            case "memberDelete" -> mvc.perform(delete("/api/v1/marketing/meta/connection")
                    .with(authentication(as(UserRole.MEMBER)))).andExpect(status().isForbidden());
            case "memberTest" -> mvc.perform(post("/api/v1/marketing/meta/connection/test")
                    .with(authentication(as(UserRole.MEMBER)))).andExpect(status().isForbidden());
            default -> mvc.perform(put("/api/v1/marketing/meta/connection").with(authentication(as(UserRole.ADMIN)))
                    .contentType("application/json").content(replace)).andExpect(status().isOk());
        }

        var connection = connRepo.findByOrgIdAndEventIdIsNull(org.getId());
        var connectedRows = audit.forOrg(org.getId()).stream()
                .filter(r -> "META_PIXEL_CONNECTED".equals(r.getAction())).toList();
        if ("adminPut".equals(call)) {
            assertThat(connection).hasValueSatisfying(c -> assertThat(c.getPixelId()).isEqualTo("222"));
            assertThat(connectedRows).hasSize(2).anySatisfy(r -> assertThat(r.getSummary()).contains("222"));
            assertThat(connectedRows).allSatisfy(r -> assertThat(r.getSummary()).doesNotContain("EAAG"));
        } else {
            assertThat(connection).hasValueSatisfying(c -> assertThat(c.getPixelId()).isEqualTo("111"));
            assertThat(connectedRows).hasSize(1);
            assertThat(audit.forOrg(org.getId())).noneMatch(r -> "META_PIXEL_DISCONNECTED".equals(r.getAction()));
            verify(graph, never()).sendEvents(any(), any(), any(), any());
        }
    }
}
