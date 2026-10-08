package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.webhook.ProviderEventDedupService;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class ProviderEventDedupServiceTest {

    @Autowired ProviderEventDedupService dedup;
    @Autowired JdbcTemplate jdbc;

    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void deleteOwnClaims() {
        for (String id : eventIds) jdbc.update("delete from provider_events where provider_event_id = ?", id);
    }

    /** The claim is unique per (provider, event id); an event without an id cannot be deduped. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "same provider replays     | resend | svix_  | resend | svix_  | false",
            "same id, another provider | resend | shared | bird   | shared | true",
            "blank id                  | resend | '  '   | resend | ''     | true",
    })
    void secondClaim(String label, String firstProvider, String firstId,
                     String secondProvider, String secondId, boolean secondIsFresh) {
        String a = unique(firstId);
        String b = firstId.equals(secondId) ? a : unique(secondId);

        assertThat(dedup.tryClaim(firstProvider, a, "msg_abc", null, null, "email.delivered")).isTrue();
        assertThat(dedup.tryClaim(secondProvider, b, "msg_abc", null, null, "email.delivered"))
                .isEqualTo(secondIsFresh);
    }

    private String unique(String id) {
        if (id.isBlank()) return id;
        String own = id + UUID.randomUUID();
        eventIds.add(own);
        return own;
    }
}
