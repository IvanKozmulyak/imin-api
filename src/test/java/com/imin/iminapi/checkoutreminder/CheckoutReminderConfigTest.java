package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.checkoutreminder.config.CheckoutReminderConfig;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Deploying the API before the privacy text is live must not start emailing buyers. */
class CheckoutReminderConfigTest {

    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withUserConfiguration(CheckoutReminderConfig.class)
            .withInitializer(ctx -> {
                var sources = ctx.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            });

    @Test
    void platformSwitchDefaultsOff() {
        // The Java default, with no property set at all.
        bare.run(ctx -> assertThat(ctx.getBean(CheckoutReminderProperties.class).isEnabled()).isFalse());
        // The shipped yaml, with the variable absent and with it set to empty.
        shipped().run(ctx -> assertThat(ctx.getBean(CheckoutReminderProperties.class).isEnabled()).isFalse());
        shipped().withPropertyValues("IMIN_CHECKOUT_REMINDER_ENABLED=")
                .run(ctx -> assertThat(ctx.getBean(CheckoutReminderProperties.class).isEnabled()).isFalse());
    }

    private ApplicationContextRunner shipped() {
        return bare.withInitializer(ctx -> {
            try {
                new YamlPropertySourceLoader()
                        .load("shipped", new FileSystemResource("src/main/resources/application.yaml"))
                        .forEach(ctx.getEnvironment().getPropertySources()::addLast);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
