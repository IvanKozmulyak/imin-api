package com.imin.iminapi.marketing;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.moderation.ModerationProperties;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only IMIN staff lift a breaker pause, only a paused org can be resumed, and the lift is on the org's trail. */
@IminIntegrationTest
class InternalMarketingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired ModerationProperties moderation;
    @Autowired PropertyFlips flips;
    @Autowired AuditRows audit;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"staffOnAPausedOrg", "ownerOfThePausedOrg", "staffOnAnUnpausedOrg"})
    void onlyStaffResumeAPausedOrg(String caller) throws Exception {
        Organization target = fx.org();
        User targetOwner = fx.owner(target);
        User staff = fx.owner(fx.org());
        flips.set(moderation, "staffUserIds", Set.of(staff.getId()));
        Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        if (!"staffOnAnUnpausedOrg".equals(caller)) orgs.pauseMarketingIfNotPaused(target.getId(), "bounce_rate", pausedAt);
        // A settings save loaded before the resume and written after it must not undo the resume.
        Organization stale = orgs.findById(target.getId()).orElseThrow();
        AuthPrincipal p = fx.principal("ownerOfThePausedOrg".equals(caller) ? targetOwner : staff);

        var result = mvc.perform(post("/api/v1/internal/marketing/orgs/{orgId}/resume", target.getId())
                .with(authentication(new UsernamePasswordAuthenticationToken(p, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())))))
                .contentType("application/json").content("{\"note\":\"Bounce list cleaned, OK to send\"}"));

        Organization after = orgs.findById(target.getId()).orElseThrow();
        switch (caller) {
            case "staffOnAPausedOrg" -> {
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.liftedReason").value("bounce_rate"));
                stale.setBrandName("Renamed meanwhile");
                orgs.save(stale);
                Organization saved = orgs.findById(target.getId()).orElseThrow();
                assertThat(saved.getMarketingPausedAt()).isNull();
                assertThat(saved.getMarketingPausedReason()).isNull();
                assertThat(saved.getMarketingResumedAt()).isAfter(pausedAt);
                AuditLog row = audit.assertRecorded(target.getId(), "MARKETING_SENDING_RESUMED", "organization",
                        target.getId());
                assertThat(row.getActorId()).isEqualTo(staff.getId());
                assertThat(row.getSummary()).contains("bounce_rate").contains("Bounce list cleaned, OK to send");
            }
            case "ownerOfThePausedOrg" -> {
                result.andExpect(status().isNotFound());
                assertThat(after.getMarketingPausedAt()).isNotNull();
                assertThat(after.getMarketingResumedAt()).isNull();
                assertThat(audit.forOrg(target.getId()))
                        .noneMatch(r -> "MARKETING_SENDING_RESUMED".equals(r.getAction()));
            }
            default -> {
                result.andExpect(status().isConflict());
                assertThat(after.getMarketingResumedAt()).isNull();
                assertThat(audit.forOrg(target.getId()))
                        .noneMatch(r -> "MARKETING_SENDING_RESUMED".equals(r.getAction()));
            }
        }
    }
}
