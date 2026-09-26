package com.imin.iminapi.marketing.service;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignAiSuggestionsTest {

    @Autowired CampaignAiSuggestions suggestions;
    @Autowired CampaignRepository campaigns;
    @Autowired JdbcTemplate jdbc;

    private UUID campaign() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("c");
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }

    private int rows(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_ai_suggestions WHERE campaign_id = ?",
                Integer.class, id);
    }

    @Test
    void recordThenMatch_ignoresSurroundingWhitespaceAndLineEndings() {
        UUID id = campaign();

        suggestions.record(id, CampaignAiSuggestions.BODY, List.of("Line one\nLine two"));

        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.BODY, "  Line one\r\nLine two\n")).isTrue();
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.BODY, "Line one Line two")).isFalse();
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.SUBJECT, "Line one\nLine two")).isFalse();
    }

    @Test
    void recordingTheSameTextTwice_keepsOneRow() {
        UUID id = campaign();

        suggestions.record(id, CampaignAiSuggestions.SUBJECT, List.of("Same", " Same "));
        suggestions.record(id, CampaignAiSuggestions.SUBJECT, List.of("Same"));

        assertThat(rows(id)).isEqualTo(1);
    }

    @Test
    void nullAndBlankTexts_areNeitherRecordedNorMatched() {
        UUID id = campaign();

        suggestions.record(id, CampaignAiSuggestions.SUBJECT, Arrays.asList(null, "  ", ""));

        assertThat(rows(id)).isZero();
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.SUBJECT, null)).isFalse();
        assertThat(suggestions.wasOffered(id, CampaignAiSuggestions.SUBJECT, " ")).isFalse();
    }

    @Test
    void deletingTheCampaign_removesItsFingerprints() {
        UUID id = campaign();
        suggestions.record(id, CampaignAiSuggestions.SUBJECT, List.of("Gone soon"));

        campaigns.delete(campaigns.findById(id).orElseThrow());

        assertThat(rows(id)).isZero();
    }
}
