package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.MetaTestEventResult;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.marketing.service.MetaCapiPoller;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@IminIntegrationTest
class MetaCapiPollerTest {

    @Autowired MetaCapiPoller poller;
    @Autowired MetaCapiEventRepository capiRepo;
    @Autowired MetaPixelConnectionRepository connRepo;
    @Autowired MetaGraphClient graphClient;
    @Autowired JdbcTemplate jdbc;

    private final Set<UUID> orgIds = new LinkedHashSet<>();

    @AfterEach
    void deleteOwnOutboxRows() {
        // A backed-off row stays due for the next drain of any later class.
        for (UUID orgId : orgIds) jdbc.update("delete from meta_capi_events where org_id = ?", orgId);
    }

    private UUID orgWithPixel() {
        // A cipher-produced value; the poller decrypts before use. Use a real cipher
        // round-trip so decrypt succeeds — inject via the writer path in real code.
        return orgWithPixel(encToken("real-token"));
    }

    private UUID orgWithPixel(String tokenEnc) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        MetaPixelConnection c = new MetaPixelConnection();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setEventId(null);
        c.setPixelId("PIX-1");
        c.setCapiAccessTokenEnc(tokenEnc);
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
        // The drain takes the oldest due rows of every org first; this one sorts ahead of any leftover.
        e.setNextAttemptAt(Instant.now().minus(3650, ChronoUnit.DAYS));
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
        Instant drainedAt = Instant.now();
        when(graphClient.sendEvents(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.anyList()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.META_UPSTREAM_ERROR, "boom"));

        poller.drain();

        MetaCapiEvent reloaded = capiRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MetaCapiEvent.STATUS_PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo((short) 1);
        // Backoff pushed the retry from the past to about a minute after the drain.
        assertThat(reloaded.getNextAttemptAt()).isAfter(drainedAt);
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

    /** A stored token that no longer decrypts is never sent to Meta; the row backs off like any failure. */
    @Test
    void undecryptableTokenIsRetriedNotSent() {
        UUID orgId = orgWithPixel("not-a-cipher-text");
        MetaCapiEvent e = pending(orgId);

        poller.drain();

        MetaCapiEvent reloaded = capiRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MetaCapiEvent.STATUS_PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo((short) 1);
        assertThat(reloaded.getSentAt()).isNull();
        assertThat(reloaded.getLastError()).startsWith("Token decrypt failed");
    }

    // Helper: encrypt with the same cipher the poller uses (test key from application-test).
    @Autowired com.imin.iminapi.marketing.crypto.CapiTokenCipher cipher;
    private String encToken(String plain) { return cipher.encrypt(plain); }
}
