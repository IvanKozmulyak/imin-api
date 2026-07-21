package com.imin.iminapi.service.ai;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * AiQuotaService — the rolling-24h anti-abuse ceiling on poster generation. Default limit is 3
 * (imin.ai-quota.image-per-day, no override in the test yaml).
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AiQuotaServiceTest {

    @Autowired AiQuotaService quota;
    @Autowired AiGenerationUsageRepository usageRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AiQuotaProperties props;

    private AuthPrincipal principal(UUID userId, UUID orgId) {
        return new AuthPrincipal(userId, orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private void seedUsage(UUID userId, UUID orgId, Instant createdAt) {
        AiGenerationUsage u = new AiGenerationUsage();
        u.setUserId(userId);
        u.setOrgId(orgId);
        u.setKind("image");
        u.setCreatedAt(createdAt);
        usageRepo.save(u);
    }

    private long imageRows(UUID userId) {
        return usageRepo.countByUserIdAndKindAndCreatedAtAfter(
                userId, "image", Instant.now().minus(Duration.ofDays(2)));
    }

    @Test
    void allowsAndRecordsUnderLimit() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        seedUsage(userId, orgId, Instant.now());   // 1 of 3 used

        quota.checkAndRecordImage(principal(userId, orgId));

        assertThat(imageRows(userId)).isEqualTo(2); // the attempt was recorded
    }

    @Test
    void blocksAtLimitWith429Envelope() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        for (int i = 0; i < props.getImagePerDay(); i++) {
            seedUsage(userId, orgId, Instant.now());
        }

        ApiException ex = catchThrowableOfType(
                () -> quota.checkAndRecordImage(principal(userId, orgId)), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.code()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
        assertThat(ex.fields())
                .containsEntry("limit", String.valueOf(props.getImagePerDay()))
                .containsEntry("used", String.valueOf(props.getImagePerDay()))
                .containsKey("resetAt");
        // over-limit call must NOT record another attempt
        assertThat(imageRows(userId)).isEqualTo(props.getImagePerDay());
    }

    @Test
    void unlimitedOrgBypassesAndRecordsNothing() {
        UUID userId = UUID.randomUUID();
        Organization org = new Organization();
        org.setName("Unlimited Co");
        org.setSlug("unlimited-" + UUID.randomUUID());
        org.setContactEmail("u@example.com");
        org.setCountry("NL");
        org.setAiUnlimited(true);
        org = orgRepo.save(org);
        // already over the limit — bypass must ignore it
        for (int i = 0; i < props.getImagePerDay() + 2; i++) {
            seedUsage(userId, org.getId(), Instant.now());
        }
        long before = imageRows(userId);

        quota.checkAndRecordImage(principal(userId, org.getId()));

        assertThat(imageRows(userId)).isEqualTo(before); // nothing recorded
    }

    @Test
    void rowsOutsideRollingWindowDoNotCount() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        // 3 attempts but all older than 24h → window is empty
        for (int i = 0; i < props.getImagePerDay(); i++) {
            seedUsage(userId, orgId, Instant.now().minus(Duration.ofHours(25)));
        }

        // must be allowed despite 3 historic rows
        quota.checkAndRecordImage(principal(userId, orgId));

        assertThat(quota.status(principal(userId, orgId)).image().used()).isEqualTo(1);
    }

    @Test
    void statusReflectsUsageAndRemaining() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        seedUsage(userId, orgId, Instant.now());
        seedUsage(userId, orgId, Instant.now());

        var status = quota.status(principal(userId, orgId));

        assertThat(status.unlimited()).isFalse();
        assertThat(status.image().limit()).isEqualTo(props.getImagePerDay());
        assertThat(status.image().used()).isEqualTo(2);
        assertThat(status.image().remaining()).isEqualTo(props.getImagePerDay() - 2);
        assertThat(status.image().resetAt()).isNotNull();
    }
}
