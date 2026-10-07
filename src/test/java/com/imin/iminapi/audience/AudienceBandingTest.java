package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audience.service.LifecycleClassifier;
import com.imin.iminapi.audience.service.RfmBander;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure banding behind every audience filter: email identity, RFM bands and the lifecycle state. */
class AudienceBandingTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "'  Ann@X.com ',          ann@x.com",
            "ANN@X.COM,               ann@x.com",
            "ann@x.com,               ann@x.com",
            "'  User@Example.COM  ',  user@example.com",
            "user@example.com,        user@example.com",
            "NULL,                    NULL"})
    void email_normalizer(String raw, String expected) {
        assertThat(EmailNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "recency,   NULL,  0",
            "recency,   200,   0",
            "recency,   150,   1",
            "recency,   80,    2",
            "recency,   45,    3",
            "recency,   20,    4",
            "recency,   15,    4",
            "recency,   14,    5",
            "recency,   7,     5",
            "frequency, 0,     0",
            "frequency, 1,     1",
            "frequency, 2,     2",
            "frequency, 4,     3",
            "frequency, 7,     4",
            "frequency, 10,    5",
            "frequency, 99,    5",
            "monetary,  0,     0",
            "monetary,  500,   1",
            "monetary,  7000,  2",
            "monetary,  15000, 3",
            "monetary,  25000, 4",
            "monetary,  49999, 4",
            "monetary,  50000, 5",
            "monetary,  55000, 5"})
    void rfm_bands(String dimension, Integer input, short expected) {
        short band = switch (dimension) {
            case "recency" -> RfmBander.recency(input);
            case "frequency" -> RfmBander.frequency(input);
            case "monetary" -> RfmBander.monetary(input);
            default -> throw new IllegalArgumentException(dimension);
        };
        assertThat(band).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "0, 0,     NULL, NULL,    prospect",
            "1, 500,   30,   NULL,    firsttime",
            "2, 1000,  20,   NULL,    repeat",
            "4, 20000, 10,   NULL,    vip",
            // VIP needs both thresholds: spend without the fourth event, four events without the spend.
            "3, 25000, 10,   NULL,    repeat",
            "4, 19999, 10,   NULL,    repeat",
            "1, 500,   90,   NULL,    lapsing",
            "1, 500,   180,  NULL,    dormant",
            "2, 1000,  10,   dormant, wonback",
            "5, 25000, 200,  NULL,    vip"})
    void lifecycle(int events, long spendMinor, Integer recencyDays, String priorLifecycle, String expected) {
        Membership m = new Membership();
        m.setOrgId(UUID.randomUUID());
        m.setConsumerId(UUID.randomUUID());
        m.setEvents(events);
        m.setSpendMinor(spendMinor);
        m.setRecencyDays(recencyDays);
        if (priorLifecycle != null) m.setLifecycle(priorLifecycle);

        assertThat(LifecycleClassifier.classify(m)).isEqualTo(expected);
    }
}
