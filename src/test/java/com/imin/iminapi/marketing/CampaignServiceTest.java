package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignServiceTest {

    @Autowired CampaignService service;
    @MockitoBean AuditLogger auditLogger;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private AuthPrincipal principal(UUID org) {
        return new AuthPrincipal(USER, org, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void create_persists_a_draft_and_returns_detail() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch night", null, null, null, null, null));
        assertThat(d.status()).isEqualTo("draft");
        assertThat(d.channel()).isEqualTo("email");
        assertThat(d.name()).isEqualTo("Launch night");
        assertThat(service.get(principal(ORG), d.id()).name()).isEqualTo("Launch night");
    }

    @Test
    void create_rejects_blank_name() {
        assertThatThrownBy(() -> service.create(principal(ORG),
                new CreateCampaignRequest("email", "  ", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void create_rejects_unknown_channel() {
        assertThatThrownBy(() -> service.create(principal(ORG),
                new CreateCampaignRequest("carrier-pigeon", "x", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void get_other_orgs_campaign_is_not_found() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Mine", null, null, null, null, null));
        assertThatThrownBy(() -> service.get(principal(OTHER_ORG), d.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void patch_applies_only_supplied_fields_on_a_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch", null, null, "Old subj", null, "body"));
        CampaignDto patched = service.patch(principal(ORG), d.id(),
                new PatchCampaignRequest(null, null, null, "New subj", null, null));
        assertThat(patched.subject()).isEqualTo("New subj");
        assertThat(patched.name()).isEqualTo("Launch");   // untouched
        assertThat(patched.bodyMd()).isEqualTo("body");    // untouched
    }

    @Test
    void patch_rejects_non_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Launch", null, null, null, null, null));
        service.forceStatusForTest(d.id(), "sent");
        assertThatThrownBy(() -> service.patch(principal(ORG), d.id(),
                new PatchCampaignRequest("x", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void list_is_org_scoped_and_channel_filtered() {
        service.create(principal(ORG),
                new CreateCampaignRequest("email", "E1", null, null, null, null, null));
        service.create(principal(OTHER_ORG),
                new CreateCampaignRequest("email", "Other", null, null, null, null, null));
        List<CampaignSummary> mine = service.list(principal(ORG), "email", null, 0, 50);
        assertThat(mine).extracting(CampaignSummary::name).contains("E1").doesNotContain("Other");
    }

    @Test
    void duplicate_clones_into_a_new_draft() {
        CampaignDto d = service.create(principal(ORG),
                new CreateCampaignRequest("email", "Repeat night", null, null, "Subj", "Pre", "body"));
        service.forceStatusForTest(d.id(), "sent");
        CampaignDto copy = service.duplicate(principal(ORG), d.id());
        assertThat(copy.id()).isNotEqualTo(d.id());
        assertThat(copy.status()).isEqualTo("draft");
        assertThat(copy.name()).isEqualTo("Repeat night (copy)");
        assertThat(copy.subject()).isEqualTo("Subj");
        assertThat(copy.bodyMd()).isEqualTo("body");
    }
}
