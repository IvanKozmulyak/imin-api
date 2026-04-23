package com.imin.iminapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imin.iminapi.model.IdempotencyKey;
import com.imin.iminapi.repository.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class IdempotencyKeySupport {

    private static final Duration TTL = Duration.ofHours(24);
    private static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule());

    private final IdempotencyKeyRepository repo;

    public IdempotencyKeySupport(IdempotencyKeyRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Cached runOrReplay(UUID orgId, String route, String key, Supplier<Cached> supplier) {
        if (key == null || key.isBlank()) {
            return supplier.get();
        }
        Optional<IdempotencyKey> existing = repo.findByOrgIdAndRouteAndKey(orgId, route, key);
        if (existing.isPresent()) {
            IdempotencyKey k = existing.get();
            return new Cached(k.getResponseStatus(), k.getResponseBody());
        }
        Cached fresh = supplier.get();
        IdempotencyKey row = new IdempotencyKey();
        row.setOrgId(orgId);
        row.setRoute(route);
        row.setKey(key);
        row.setResponseStatus(fresh.status());
        row.setResponseBody(fresh.bodyJson());
        row.setExpiresAt(Instant.now().plus(TTL));
        try {
            repo.save(row);
        } catch (DataIntegrityViolationException ex) {
            // Another concurrent request won the race — return the winning cached response.
            IdempotencyKey winner = repo.findByOrgIdAndRouteAndKey(orgId, route, key)
                    .orElseThrow(() -> ex);
            return new Cached(winner.getResponseStatus(), winner.getResponseBody());
        }
        return fresh;
    }

    /** Helper for callers that have an object — serialise to JSON for storage. */
    public Cached toCached(int status, Object body) {
        try {
            return new Cached(status, OM.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise idempotency body", e);
        }
    }

    public record Cached(int status, String bodyJson) {}
}
