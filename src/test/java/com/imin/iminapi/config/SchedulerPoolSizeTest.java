package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code @Scheduled} pool must be big enough that one slow job cannot stall
 * the rest.
 *
 * <p>Spring's default scheduler is <b>single-threaded</b>, and
 * {@code spring.task.scheduling.pool.size} was set nowhere in this repo — a fact
 * {@code PushConfig} already documents as a hazard ("pool size 1 … would stall
 * every other @Scheduled job … including ReservationSweeper"). Dozens of jobs
 * share that one thread: {@code PaidFulfilmentReconciler} walks up to 1000
 * PaymentIntents in pages of 100 through the Stripe SDK every 15 minutes, and
 * {@code EventReminderSender} self-documents ~80s of synchronous Resend sends
 * per 5-minute tick. While either runs, {@code ReservationSweeper} — the source
 * of truth for inventory holds — cannot, and a stall past a job's
 * {@code lockAtMostFor} lets a second replica re-enter.
 *
 * <p>Read from the production YAML rather than the wired bean: the test profile
 * has its own {@code application.yaml}, so a live {@code TaskScheduler} here
 * would prove nothing about what production runs. ShedLock stays the
 * cross-replica guard; this is the in-JVM one.
 */
class SchedulerPoolSizeTest {

    private static final Pattern POOL_SIZE = Pattern.compile(
            "(?ms)^\\s{2}task:\\s*$.*?^\\s{4}scheduling:\\s*$.*?^\\s{6}pool:\\s*$.*?^\\s{8}size:\\s*(\\d+)\\s*$");

    @Test
    void the_scheduled_pool_is_not_the_single_threaded_default() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8);

        Matcher m = POOL_SIZE.matcher(yaml);
        assertThat(m.find())
                .as("spring.task.scheduling.pool.size is unset, so Spring's single-threaded "
                        + "default scheduler runs every @Scheduled job in this repo")
                .isTrue();
        assertThat(Integer.parseInt(m.group(1)))
                .as("one thread is the defect; a pool of one is the same defect spelled out")
                .isGreaterThan(1);
    }

    /** If the jobs ever thin out to one, the pool above stops being load-bearing. */
    @Test
    void there_are_still_many_scheduled_jobs_sharing_that_pool() throws IOException {
        int jobs = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("@Scheduled")) jobs++;
            }
        }
        assertThat(jobs).isGreaterThan(10);
    }
}
