package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.service.MomentumThresholds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class MomentumThresholdsTest {

    @Autowired
    MomentumThresholds t;

    @Test
    void defaultsMatchSpecSection6_1() {
        assertThat(t.getMinAudienceFloor()).isEqualTo(10);
        assertThat(t.getCooldownDays()).isEqualTo(7);
        assertThat(t.getLaunchAfterHours()).isEqualTo(48);
        assertThat(t.getLaunchMaxSellThroughPct()).isEqualTo(15);
        assertThat(t.getSlumpMinDaysOut()).isEqualTo(14);
        assertThat(t.getSlumpMinSellThroughPct()).isEqualTo(15);
        assertThat(t.getSlumpTargetPct()).isEqualTo(50);
        assertThat(t.getUrgencyBeforeHours()).isEqualTo(72);
        assertThat(t.getUrgencyMinSellThroughPct()).isEqualTo(30);
        assertThat(t.getUrgencyMaxSellThroughPct()).isEqualTo(90);
    }
}
