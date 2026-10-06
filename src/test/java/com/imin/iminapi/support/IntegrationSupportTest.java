package com.imin.iminapi.support;

import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the helpers @IminIntegrationTest resets after every test. */
class IntegrationSupportTest {

    @ConfigurationProperties(prefix = "fixture")
    static class FixtureProps {
        private String clientId = "client";
        private String nativeAudience = "";
        private Inner inner = new Inner();

        // Same shape as OAuthProperties.Google: the getter derives a fallback from another field.
        public String getNativeAudience() { return nativeAudience.isBlank() ? clientId : nativeAudience; }
        public void setNativeAudience(String v) { this.nativeAudience = v; }
        public Inner getInner() { return inner; }
        public void setInner(Inner inner) { this.inner = inner; }

        static class Inner {
            private int value = 1;
            public int getValue() { return value; }
            public void setValue(int value) { this.value = value; }
        }
    }

    static class NotProperties {
        private String value = "x";
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @Nested
    class PropertyFlipsTest {
        private final PropertyFlips flips = new PropertyFlips();

        @Test
        void restore_putsTheRawFieldBack_notTheDerivedGetterValue() {
            FixtureProps props = new FixtureProps();
            flips.set(props, "nativeAudience", "aud");
            assertThat(props.getNativeAudience()).isEqualTo("aud");

            flips.restoreAll();

            assertThat(ReflectionTestUtils.getField(props, "nativeAudience")).isEqualTo("");
            assertThat(props.getNativeAudience()).isEqualTo("client");
        }

        @Test
        void twoFlipsOfOnePath_restoreTheOriginal() {
            FixtureProps props = new FixtureProps();
            flips.set(props, "nativeAudience", "first");
            flips.set(props, "nativeAudience", "second");

            flips.restoreAll();

            assertThat(ReflectionTestUtils.getField(props, "nativeAudience")).isEqualTo("");
        }

        @Test
        void nestedPath_isFlippedAndRestored() {
            FixtureProps props = new FixtureProps();
            flips.set(props, "inner.value", 5);
            assertThat(props.getInner().getValue()).isEqualTo(5);

            flips.restoreAll();

            assertThat(props.getInner().getValue()).isEqualTo(1);
        }

        @Test
        void targetWithoutConfigurationProperties_isRejected() {
            NotProperties target = new NotProperties();
            assertThatThrownBy(() -> flips.set(target, "value", "y"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(target.getValue()).isEqualTo("x");
        }
    }

    @Nested
    class MutableClockTest {
        private final MutableClock clock = new MutableClock();

        @Test
        void followsSystemTimeByDefault() {
            Instant before = Instant.now();
            Instant read = clock.instant();
            Instant after = Instant.now();
            assertThat(read).isBetween(before, after);
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        }

        @Test
        void setInstant_pins() throws Exception {
            Instant pinned = Instant.parse("2020-01-01T00:00:00Z");
            clock.setInstant(pinned);
            Thread.sleep(5);
            assertThat(clock.instant()).isEqualTo(pinned);
            assertThat(clock.withZone(ZoneId.of("Europe/Berlin")).instant()).isEqualTo(pinned);
        }

        @Test
        void advance_movesAPinnedClock() {
            Instant pinned = Instant.parse("2020-01-01T00:00:00Z");
            clock.setInstant(pinned);
            clock.advance(Duration.ofHours(2));
            assertThat(clock.instant()).isEqualTo(pinned.plus(Duration.ofHours(2)));
        }

        @Test
        void advance_onAnUnpinnedClock_pinsAtNowPlusDuration() throws Exception {
            Instant before = Instant.now();
            clock.advance(Duration.ofDays(1));
            Instant after = Instant.now();
            Instant first = clock.instant();
            Thread.sleep(5);
            assertThat(clock.instant()).isEqualTo(first);
            assertThat(first).isBetween(before.plus(Duration.ofDays(1)), after.plus(Duration.ofDays(1)));
        }

        @Test
        void reset_followsSystemTimeAgain() {
            clock.setInstant(Instant.parse("2020-01-01T00:00:00Z"));
            clock.reset();
            Instant before = Instant.now();
            Instant read = clock.instant();
            Instant after = Instant.now();
            assertThat(read).isBetween(before, after);
        }
    }

    @Nested
    class RecordingRateLimiterTest {
        private final RecordingRateLimiter limiter = new RecordingRateLimiter();

        @Test
        void unlimitedByDefault_andRecordsEveryCall() {
            assertThatCode(() -> {
                for (int i = 0; i < 50; i++) limiter.consume("login", "k");
            }).doesNotThrowAnyException();
            assertThat(limiter.calls()).hasSize(50)
                    .allSatisfy(c -> assertThat(c).isEqualTo(new RecordingRateLimiter.Call("login", "k")));
        }

        @Test
        void limit_rejectsTheCallAfterN_perKey() {
            limiter.limit("login", 1);
            limiter.consume("login", "k");

            assertThatThrownBy(() -> limiter.consume("login", "k"))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            assertThatCode(() -> limiter.consume("login", "other")).doesNotThrowAnyException();
            assertThatCode(() -> limiter.consume("signup", "k")).doesNotThrowAnyException();
        }

        @Test
        void reset_clearsLimitsAndCalls() {
            limiter.limit("login", 0);
            assertThatThrownBy(() -> limiter.consume("login", "k")).isInstanceOf(ApiException.class);

            limiter.reset();

            assertThat(limiter.calls()).isEmpty();
            assertThatCode(() -> limiter.consume("login", "k")).doesNotThrowAnyException();
        }
    }

    @Nested
    class RecordingEmailServiceTest {

        @Test
        void recordsWhetherEachSendRanInsideATransaction() {
            RecordingEmailService email = new RecordingEmailService();
            email.send("a@example.test", "s", "<p>h</p>", "t");
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                email.send("b@example.test", "s", "<p>h</p>", "t");
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            assertThat(email.sentInTransaction(0)).isFalse();
            assertThat(email.sentInTransaction(1)).isTrue();
            email.clear();
            assertThat(email.sent()).isEmpty();
            assertThatThrownBy(() -> email.sentInTransaction(0)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class ResetListenerTest {

        @Test
        void failingReset_stillRunsTheRest_andCarriesLaterFailuresAsSuppressed() {
            IllegalStateException flipsFailure = new IllegalStateException("flips");
            IllegalStateException emailFailure = new IllegalStateException("email");
            MutableClock clock = new MutableClock();
            clock.setInstant(Instant.parse("2020-01-01T00:00:00Z"));
            RecordingRateLimiter limiter = new RecordingRateLimiter();
            limiter.consume("b", "k");
            InMemoryMediaStorage media = new InMemoryMediaStorage("https://m.invalid/");
            media.put("k", new byte[] {1}, "application/octet-stream");

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBean(PropertyFlips.class)).thenThrow(flipsFailure);
            when(ctx.getBean(MutableClock.class)).thenReturn(clock);
            when(ctx.getBean(RecordingEmailService.class)).thenThrow(emailFailure);
            when(ctx.getBean(RecordingRateLimiter.class)).thenReturn(limiter);
            when(ctx.getBean(InMemoryMediaStorage.class)).thenReturn(media);
            TestContext testContext = mock(TestContext.class);
            when(testContext.getApplicationContext()).thenReturn(ctx);

            Throwable thrown = catchThrowable(() -> new IminIntegrationResetListener().afterTestMethod(testContext));

            assertThat(thrown).isSameAs(flipsFailure);
            assertThat(thrown.getSuppressed()).containsExactly(emailFailure);
            assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
            assertThat(limiter.calls()).isEmpty();
            assertThat(media.blobs()).isEmpty();
        }
    }
}
