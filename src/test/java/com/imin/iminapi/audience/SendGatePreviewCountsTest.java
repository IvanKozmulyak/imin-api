package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the pure bucketing logic of previewCounts.bucket — no Spring context needed;
 * we call the static grouping helper directly against a hand-built GateResult.
 */
class SendGatePreviewCountsTest {

    @Test
    void buckets_each_reason_into_its_own_count() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(),
             d = UUID.randomUUID(), e = UUID.randomUUID(), f = UUID.randomUUID(),
             g = UUID.randomUUID();
        var gate = new SendGateService.GateResult(
                List.of(a, b),                                   // 2 sendable
                List.of(new ExclusionReason(c, "marketing_unsubscribed"),
                        new ExclusionReason(d, "marketing_suppressed"),
                        new ExclusionReason(e, "deliverability_suppressed"),
                        new ExclusionReason(f, "no_lawful_basis"),
                        new ExclusionReason(g, "no_email")));

        PreviewAudienceResponse r = SendGateService.bucket(gate);

        assertThat(r.sendable()).isEqualTo(2);
        assertThat(r.excluded().unsubscribed()).isEqualTo(1);
        assertThat(r.excluded().marketingSuppressed()).isEqualTo(1);
        assertThat(r.excluded().deliverabilitySuppressed()).isEqualTo(1);
        assertThat(r.excluded().noBasis()).isEqualTo(1);
        assertThat(r.excluded().noEmail()).isEqualTo(1);
        assertThat(r.excluded().noPhone()).isEqualTo(0);
    }

    @Test
    void empty_gate_is_all_zero() {
        PreviewAudienceResponse r = SendGateService.bucket(
                new SendGateService.GateResult(List.of(), List.of()));
        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().unsubscribed()).isZero();
        assertThat(r.excluded().noBasis()).isZero();
        assertThat(r.excluded().noEmail()).isZero();
    }
}
