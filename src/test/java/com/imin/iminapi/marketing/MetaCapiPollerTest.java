package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.MetaTestEventResult;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.marketing.service.MetaCapiPoller;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class MetaCapiPollerTest {

    @Autowired MetaCapiPoller poller;
    @Autowired MetaCapiEventRepository capiRepo;
    @Autowired MetaPixelConnectionRepository connRepo;
    @MockitoBean MetaGraphClient graphClient;

    private UUID orgWithPixel() {
        UUID orgId = UUID.randomUUID();
        MetaPixelConnection c = new MetaPixelConnection();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setEventId(null);
        c.setPixelId("PIX-1");
        // A cipher-produced value; the poller decrypts before use. Use a real cipher
        // round-trip so decrypt succeeds — inject via the writer path in real code.
        c.setCapiAccessTokenEnc(encToken("real-token"));
        connRepo.save(c);
        return orgId;
    }

    private MetaCapiEvent pending(UUID orgId) {
        MetaCapiEvent e = new MetaCapiEvent();
        e.setId(UUID.randomUUID());
        e.setOrgId(orgId);
        e.setOrderId(UUID.randomUUID());
        e.setOrderToken("tok-" + UUID.randomUUID()); // NOT NULL; used as Meta event_id
        e.setPixelId("PIX-1");
        e.setEmailSha256("a".repeat(64));
        e.setValueMinor(1000L);
        e.setCurrency("eur");
        e.setEventTime(Instant.now().getEpochSecond());
        e.setStatus(MetaCapiEvent.STATUS_PENDING);
        e.setNextAttemptAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        return capiRepo.save(e);
    }

    @Test
    void marksSentOnSuccess() {
        UUID orgId = orgWithPixel();
        MetaCapiEvent e = pending(orgId);
        when(graphClient.sendEvents(ArgumentMatchers.eq("PIX-1"), ArgumentMatchers.anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.anyList()))
                .thenReturn(new MetaTestEventResult(true, 1, null, "trace"));

        poller.drain();

        MetaCapiEvent reloaded = capiRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MetaCapiEvent.STATUS_SENT);
        assertThat(reloaded.getSentAt()).isNotNull();
    }

    @Test
    void backsOffAndRetriesOnFailure() {
        UUID orgId = orgWithPixel();
        MetaCapiEvent e = pending(orgId);
        // Seeded next_attempt_at is in the past (now-1m); capture it AS PERSISTED so the
        // "backed off into the future" check compares two values read through the same DB
        // representation (H2 TIMESTAMP WITH TIME ZONE shifts an Instant on read; production
        // Postgres timestamptz round-trips it exactly).
        Instant scheduledBeforeDrain = capiRepo.findById(e.getId()).orElseThrow().getNextAttemptAt();
        when(graphClient.sendEvents(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.anyList()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.META_UPSTREAM_ERROR, "boom"));

        poller.drain();

        MetaCapiEvent reloaded = capiRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MetaCapiEvent.STATUS_PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo((short) 1);
        // Backoff pushed the retry from the past into the future (advanced by ~1m+1m past
        // the seeded schedule); comparing to the pre-drain value is TZ-shift-immune.
        assertThat(reloaded.getNextAttemptAt()).isAfter(scheduledBeforeDrain);
        assertThat(reloaded.getLastError()).contains("boom");
    }

    @Test
    void deadLettersAfterFiveAttempts() {
        UUID orgId = orgWithPixel();
        MetaCapiEvent e = pending(orgId);
        e.setAttempts((short) 4); // this failure is the 5th
        capiRepo.save(e);
        when(graphClient.sendEvents(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.anyList()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.META_UPSTREAM_ERROR, "boom"));

        poller.drain();

        MetaCapiEvent reloaded = capiRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MetaCapiEvent.STATUS_DEAD);
        assertThat(reloaded.getAttempts()).isEqualTo((short) 5);
    }

    // Helper: encrypt with the same cipher the poller uses (test key from application-test).
    @Autowired com.imin.iminapi.marketing.crypto.CapiTokenCipher cipher;
    private String encToken(String plain) { return cipher.encrypt(plain); }
}
