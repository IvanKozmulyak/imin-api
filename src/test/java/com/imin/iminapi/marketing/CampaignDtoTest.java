package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.model.Campaign;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignDtoTest {

    private Campaign sample() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("Launch night");
        c.setStatus("draft");
        c.setSubject("Doors open Friday");
        c.setPreheader("Grab tickets");
        c.setBodyMd("Hey {{firstName}}");
        c.setOrigin("manual");
        c.setCreatedAt(Instant.parse("2026-07-11T10:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-07-11T10:05:00Z"));
        return c;
    }

    @Test
    void detail_dto_maps_email_fields() {
        CampaignDto d = CampaignDto.from(sample());
        assertThat(d.channel()).isEqualTo("email");
        assertThat(d.subject()).isEqualTo("Doors open Friday");
        assertThat(d.bodyMd()).isEqualTo("Hey {{firstName}}");
        assertThat(d.status()).isEqualTo("draft");
    }

    @Test
    void summary_dto_projects_list_columns() {
        CampaignSummary s = CampaignSummary.from(sample());
        assertThat(s.name()).isEqualTo("Launch night");
        assertThat(s.channel()).isEqualTo("email");
        assertThat(s.origin()).isEqualTo("manual");
        assertThat(s.recipientCount()).isNull();   // no send yet in Phase 1
    }
}
