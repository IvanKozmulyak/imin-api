package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertSettings;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The stock-alert card endpoints, their roles and state machine, and the readiness check at Send. */
@IminIntegrationTest
class StockAlertServiceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired StockAlertTestSupport support;
    @Autowired StockAlertService stockAlerts;
    @Autowired StockAlertProperties props;
    @Autowired StockAlertRepository alerts;
    @Autowired CampaignService campaignService;
    @Autowired CampaignRepository campaigns;
    @Autowired UserRepository users;
    @Autowired AuditRows audit;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private StockAlertTestSupport.Night night;
    private AuthPrincipal owner;
    private TicketTier regular;

    @BeforeEach
    void seed() {
        flips.set(props, "enabled", true);
        night = support.awakeNight();
        orgIds.add(night.org().getId());
        owner = fx.principal(night.owner());
        regular = support.tier(night.event(), "Regular", 2000, 100, 85, null, 0);
        support.mailable(night.org().getId(), 2);
    }

    @AfterEach
    void deleteOwnRows() {
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    private UsernamePasswordAuthenticationToken auth(AuthPrincipal p) {
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())));
    }

    private AuthPrincipal member() {
        User u = new User();
        u.setEmail(fx.email("member"));
        u.setOrgId(night.org().getId());
        u.setRole(UserRole.MEMBER);
        return fx.principal(users.save(u));
    }

    private String base() {
        return "/api/v1/events/" + night.event().getId() + "/stock-alerts";
    }

    private Map<String, String> sendRefusal(UUID campaignId) {
        try {
            campaignService.send(campaignId, owner, UUID.randomUUID().toString(), null);
        } catch (ApiException e) {
            assertThat(e.code().name()).isEqualTo("FIELD_INVALID");
            return e.fields();
        }
        throw new AssertionError("send was accepted");
    }

    @Test
    void sendReadiness() {
        StockAlert a = support.readyAlert(night.event(), regular, StockAlertKind.LAST_TICKETS, 20);
        CampaignDto draft = stockAlerts.review(owner, night.event().getId(), a.getId());

        // The facts block deleted: nothing would state the checked numbers.
        campaignService.patch(owner, draft.id(), new PatchCampaignRequest(null, null, null, null, null,
                "Hurry, almost gone! {{tickets_button}}", null));
        assertThat(sendRefusal(draft.id())).containsEntry("stockFacts", "required");

        // Block back, but the tier sold out since: the claim is no longer true.
        campaignService.patch(owner, draft.id(), new PatchCampaignRequest(null, null, null, null, null,
                "Update:\n\n{{stock_facts}}\n\n{{tickets_button}}", null));
        support.setSold(regular, 100);
        assertThat(sendRefusal(draft.id())).containsEntry("stockFacts", "no_longer_true");
        assertThat(campaigns.findByIdAndOrgId(draft.id(), night.org().getId()).orElseThrow().getStatus())
                .isEqualTo("draft");

        // Raw stock tokens in a normal campaign: nothing would fill or re-check them.
        CampaignDto manual = campaignService.create(owner, new CreateCampaignRequest("email", "Manual",
                draft.segmentId(), night.event().getId(), "Only {{tickets_left}} left", null,
                "Body {{price_now}}", null));
        Map<String, String> fields = sendRefusal(manual.id());
        assertThat(fields).containsEntry("subject", "stock_token_outside_alert")
                .containsEntry("bodyMd", "stock_token_outside_alert");
        assertThat(campaigns.findByIdAndOrgId(manual.id(), night.org().getId()).orElseThrow().getStatus())
                .isEqualTo("draft");
    }

    @Test
    void rolesAndStateMachine() throws Exception {
        String autoOn = "{\"lastTicketsOn\":true,\"priceUpOn\":false,\"priceUpLeadHours\":24,\"mode\":\"auto\","
                + "\"autoConfirmed\":true}";
        mvc.perform(put(base() + "/settings").with(authentication(auth(member())))
                        .contentType(MediaType.APPLICATION_JSON).content(autoOn))
                .andExpect(status().isForbidden());
        mvc.perform(put(base() + "/settings").with(authentication(auth(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(autoOn.replace(",\"autoConfirmed\":true", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
        // A fixed threshold can only lower the tier's auto rule (at most 30), so 50 is refused with the field named.
        mvc.perform(put(base() + "/settings").with(authentication(auth(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastTicketsOn\":true,\"lastTicketsThreshold\":50,\"priceUpLeadHours\":24,\"mode\":\"ask\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.lastTicketsThreshold").value("between_1_and_30"));
        mvc.perform(put(base() + "/settings").with(authentication(auth(owner)))
                        .contentType(MediaType.APPLICATION_JSON).content(autoOn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.mode").value(StockAlertSettings.MODE_AUTO))
                .andExpect(jsonPath("$.reach.canReceive").value(2));
        assertThat(audit.assertRecorded(night.org().getId(), AuditActions.STOCK_ALERT_AUTO_ON, "event",
                night.event().getId()).getActorId()).isEqualTo(night.owner().getId());

        StockAlert a = support.readyAlert(night.event(), regular, StockAlertKind.LAST_TICKETS, 20);
        mvc.perform(post(base() + "/" + a.getId() + "/review").with(authentication(auth(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("stock_alert"))
                .andExpect(jsonPath("$.status").value("draft"));
        mvc.perform(post(base() + "/" + a.getId() + "/review").with(authentication(auth(owner))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaigns WHERE org_id = ? AND origin = 'stock_alert'",
                Long.class, night.org().getId())).isEqualTo(1L);
        mvc.perform(post(base() + "/" + a.getId() + "/dismiss").with(authentication(auth(owner))))
                .andExpect(status().isConflict());

        UUID campaignId = alerts.findById(a.getId()).orElseThrow().getCampaignId();
        assertThatThrownBy(() -> campaignService.duplicate(owner, campaignId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code().name()).isEqualTo("INVALID_STATE"));

        // The card reads the drafted alert with its campaign, and another org's event is a 404.
        mvc.perform(get(base()).with(authentication(auth(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].status").value(StockAlert.DRAFTED))
                .andExpect(jsonPath("$.alerts[0].campaignStatus").value("draft"));
        StockAlertTestSupport.Night other = support.awakeNight();
        orgIds.add(other.org().getId());
        mvc.perform(get("/api/v1/events/" + other.event().getId() + "/stock-alerts").with(authentication(auth(owner))))
                .andExpect(status().isNotFound());
    }

    @Test
    void dismissedAlertNeverComesBack_andAnEventChangeIsRefused() throws Exception {
        StockAlert dismissed = support.readyAlert(night.event(), regular, StockAlertKind.LAST_TICKETS, 20);
        mvc.perform(post(base() + "/" + dismissed.getId() + "/dismiss").with(authentication(auth(owner))))
                .andExpect(status().isNoContent());
        assertThat(alerts.findById(dismissed.getId()).orElseThrow().getStatus()).isEqualTo(StockAlert.DISMISSED);
        mvc.perform(post(base() + "/" + dismissed.getId() + "/review").with(authentication(auth(owner))))
                .andExpect(status().isConflict());

        TicketTier other = support.tier(night.event(), "Late", 3000, 100, 90, null, 1);
        StockAlert a = support.readyAlert(night.event(), other, StockAlertKind.LAST_TICKETS, 20);
        CampaignDto draft = stockAlerts.review(owner, night.event().getId(), a.getId());
        UUID otherEvent = fx.event(night.org(), night.owner(), com.imin.iminapi.model.EventStatus.LIVE,
                java.time.Instant.now().plusSeconds(864_000)).getId();
        assertThatThrownBy(() -> campaignService.patch(owner, draft.id(), new PatchCampaignRequest(null, null,
                com.imin.iminapi.marketing.dto.PatchableUuid.of(otherEvent), null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code().name()).isEqualTo("INVALID_STATE"));
        Campaign kept = campaigns.findByIdAndOrgId(draft.id(), night.org().getId()).orElseThrow();
        assertThat(kept.getEventId()).isEqualTo(night.event().getId());
    }
}
