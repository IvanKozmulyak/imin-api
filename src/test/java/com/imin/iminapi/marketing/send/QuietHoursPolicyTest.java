package com.imin.iminapi.marketing.send;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuietHoursPolicyTest {

    @Test
    void defaultPolicyAllowsSendingAnyTime() {
        // Phase 2 ships a permissive seam so email send is unblocked; Phase 4 tightens
        // to org-local 22:00–09:00 quiet hours (spec §7). enabled=false ⇒ always allowed.
        QuietHoursPolicy policy = new QuietHoursPolicy(false);
        assertThat(policy.canSendNow("Europe/Kyiv")).isTrue();
        assertThat(policy.canSendNow("UTC")).isTrue();
    }
}
