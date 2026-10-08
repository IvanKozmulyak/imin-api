package com.imin.iminapi.audienceplan.config;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Binds the audience-plan config from the shipped main application.yaml (single document, no profile sections). */
final class ShippedYaml {

    private ShippedYaml() {
    }

    /**
     * Runs {@code check} twice: with the env vars absent, so the yaml defaults apply, and with each stubbed to
     * empty. The OS environment and system properties are removed, so a developer's local value cannot leak in.
     */
    static void run(List<String> envVars, ContextConsumer<AssertableApplicationContext> check) {
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withUserConfiguration(AudiencePlanConfig.class, AudiencePlanAccess.class)
                .withInitializer(ctx -> {
                    var sources = ctx.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        new YamlPropertySourceLoader()
                                .load("shipped", new FileSystemResource("src/main/resources/application.yaml"))
                                .forEach(sources::addLast);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        base.run(check);
        base.withPropertyValues(envVars.stream().map(v -> v + "=").toArray(String[]::new)).run(check);
    }
}
