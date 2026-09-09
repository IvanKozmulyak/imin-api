package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * infra-17: a configuration key nothing reads is worse than no key at all.
 *
 * <p>{@code replicate.timeout-seconds} and the whole {@code openai.image} block —
 * {@code OPENAI_API_KEY}/{@code OPENAI_BASE_URL}/{@code OPENAI_IMAGE_MODEL} — were
 * read by nothing after the native Ideogram V3 migration: no {@code @Value}, no
 * {@code @ConfigurationProperties} prefix. Their neighbours are live
 * ({@code replicate.max-concurrent} is read, {@code spring.ai.openai.api-key} is a
 * separate real key), which is exactly what made them look load-bearing. An
 * operator setting {@code OPENAI_BASE_URL} in an environment changed nothing and
 * got no warning.
 *
 * <p>This test is a regression guard, not a general dead-config scan: it names the
 * two namespaces that were removed, so re-adding one is a deliberate act that has
 * to come with a reader.
 */
class InertConfigKeysTest {

    @Test
    void the_removed_vendor_keys_stay_removed_unless_something_reads_them() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8);
        String java = allMainSources();

        Set<String> reintroduced = new TreeSet<>();
        if (yaml.contains("\n  timeout-seconds: ") && !java.contains("replicate.timeout-seconds")) {
            reintroduced.add("replicate.timeout-seconds");
        }
        if (yaml.contains("\nopenai:") && !java.contains("openai.image")) {
            reintroduced.add("openai.image.*");
        }

        assertThat(reintroduced)
                .as("""
                    These application.yaml keys are back with nothing in src/main reading
                    them. A key that binds to no @Value and no @ConfigurationProperties
                    prefix is a silent no-op: setting the environment variable it names
                    changes nothing and reports nothing. Add the reader, or drop the key.""")
                .isEmpty();
    }

    private static String allMainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) all.append(Files.readString(file, StandardCharsets.UTF_8));
        }
        return all.toString();
    }
}
