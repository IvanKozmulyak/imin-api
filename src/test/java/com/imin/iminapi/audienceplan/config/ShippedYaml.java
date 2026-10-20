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

/** Binds the audience-plan config from the shipped yaml files (single documents, no profile sections). */
final class ShippedYaml {

    private static final String BASE = "src/main/resources/application.yaml";
    private static final String PROD = "src/main/resources/application-prod.yaml";

    private ShippedYaml() {
    }

    /**
     * Runs {@code check} twice: with the env vars absent, so the yaml defaults apply, and with each stubbed to
     * empty. The OS environment and system properties are removed, so a developer's local value cannot leak in.
     */
    static void run(List<String> envVars, ContextConsumer<AssertableApplicationContext> check) {
        ApplicationContextRunner base = runner(BASE);
        base.run(check);
        base.withPropertyValues(envVars.stream().map(v -> v + "=").toArray(String[]::new)).run(check);
    }

    /** The base yaml with application-prod.yaml over it, as the prod profile binds them; OS env removed. */
    static ApplicationContextRunner prod() {
        return runner(PROD, BASE);
    }

    /** Earlier files win, like a profile document over the base one. */
    private static ApplicationContextRunner runner(String... files) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AudiencePlanConfig.class, AudiencePlanAccess.class)
                .withInitializer(ctx -> {
                    var sources = ctx.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        for (String file : files) {
                            new YamlPropertySourceLoader()
                                    .load("shipped:" + file, new FileSystemResource(file))
                                    .forEach(sources::addLast);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
