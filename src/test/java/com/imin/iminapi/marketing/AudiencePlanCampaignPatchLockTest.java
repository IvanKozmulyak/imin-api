package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.PatchableUuid;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** An audience-plan draft keeps its segment and event through the composer PATCH; it can still be deleted. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudiencePlanCampaignPatchLockTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @MockitoBean AuditLogger audit;

    private final UUID orgId = UUID.randomUUID();
    private final UUID segmentId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    private AuthPrincipal owner() {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Campaign draft(String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Arm");
        c.setStatus("draft");
        c.setOrigin(origin);
        c.setSegmentId(segmentId);
        c.setEventId(eventId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private static PatchCampaignRequest retarget(PatchableUuid segment, PatchableUuid event) {
        return new PatchCampaignRequest("Renamed", segment, event, null, null, null, null);
    }

    private Campaign reload(Campaign c) {
        return campaigns.findById(c.getId()).orElseThrow();
    }

    private void assertRefusedAndUnchanged(Campaign c, PatchCampaignRequest req) {
        Throwable t = catchThrowable(() -> service.patch(owner(), c.getId(), req));
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE);
            assertThat(e.getMessage()).isEqualTo("The segment and event of an audience plan campaign cannot be changed");
        });
        Campaign after = reload(c);
        assertThat(after.getSegmentId()).isEqualTo(segmentId);
        assertThat(after.getEventId()).isEqualTo(eventId);
        assertThat(after.getName()).isEqualTo("Arm");
    }

    @Test
    void changingTheSegment_is409_andNothingIsSaved() {
        Campaign c = draft("audience_plan");
        assertRefusedAndUnchanged(c, retarget(PatchableUuid.of(UUID.randomUUID()), null));
    }

    @Test
    void changingTheEvent_is409_andNothingIsSaved() {
        Campaign c = draft("audience_plan");
        assertRefusedAndUnchanged(c, retarget(null, PatchableUuid.of(UUID.randomUUID())));
    }

    @Test
    void unlinkingTheSegment_is409() {
        Campaign c = draft("audience_plan");
        assertRefusedAndUnchanged(c, retarget(PatchableUuid.NULL, null));
    }

    @Test
    void unlinkingTheEvent_is409() {
        Campaign c = draft("audience_plan");
        assertRefusedAndUnchanged(c, retarget(null, PatchableUuid.NULL));
    }

    @Test
    void resendingTheSameSegmentAndEvent_withOtherEdits_isSaved() {
        Campaign c = draft("audience_plan");

        service.patch(owner(), c.getId(), retarget(PatchableUuid.of(segmentId), PatchableUuid.of(eventId)));

        Campaign after = reload(c);
        assertThat(after.getName()).isEqualTo("Renamed");
        assertThat(after.getSegmentId()).isEqualTo(segmentId);
        assertThat(after.getEventId()).isEqualTo(eventId);
    }

    @Test
    void manualCampaign_canStillBeRetargeted() {
        Campaign c = draft("manual");
        UUID otherSegment = UUID.randomUUID();

        service.patch(owner(), c.getId(), retarget(PatchableUuid.of(otherSegment), PatchableUuid.NULL));

        Campaign after = reload(c);
        assertThat(after.getSegmentId()).isEqualTo(otherSegment);
        assertThat(after.getEventId()).isNull();
    }

    @Test
    void audiencePlanDraft_canBeDeleted() {
        Campaign c = draft("audience_plan");

        service.delete(owner(), c.getId());

        assertThat(campaigns.findById(c.getId())).isEmpty();
    }
}
