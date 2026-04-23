package com.imin.iminapi.web;

import com.imin.iminapi.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class IdempotencyKeyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurger.class);
    private final IdempotencyKeyRepository repo;

    public IdempotencyKeyPurger(IdempotencyKeyRepository repo) { this.repo = repo; }

    @Scheduled(cron = "0 0 * * * *") // top of every hour
    @Transactional
    public void purgeExpired() {
        int n = repo.deleteExpired(Instant.now());
        if (n > 0) log.info("Purged {} expired idempotency keys", n);
    }
}
