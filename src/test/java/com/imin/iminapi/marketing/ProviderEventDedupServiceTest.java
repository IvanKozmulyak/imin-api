package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.webhook.ProviderEventDedupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class ProviderEventDedupServiceTest {

    @Autowired ProviderEventDedupService dedup;

    @Test
    void firstClaimSucceedsReplayReturnsFalse() {
        String eventId = "svix_" + UUID.randomUUID();
        boolean first = dedup.tryClaim("resend", eventId, "msg_abc", null, null, "email.delivered", "{}");
        boolean replay = dedup.tryClaim("resend", eventId, "msg_abc", null, null, "email.delivered", "{}");
        assertThat(first).isTrue();
        assertThat(replay).isFalse();
    }

    @Test
    void blankEventIdIsTreatedAsFreshEachTime() {
        // Defensive: no id can't be deduped — mirrors WebhookEventDedupService.
        boolean a = dedup.tryClaim("resend", "  ", "m1", null, null, "email.opened", "{}");
        boolean b = dedup.tryClaim("resend", "", "m1", null, null, "email.opened", "{}");
        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }
}
