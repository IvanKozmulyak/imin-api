package com.imin.iminapi.web;

import com.imin.iminapi.model.IdempotencyKey;
import com.imin.iminapi.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyKeySupportTest {

    IdempotencyKeyRepository repo = mock(IdempotencyKeyRepository.class);
    IdempotencyKeySupport sut = new IdempotencyKeySupport(repo);

    @Test
    void first_call_runs_supplier_and_persists() {
        UUID orgId = UUID.randomUUID();
        when(repo.findByOrgIdAndRouteAndKey(orgId, "/x", "k1")).thenReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();
        var result = sut.runOrReplay(orgId, "/x", "k1", () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(201, "\"hello\"");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(201);
        verify(repo).save(any(IdempotencyKey.class));
    }

    @Test
    void second_call_returns_cached_without_running_supplier() {
        UUID orgId = UUID.randomUUID();
        IdempotencyKey existing = new IdempotencyKey();
        existing.setResponseStatus(201);
        existing.setResponseBody("\"hello\"");
        when(repo.findByOrgIdAndRouteAndKey(orgId, "/x", "k1")).thenReturn(Optional.of(existing));
        AtomicInteger calls = new AtomicInteger();
        var result = sut.runOrReplay(orgId, "/x", "k1", () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(500, "\"oops\"");
        });
        assertThat(calls.get()).isZero();
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.bodyJson()).isEqualTo("\"hello\"");
    }

    @Test
    void concurrent_same_key_returns_winning_cached() {
        UUID orgId = UUID.randomUUID();
        // First findByOrgIdAndRouteAndKey returns empty (both threads pass the check)
        when(repo.findByOrgIdAndRouteAndKey(orgId, "/x", "k2")).thenReturn(Optional.empty());
        // save throws due to unique constraint race
        when(repo.save(any(IdempotencyKey.class))).thenThrow(new DataIntegrityViolationException("duplicate"));
        // After the race, the winning row is found
        IdempotencyKey winner = new IdempotencyKey();
        winner.setResponseStatus(201);
        winner.setResponseBody("\"winner\"");
        // Second call to findByOrgIdAndRouteAndKey (inside the catch) returns winner
        when(repo.findByOrgIdAndRouteAndKey(orgId, "/x", "k2"))
                .thenReturn(Optional.empty())   // first call in runOrReplay
                .thenReturn(Optional.of(winner)); // second call inside catch

        var result = sut.runOrReplay(orgId, "/x", "k2",
                () -> new IdempotencyKeySupport.Cached(201, "\"mine\""));

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.bodyJson()).isEqualTo("\"winner\"");
    }

    @Test
    void null_or_blank_key_skips_idempotency() {
        UUID orgId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        sut.runOrReplay(orgId, "/x", null, () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(200, "{}");
        });
        sut.runOrReplay(orgId, "/x", "  ", () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(200, "{}");
        });
        assertThat(calls.get()).isEqualTo(2);
        verifyNoInteractions(repo);
    }
}
