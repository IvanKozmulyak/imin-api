package com.imin.iminapi.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The staff list is the only key to the internal moderation endpoints: blank must mean nobody, a typo must not boot. */
class ModerationPropertiesTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModerationConfig.class);

    @Test
    void staff_list_binds_blank_to_off_and_rejects_malformed_ids() {
        // Java defaults, no key at all.
        runner.run(ctx -> {
            ModerationProperties p = ctx.getBean(ModerationProperties.class);
            assertThat(p.getStaffUserIds()).isEmpty();
            assertThat(p.isStaff(A)).isFalse();
            assertThat(p.getAppealEmail()).isEqualTo("hello@imin.wtf");
        });
        // The shipped yaml with the env vars unset, then stubbed to empty.
        shippedYaml().run(ctx -> assertOff(ctx.getBean(ModerationProperties.class)));
        shippedYaml().withPropertyValues("IMIN_MODERATION_STAFF_USER_IDS=", "IMIN_MODERATION_APPEAL_EMAIL=")
                .run(ctx -> assertOff(ctx.getBean(ModerationProperties.class)));

        // A trailing comma or a blank element is dropped, never an empty-string id.
        runner.withPropertyValues("imin.moderation.staff-user-ids=" + A + ", ," + B + ",")
                .run(ctx -> {
                    ModerationProperties p = ctx.getBean(ModerationProperties.class);
                    assertThat(p.getStaffUserIds()).isEqualTo(Set.of(A, B));
                    assertThat(p.isStaff(A)).isTrue();
                    assertThat(p.isStaff(null)).isFalse();
                });

        runner.withPropertyValues("imin.moderation.staff-user-ids=" + A + ",not-a-uuid")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    BindException bind = findCause(ctx.getStartupFailure(), BindException.class);
                    assertThat(bind).isNotNull();
                    assertThat(bind.getMessage()).contains("imin.moderation.staff-user-ids");
                });
    }

    private static void assertOff(ModerationProperties p) {
        assertThat(p.getStaffUserIds()).isEmpty();
        assertThat(p.isStaff(A)).isFalse();
        assertThat(p.getAppealEmail()).isEqualTo("hello@imin.wtf");
    }

    /** The shipped main application.yaml; OS env and system properties removed so a local value cannot leak in. */
    private ApplicationContextRunner shippedYaml() {
        return runner.withInitializer(ctx -> {
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
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }
}
