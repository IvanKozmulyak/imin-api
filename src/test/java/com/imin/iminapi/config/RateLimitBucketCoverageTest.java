package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rate-limit bucket a controller asks for must actually be configured.
 *
 * <p><b>Why this test exists.</b> {@code RateLimitConfig} builds a fixed map and
 * its lambda ends with {@code throw new IllegalArgumentException("Unknown bucket
 * …")}, which {@code GlobalExceptionHandler} turns into a <b>500</b>. So a
 * typo'd or unregistered bucket name is not a soft failure — it is a guaranteed
 * server error on every call to that endpoint.
 *
 * <p>And the normal suite cannot catch it. {@code RateLimitConfig} is
 * {@code @Profile("!test")}, and the test double at
 * {@link TestRateLimitConfig} does {@code buckets.computeIfAbsent(...)} — it
 * <i>invents</i> a bucket for any name it is handed. An endpoint with an
 * unregistered bucket therefore passes its own integration test and 500s in
 * production. That is exactly how {@code buyer-order-resend} was nearly
 * shipped.
 *
 * <p>This test reads the source rather than the wiring on purpose: the
 * production config bean is not even loaded under the test profile, so there is
 * no map to inspect. The property keys are the contract both sides agree on.
 */
class RateLimitBucketCoverageTest {

    private static final Pattern CONSUME =
            Pattern.compile("\\.consume\\(\\s*\"([a-z0-9-]+)\"");

    @Test
    void everyBucketNameUsedInMainCodeIsConfigured() throws IOException {
        Path main = Path.of("src/main/java");
        Path yaml = Path.of("src/main/resources/application.yaml");
        String config = Files.readString(yaml, StandardCharsets.UTF_8);

        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(main)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                Matcher m = CONSUME.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) used.add(m.group(1));
            }
        }

        assertThat(used)
                .as("the regex found no rate-limited call sites at all — it has probably rotted")
                .isNotEmpty();

        Set<String> missing = new TreeSet<>();
        for (String bucket : used) {
            if (!config.contains("\n    " + bucket + ":")) missing.add(bucket);
        }

        assertThat(missing)
                .as("""
                    These bucket names are passed to RateLimiter.consume() but have no
                    imin.ratelimit.<name> block in application.yaml. RateLimitConfig throws
                    on an unknown bucket and the global handler turns that into a 500, so
                    each of these is a guaranteed server error in production. The test
                    double invents buckets on demand, which is why nothing else catches it.
                    Add the config block AND the @Value pair AND the configs.put() in
                    RateLimitConfig.""")
                .isEmpty();
    }

    /**
     * The YAML check above is necessary but NOT sufficient, and the gap is the
     * one that actually 500s.
     *
     * <p>{@code RateLimitConfig} throws on a name missing from its
     * {@code configs} map — not on a name missing from application.yaml. A bucket
     * with a YAML block but no {@code configs.put(...)} therefore satisfies
     * {@link #everyBucketNameUsedInMainCodeIsConfigured} completely and still
     * fails every single request to its endpoint with a 500. Registering a
     * bucket takes three coordinated edits (YAML block, {@code @Value} pair,
     * {@code configs.put}); the previous test covered one of them.
     *
     * <p>Read from source for the same reason the other test is: the production
     * bean is {@code @Profile("!test")} and is never instantiated here, so there
     * is no live map to inspect.
     */
    @Test
    void everyBucketNameUsedInMainCodeIsRegisteredInRateLimitConfig() throws IOException {
        Path main = Path.of("src/main/java");
        String rateLimitConfig = Files.readString(
                Path.of("src/main/java/com/imin/iminapi/config/RateLimitConfig.java"),
                StandardCharsets.UTF_8);

        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(main)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                Matcher m = CONSUME.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) used.add(m.group(1));
            }
        }

        assertThat(used)
                .as("the regex found no rate-limited call sites at all — it has probably rotted")
                .isNotEmpty();

        Set<String> unregistered = new TreeSet<>();
        for (String bucket : used) {
            if (!rateLimitConfig.contains("configs.put(\"" + bucket + "\"")) unregistered.add(bucket);
        }

        assertThat(unregistered)
                .as("""
                    These bucket names are passed to RateLimiter.consume() but are never
                    put into RateLimitConfig's `configs` map. That map is what the limiter
                    lambda looks the name up in, and a miss throws IllegalArgumentException
                    which the global handler renders as a 500 — so each of these is a
                    guaranteed server error on every call to its endpoint, even if the
                    application.yaml block exists. Add the configs.put() call.""")
                .isEmpty();
    }

    /** The reverse direction is deliberately NOT asserted: an unused bucket is dead config, not a 500. */
    @Test
    void theKnownBucketsIncludeTheBuyerNamespace() throws IOException {
        String config = Files.readString(
                Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8);

        assertThat(config)
                .contains("\n    buyer-order-resend:")
                .contains("\n    buyer-email-add:");
    }
}
