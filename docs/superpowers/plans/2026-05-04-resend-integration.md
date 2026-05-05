# Resend Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the Resend email API into the Spring Boot service and use it to power signup verification (4-digit code), password reset (random token link), and welcome email on verification.

**Architecture:** New `com.imin.iminapi.email` package owns all email concerns: typed `EmailProperties`, a thin `EmailService` interface (Resend SDK in prod, recording stub in tests), a classpath template renderer, and a domain-level `AccountEmailService` facade. Two new tables (`email_verification_codes`, `password_reset_tokens`) plus a `verified_at` column on `users` track flow state. `AuthService` gains four new methods (`verifyEmail`, `resendVerification`, `forgotPassword`, `resetPassword`); `signup` stops issuing sessions and `login` rejects unverified users with `403 EMAIL_NOT_VERIFIED`. All emails are sent synchronously; failure handling differs per call site (propagate vs swallow+Sentry) per the spec's sync split.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Data JPA, Flyway, `com.resend:resend-java` SDK, JUnit 5 + Mockito, AssertJ, MockMvc.

**Reference:** [`docs/superpowers/specs/2026-05-04-resend-integration-design.md`](../specs/2026-05-04-resend-integration-design.md)

---

## File Map

**New (production):**
- `src/main/java/com/imin/iminapi/email/EmailProperties.java`
- `src/main/java/com/imin/iminapi/email/ResendConfig.java`
- `src/main/java/com/imin/iminapi/email/EmailService.java` (interface)
- `src/main/java/com/imin/iminapi/email/ResendEmailService.java`
- `src/main/java/com/imin/iminapi/email/EmailTemplateRenderer.java`
- `src/main/java/com/imin/iminapi/email/AccountEmailService.java`
- `src/main/java/com/imin/iminapi/model/EmailVerificationCode.java`
- `src/main/java/com/imin/iminapi/model/PasswordResetToken.java`
- `src/main/java/com/imin/iminapi/repository/EmailVerificationCodeRepository.java`
- `src/main/java/com/imin/iminapi/repository/PasswordResetTokenRepository.java`
- `src/main/java/com/imin/iminapi/service/auth/verification/EmailVerificationService.java`
- `src/main/java/com/imin/iminapi/service/auth/PasswordResetService.java`
- `src/main/java/com/imin/iminapi/dto/auth/VerificationPendingResponse.java`
- `src/main/java/com/imin/iminapi/dto/auth/VerifyEmailRequest.java`
- `src/main/java/com/imin/iminapi/dto/auth/ResendVerificationRequest.java`
- `src/main/java/com/imin/iminapi/dto/auth/ForgotPasswordRequest.java`
- `src/main/java/com/imin/iminapi/dto/auth/ResetPasswordRequest.java`
- `src/main/resources/email-templates/verification-code.{html,txt}`
- `src/main/resources/email-templates/welcome.{html,txt}`
- `src/main/resources/email-templates/password-reset.{html,txt}`
- `src/main/resources/email-templates/password-changed.{html,txt}`
- `src/main/resources/db/migration/V13__email_verification_and_password_reset.sql`

**New (tests):**
- `src/test/java/com/imin/iminapi/email/RecordingEmailService.java`
- `src/test/java/com/imin/iminapi/email/EmailServiceTestConfig.java`
- `src/test/java/com/imin/iminapi/email/EmailTemplateRendererTest.java`
- `src/test/java/com/imin/iminapi/email/AccountEmailServiceTest.java`
- `src/test/java/com/imin/iminapi/service/auth/verification/EmailVerificationServiceTest.java`
- `src/test/java/com/imin/iminapi/service/auth/PasswordResetServiceTest.java`

**Modified:**
- `pom.xml` — add `com.resend:resend-java`
- `src/main/resources/application.yaml` — add `imin.email.*` config block
- `src/test/resources/application.yaml` — add test email config (no API key)
- `src/main/java/com/imin/iminapi/model/User.java` — add `verifiedAt` field
- `src/main/java/com/imin/iminapi/security/ErrorCode.java` — add `EMAIL_NOT_VERIFIED`, `INVALID_CODE`, `INVALID_TOKEN`
- `src/main/java/com/imin/iminapi/repository/AuthSessionRepository.java` — add bulk-revoke method
- `src/main/java/com/imin/iminapi/service/auth/AuthService.java` — change `signup`, change `login`, add 4 methods
- `src/main/java/com/imin/iminapi/controller/auth/AuthController.java` — add 4 endpoints, change `signup` return type
- `src/main/java/com/imin/iminapi/config/SecurityConfig.java` — add 4 endpoints to `permitAll`
- `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java` — extend tests
- `src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java` — extend tests
- `CLAUDE.md` — document new env vars

---

## Notes for the implementer

- **Run tests with `./mvnw test`.** Single class: `./mvnw test -Dtest=ClassName`. Single method: `./mvnw test -Dtest=ClassName#method`.
- **Existing patterns to follow.** `AuthServiceTest` uses plain Mockito mocks via constructor (no Spring context). `AuthControllerTest` uses `@SpringBootTest` + `@AutoConfigureMockMvc` + `@MockitoBean AuthService`. Mirror these.
- **`User.setEmail(...)` already lowercases into `email_lower`** (see `User.java:49-52`). Don't set `emailLower` manually.
- **Migrations use H2 in tests via PG-compat mode.** All migration SQL must work in both PostgreSQL and H2 PG-mode. Use `TIMESTAMP` (no TZ), `CURRENT_TIMESTAMP`, `INTEGER` (not `INT`).
- **`TokenService` (in `security/` package) already does exactly what password-reset tokens need:** 32-byte SecureRandom + sha256 hex via `IssuedToken(token, tokenHash)`. Reuse it; don't reinvent.
- **Resend SDK API surface** (`com.resend:resend-java`, latest 4.x as of 2026-04):
  ```java
  Resend resend = new Resend(apiKey);
  CreateEmailOptions options = CreateEmailOptions.builder()
      .from("Name <addr@domain>").to(to).subject(subject)
      .html(html).text(text).replyTo(replyTo).build();
  CreateEmailResponse resp = resend.emails().send(options);
  ```
  Throws `com.resend.core.exception.ResendException`. If the actual SDK class names differ slightly when you add the dep, adapt by reading the SDK's published Javadoc — the shape is stable.

---

## Task 1: Add Resend dependency + email config plumbing

**Goal:** SDK on classpath, `EmailProperties` bean reading from env, `Resend` client bean ready to inject. No business logic yet.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application.yaml`
- Create: `src/main/java/com/imin/iminapi/email/EmailProperties.java`
- Create: `src/main/java/com/imin/iminapi/email/ResendConfig.java`

- [ ] **Step 1.1: Add the resend-java dependency**

In `pom.xml`, add inside `<dependencies>` (after the `sentry-spring-boot-4-starter` block to keep third-party deps grouped):

```xml
<dependency>
    <groupId>com.resend</groupId>
    <artifactId>resend-java</artifactId>
    <version>4.1.0</version>
</dependency>
```

Run `./mvnw dependency:resolve -q` to confirm it downloads (no expected output on success). If 4.1.0 isn't on Maven Central anymore, use the latest 4.x — check https://central.sonatype.com/artifact/com.resend/resend-java.

- [ ] **Step 1.2: Create `EmailProperties`**

Create `src/main/java/com/imin/iminapi/email/EmailProperties.java`:

```java
package com.imin.iminapi.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imin.email")
public class EmailProperties {
    private String apiKey = "";
    private String fromAddress = "";
    private String fromName = "";
    private String replyTo = "";
    private String appBaseUrl = "http://localhost:3000";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public String getAppBaseUrl() { return appBaseUrl; }
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }

    /** Convenience: "Name <addr@example.com>" or just "addr@example.com" if name blank. */
    public String fromHeader() {
        if (fromName == null || fromName.isBlank()) return fromAddress;
        return fromName + " <" + fromAddress + ">";
    }
}
```

- [ ] **Step 1.3: Create `ResendConfig`**

Create `src/main/java/com/imin/iminapi/email/ResendConfig.java`:

```java
package com.imin.iminapi.email;

import com.resend.Resend;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class ResendConfig {

    @Bean
    public Resend resendClient(EmailProperties props) {
        // SDK accepts an empty key without throwing at construction; calls fail at send time
        // if the key is not set. Test profile overrides EmailService with a recording bean,
        // so this client is never actually invoked in tests.
        return new Resend(props.getApiKey());
    }
}
```

- [ ] **Step 1.4: Add config block to `application.yaml`**

In `src/main/resources/application.yaml`, add under the existing `imin:` block (alongside `cors`, `api`, `auth`, etc.):

```yaml
  email:
    api-key: ${RESEND_API_KEY:}
    from-address: ${IMIN_EMAIL_FROM_ADDRESS:noreply@imin.local}
    from-name: ${IMIN_EMAIL_FROM_NAME:imin}
    reply-to: ${IMIN_EMAIL_REPLY_TO:}
    app-base-url: ${IMIN_APP_BASE_URL:http://localhost:3000}
```

- [ ] **Step 1.5: Add test config to `src/test/resources/application.yaml`**

In `src/test/resources/application.yaml`, under the existing `imin:` block, add:

```yaml
  email:
    api-key: test-resend-key
    from-address: noreply@imin.test
    from-name: imin-test
    reply-to: ""
    app-base-url: http://localhost:3000
```

- [ ] **Step 1.6: Run the build to confirm wiring**

Run: `./mvnw test -Dtest=AuthServiceTest -q`
Expected: existing tests still pass (we haven't broken anything yet).

- [ ] **Step 1.7: Commit**

```bash
git add pom.xml src/main/resources/application.yaml src/test/resources/application.yaml \
        src/main/java/com/imin/iminapi/email/EmailProperties.java \
        src/main/java/com/imin/iminapi/email/ResendConfig.java
git commit -m "Add Resend SDK dep and email config plumbing"
```

---

## Task 2: `EmailService` interface + recording test stub + `ResendEmailService`

**Goal:** A single `send(to, subject, html, text)` seam. Production impl wraps the Resend SDK; test impl records calls in memory and is wired as `@Primary` in the test profile so no test ever hits the network.

**Files:**
- Create: `src/main/java/com/imin/iminapi/email/EmailService.java`
- Create: `src/main/java/com/imin/iminapi/email/ResendEmailService.java`
- Create: `src/test/java/com/imin/iminapi/email/RecordingEmailService.java`
- Create: `src/test/java/com/imin/iminapi/email/EmailServiceTestConfig.java`

- [ ] **Step 2.1: Create the `EmailService` interface**

Create `src/main/java/com/imin/iminapi/email/EmailService.java`:

```java
package com.imin.iminapi.email;

public interface EmailService {
    /**
     * Send an email synchronously. Throws ApiException on failure.
     * Callers decide whether to propagate or swallow per the spec's sync split.
     */
    void send(String to, String subject, String html, String text);
}
```

- [ ] **Step 2.2: Create `ResendEmailService`**

Create `src/main/java/com/imin/iminapi/email/ResendEmailService.java`:

```java
package com.imin.iminapi.email;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResendEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private final Resend resend;
    private final EmailProperties props;

    public ResendEmailService(Resend resend, EmailProperties props) {
        this.resend = resend;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String html, String text) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.error("RESEND_API_KEY not configured; cannot send email to {}", to);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                    "Email service not configured");
        }
        CreateEmailOptions.Builder b = CreateEmailOptions.builder()
                .from(props.fromHeader())
                .to(to)
                .subject(subject)
                .html(html)
                .text(text);
        if (props.getReplyTo() != null && !props.getReplyTo().isBlank()) {
            b.replyTo(props.getReplyTo());
        }
        try {
            resend.emails().send(b.build());
        } catch (ResendException e) {
            log.error("Resend API call failed for {}: {}", to, e.getMessage(), e);
            // 4xx vs 5xx isn't always cleanly distinguishable from the SDK exception;
            // we map to UPSTREAM_UNAVAILABLE so callers/Sentry can react.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Email service unavailable");
        }
    }
}
```

- [ ] **Step 2.3: Create `RecordingEmailService` (test-only)**

Create `src/test/java/com/imin/iminapi/email/RecordingEmailService.java`:

```java
package com.imin.iminapi.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordingEmailService implements EmailService {
    public record SentEmail(String to, String subject, String html, String text) {}

    private final List<SentEmail> sent = new ArrayList<>();
    private RuntimeException nextFailure;

    @Override
    public synchronized void send(String to, String subject, String html, String text) {
        if (nextFailure != null) {
            RuntimeException toThrow = nextFailure;
            nextFailure = null;
            throw toThrow;
        }
        sent.add(new SentEmail(to, subject, html, text));
    }

    public synchronized List<SentEmail> sent() { return Collections.unmodifiableList(new ArrayList<>(sent)); }
    public synchronized SentEmail lastSent() { return sent.isEmpty() ? null : sent.get(sent.size() - 1); }
    public synchronized void clear() { sent.clear(); nextFailure = null; }
    public synchronized void failNextSendWith(RuntimeException ex) { this.nextFailure = ex; }
}
```

- [ ] **Step 2.4: Create `EmailServiceTestConfig` to register the recorder as `@Primary`**

Create `src/test/java/com/imin/iminapi/email/EmailServiceTestConfig.java`:

```java
package com.imin.iminapi.email;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class EmailServiceTestConfig {

    @Bean
    @Primary
    public EmailService recordingEmailService() {
        return new RecordingEmailService();
    }
}
```

Tests that need to assert email behaviour will `@Import(EmailServiceTestConfig.class)` and `@Autowired EmailService emailService` (cast to `RecordingEmailService` when introspecting).

- [ ] **Step 2.5: Run a quick compile check**

Run: `./mvnw test-compile -q`
Expected: no errors.

- [ ] **Step 2.6: Commit**

```bash
git add src/main/java/com/imin/iminapi/email/EmailService.java \
        src/main/java/com/imin/iminapi/email/ResendEmailService.java \
        src/test/java/com/imin/iminapi/email/RecordingEmailService.java \
        src/test/java/com/imin/iminapi/email/EmailServiceTestConfig.java
git commit -m "Add EmailService interface, Resend impl, and recording test stub"
```

---

## Task 3: `EmailTemplateRenderer` + tests + template files

**Goal:** Load `{name}.html` and `{name}.txt` from `classpath:email-templates/` and substitute `{{key}}` placeholders. Missing key → `IllegalStateException` (loud failure).

**Files:**
- Create: `src/main/java/com/imin/iminapi/email/EmailTemplateRenderer.java`
- Create: `src/main/resources/email-templates/verification-code.html`
- Create: `src/main/resources/email-templates/verification-code.txt`
- Create: `src/main/resources/email-templates/welcome.html`
- Create: `src/main/resources/email-templates/welcome.txt`
- Create: `src/main/resources/email-templates/password-reset.html`
- Create: `src/main/resources/email-templates/password-reset.txt`
- Create: `src/main/resources/email-templates/password-changed.html`
- Create: `src/main/resources/email-templates/password-changed.txt`
- Test: `src/test/java/com/imin/iminapi/email/EmailTemplateRendererTest.java`

- [ ] **Step 3.1: Write the failing test**

Create `src/test/java/com/imin/iminapi/email/EmailTemplateRendererTest.java`:

```java
package com.imin.iminapi.email;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateRendererTest {

    EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void renders_html_and_text_with_substitutions() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "verification-code",
                Map.of("code", "1234", "expiresInMinutes", "10"));
        assertThat(r.html()).contains("1234").contains("10");
        assertThat(r.text()).contains("1234").contains("10");
        assertThat(r.html()).doesNotContain("{{");
        assertThat(r.text()).doesNotContain("{{");
    }

    @Test
    void substitutes_repeated_placeholder_occurrences() {
        EmailTemplateRenderer.Rendered r = renderer.render(
                "welcome",
                Map.of("name", "Ada", "appBaseUrl", "https://app.imin"));
        assertThat(r.html()).doesNotContain("{{");
        assertThat(r.text()).doesNotContain("{{");
    }

    @Test
    void throws_when_template_has_unfilled_placeholder() {
        assertThatThrownBy(() -> renderer.render("verification-code", Map.of("code", "1234")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiresInMinutes");
    }

    @Test
    void throws_when_template_does_not_exist() {
        assertThatThrownBy(() -> renderer.render("nonexistent", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent");
    }
}
```

- [ ] **Step 3.2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EmailTemplateRendererTest -q`
Expected: FAIL — `EmailTemplateRenderer` doesn't exist.

- [ ] **Step 3.3: Create `EmailTemplateRenderer`**

Create `src/main/java/com/imin/iminapi/email/EmailTemplateRenderer.java`:

```java
package com.imin.iminapi.email;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EmailTemplateRenderer {

    public record Rendered(String html, String text) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

    public Rendered render(String templateName, Map<String, String> values) {
        String html = renderOne(templateName, "html", values);
        String text = renderOne(templateName, "txt", values);
        return new Rendered(html, text);
    }

    private String renderOne(String templateName, String ext, Map<String, String> values) {
        String resource = "email-templates/" + templateName + "." + ext;
        String raw = readClasspath(resource);
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new IllegalStateException(
                        "Missing value for placeholder '" + key + "' in template " + resource);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String readClasspath(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Email template not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template: " + resource, e);
        }
    }
}
```

- [ ] **Step 3.4: Create the four template pairs (HTML + text)**

Create `src/main/resources/email-templates/verification-code.html`:

```html
<!doctype html>
<html lang="en">
<body style="font-family:Arial,Helvetica,sans-serif;background:#f5f6fa;padding:32px;color:#222;">
  <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;">
    <h2 style="margin:0 0 16px 0;">Verify your email</h2>
    <p>Use this code to finish creating your imin account:</p>
    <div style="font-size:32px;font-weight:700;letter-spacing:8px;text-align:center;background:#f0f1f5;padding:16px;border-radius:6px;margin:24px 0;">{{code}}</div>
    <p style="color:#666;font-size:14px;">This code expires in {{expiresInMinutes}} minutes. If you didn't request it, you can ignore this email.</p>
  </div>
</body>
</html>
```

Create `src/main/resources/email-templates/verification-code.txt`:

```
Verify your email

Use this code to finish creating your imin account:

{{code}}

This code expires in {{expiresInMinutes}} minutes. If you didn't request it, you can ignore this email.
```

Create `src/main/resources/email-templates/welcome.html`:

```html
<!doctype html>
<html lang="en">
<body style="font-family:Arial,Helvetica,sans-serif;background:#f5f6fa;padding:32px;color:#222;">
  <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;">
    <h2 style="margin:0 0 16px 0;">Welcome to imin{{name}}</h2>
    <p>Your account is ready. You can sign in any time at <a href="{{appBaseUrl}}">{{appBaseUrl}}</a>.</p>
    <p style="color:#666;font-size:14px;">Glad to have you on board.</p>
  </div>
</body>
</html>
```

Create `src/main/resources/email-templates/welcome.txt`:

```
Welcome to imin{{name}}

Your account is ready. You can sign in any time at {{appBaseUrl}}.

Glad to have you on board.
```

Create `src/main/resources/email-templates/password-reset.html`:

```html
<!doctype html>
<html lang="en">
<body style="font-family:Arial,Helvetica,sans-serif;background:#f5f6fa;padding:32px;color:#222;">
  <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;">
    <h2 style="margin:0 0 16px 0;">Reset your password</h2>
    <p>Click the link below to set a new password:</p>
    <p style="margin:24px 0;"><a href="{{resetUrl}}" style="background:#2b6cb0;color:#fff;padding:12px 20px;text-decoration:none;border-radius:6px;display:inline-block;">Reset password</a></p>
    <p style="color:#666;font-size:14px;">This link expires in {{expiresInMinutes}} minutes. If you didn't request a reset, you can ignore this email — your password won't change.</p>
    <p style="color:#666;font-size:12px;word-break:break-all;">{{resetUrl}}</p>
  </div>
</body>
</html>
```

Create `src/main/resources/email-templates/password-reset.txt`:

```
Reset your password

Click the link below to set a new password:

{{resetUrl}}

This link expires in {{expiresInMinutes}} minutes. If you didn't request a reset, you can ignore this email — your password won't change.
```

Create `src/main/resources/email-templates/password-changed.html`:

```html
<!doctype html>
<html lang="en">
<body style="font-family:Arial,Helvetica,sans-serif;background:#f5f6fa;padding:32px;color:#222;">
  <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;">
    <h2 style="margin:0 0 16px 0;">Your password was changed</h2>
    <p>Your imin account password was just updated. If this was you, no further action is needed.</p>
    <p>If you didn't do this, please contact support and reset your password again at <a href="{{appBaseUrl}}">{{appBaseUrl}}</a>.</p>
  </div>
</body>
</html>
```

Create `src/main/resources/email-templates/password-changed.txt`:

```
Your password was changed

Your imin account password was just updated. If this was you, no further action is needed.

If you didn't do this, please contact support and reset your password again at {{appBaseUrl}}.
```

- [ ] **Step 3.5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=EmailTemplateRendererTest -q`
Expected: PASS (4/4).

- [ ] **Step 3.6: Commit**

```bash
git add src/main/java/com/imin/iminapi/email/EmailTemplateRenderer.java \
        src/main/resources/email-templates/ \
        src/test/java/com/imin/iminapi/email/EmailTemplateRendererTest.java
git commit -m "Add EmailTemplateRenderer with classpath templates"
```

---

## Task 4: `AccountEmailService` facade + tests

**Goal:** Domain-level methods that combine the renderer + `EmailService` for each of the four email types. Subjects are constants on this class.

**Files:**
- Create: `src/main/java/com/imin/iminapi/email/AccountEmailService.java`
- Test: `src/test/java/com/imin/iminapi/email/AccountEmailServiceTest.java`

- [ ] **Step 4.1: Write the failing test**

Create `src/test/java/com/imin/iminapi/email/AccountEmailServiceTest.java`:

```java
package com.imin.iminapi.email;

import com.imin.iminapi.model.User;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountEmailServiceTest {

    EmailTemplateRenderer renderer = new EmailTemplateRenderer();
    RecordingEmailService email = new RecordingEmailService();
    AccountEmailService sut = new AccountEmailService(email, renderer);

    private User userWith(String addr, String name) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(addr);
        u.setName(name == null ? "" : name);
        return u;
    }

    @Test
    void sends_verification_code_email() {
        sut.sendVerificationCode(userWith("ada@example.com", "Ada"), "1234", 10);

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Your verification code");
        assertThat(s.html()).contains("1234").contains("10");
        assertThat(s.text()).contains("1234").contains("10");
    }

    @Test
    void sends_welcome_email_with_name_when_present() {
        sut.sendWelcome(userWith("ada@example.com", "Ada"));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Welcome to imin");
        assertThat(s.html()).contains("Ada");
    }

    @Test
    void sends_welcome_email_without_name_when_blank() {
        sut.sendWelcome(userWith("ada@example.com", ""));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.html()).doesNotContain("{{");
        // Template is "Welcome to imin{{name}}"; name empty becomes "Welcome to imin"
        assertThat(s.html()).contains("Welcome to imin");
    }

    @Test
    void sends_password_reset_email() {
        sut.sendPasswordReset(
                userWith("ada@example.com", "Ada"),
                "https://app.imin/reset-password?token=abc123",
                30);

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Reset your password");
        assertThat(s.html()).contains("https://app.imin/reset-password?token=abc123").contains("30");
        assertThat(s.text()).contains("https://app.imin/reset-password?token=abc123");
    }

    @Test
    void sends_password_changed_notification() {
        sut.sendPasswordChangedNotification(userWith("ada@example.com", "Ada"));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Your password was changed");
        assertThat(s.html()).doesNotContain("{{");
    }
}
```

- [ ] **Step 4.2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=AccountEmailServiceTest -q`
Expected: FAIL — `AccountEmailService` doesn't exist.

- [ ] **Step 4.3: Create `AccountEmailService`**

Create `src/main/java/com/imin/iminapi/email/AccountEmailService.java`:

```java
package com.imin.iminapi.email;

import com.imin.iminapi.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AccountEmailService {

    private static final String SUBJECT_VERIFICATION = "Your verification code";
    private static final String SUBJECT_WELCOME = "Welcome to imin";
    private static final String SUBJECT_PASSWORD_RESET = "Reset your password";
    private static final String SUBJECT_PASSWORD_CHANGED = "Your password was changed";

    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private String appBaseUrl = "http://localhost:3000";

    public AccountEmailService(EmailService email, EmailTemplateRenderer renderer) {
        this.email = email;
        this.renderer = renderer;
    }

    @Value("${imin.email.app-base-url:http://localhost:3000}")
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }

    public void sendVerificationCode(User user, String code, int expiresInMinutes) {
        Map<String, String> vars = Map.of(
                "code", code,
                "expiresInMinutes", String.valueOf(expiresInMinutes));
        EmailTemplateRenderer.Rendered r = renderer.render("verification-code", vars);
        email.send(user.getEmail(), SUBJECT_VERIFICATION, r.html(), r.text());
    }

    public void sendWelcome(User user) {
        // Template reads "Welcome to imin{{name}}" — name is rendered with leading space when present.
        String name = user.getName();
        String namePart = (name == null || name.isBlank()) ? "" : ", " + name;
        Map<String, String> vars = Map.of(
                "name", namePart,
                "appBaseUrl", appBaseUrl);
        EmailTemplateRenderer.Rendered r = renderer.render("welcome", vars);
        email.send(user.getEmail(), SUBJECT_WELCOME, r.html(), r.text());
    }

    public void sendPasswordReset(User user, String resetUrl, int expiresInMinutes) {
        Map<String, String> vars = Map.of(
                "resetUrl", resetUrl,
                "expiresInMinutes", String.valueOf(expiresInMinutes));
        EmailTemplateRenderer.Rendered r = renderer.render("password-reset", vars);
        email.send(user.getEmail(), SUBJECT_PASSWORD_RESET, r.html(), r.text());
    }

    public void sendPasswordChangedNotification(User user) {
        Map<String, String> vars = new HashMap<>();
        vars.put("appBaseUrl", appBaseUrl);
        EmailTemplateRenderer.Rendered r = renderer.render("password-changed", vars);
        email.send(user.getEmail(), SUBJECT_PASSWORD_CHANGED, r.html(), r.text());
    }
}
```

- [ ] **Step 4.4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AccountEmailServiceTest -q`
Expected: PASS (5/5).

- [ ] **Step 4.5: Commit**

```bash
git add src/main/java/com/imin/iminapi/email/AccountEmailService.java \
        src/test/java/com/imin/iminapi/email/AccountEmailServiceTest.java
git commit -m "Add AccountEmailService facade for verification/welcome/reset emails"
```

---

## Task 5: Schema migration + `ErrorCode` additions + `User.verifiedAt` field

**Goal:** Database has new columns/tables; `User` JPA entity exposes `verifiedAt`; new error codes are defined.

**Files:**
- Create: `src/main/resources/db/migration/V13__email_verification_and_password_reset.sql`
- Modify: `src/main/java/com/imin/iminapi/model/User.java`
- Modify: `src/main/java/com/imin/iminapi/security/ErrorCode.java`

- [ ] **Step 5.1: Create the Flyway migration**

Create `src/main/resources/db/migration/V13__email_verification_and_password_reset.sql`:

```sql
-- 1. Track email verification on users
ALTER TABLE users ADD COLUMN verified_at TIMESTAMP NULL;
-- Backfill so existing users don't get locked out on deploy
UPDATE users SET verified_at = CURRENT_TIMESTAMP WHERE verified_at IS NULL;

-- 2. Email verification codes (4-digit, 10-minute expiry, single-use, max 5 attempts)
CREATE TABLE email_verification_codes (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code            CHAR(4)      NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_evc_user_active
    ON email_verification_codes(user_id)
    WHERE consumed_at IS NULL;

-- 3. Password reset tokens (32-byte random, sha256 stored, 30-minute expiry, single-use)
CREATE TABLE password_reset_tokens (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      CHAR(64)     NOT NULL UNIQUE,
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_prt_token_hash ON password_reset_tokens(token_hash);
```

- [ ] **Step 5.2: Add `verifiedAt` to `User`**

In `src/main/java/com/imin/iminapi/model/User.java`, add the field after `lastActiveAt` (around line 47):

```java
    @Column(name = "verified_at")
    private Instant verifiedAt;
```

And update `truncateTimestamps()` to include the new field — replace the existing method body:

```java
    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        if (lastActiveAt != null) lastActiveAt = lastActiveAt.truncatedTo(ChronoUnit.MICROS);
        if (verifiedAt != null) verifiedAt = verifiedAt.truncatedTo(ChronoUnit.MICROS);
    }
```

(Lombok's `@Getter`/`@Setter` already cover the accessors.)

- [ ] **Step 5.3: Add new `ErrorCode` entries**

In `src/main/java/com/imin/iminapi/security/ErrorCode.java`, add to the enum:

```java
public enum ErrorCode {
    FIELD_INVALID,
    INVALID_REQUEST,
    AUTH_MISSING,
    AUTH_INVALID_CREDENTIALS,
    AUTH_TOKEN_EXPIRED,
    EMAIL_NOT_VERIFIED,
    INVALID_CODE,
    INVALID_TOKEN,
    FORBIDDEN,
    ORG_PLAN_LIMIT,
    NOT_FOUND,
    STALE_WRITE,
    INVALID_STATE,
    DUPLICATE,
    PUBLISH_VALIDATION_FAILED,
    RATE_LIMITED,
    INTERNAL,
    UPSTREAM_UNAVAILABLE
}
```

- [ ] **Step 5.4: Verify compile + existing tests still pass**

Run: `./mvnw test -Dtest=AuthServiceTest -q`
Expected: PASS (existing tests).

Run: `./mvnw test -Dtest=AuthControllerTest -q`
Expected: PASS.

(The migration runs against H2 here; if Flyway rejects the SQL the test class will fail to start — that's our smoke test for the migration.)

- [ ] **Step 5.5: Commit**

```bash
git add src/main/resources/db/migration/V13__email_verification_and_password_reset.sql \
        src/main/java/com/imin/iminapi/model/User.java \
        src/main/java/com/imin/iminapi/security/ErrorCode.java
git commit -m "Add schema for email verification + password reset, plus error codes"
```

---

## Task 6: `EmailVerificationCode` entity + repository

**Goal:** JPA mapping for the new table and a Spring Data repo for it.

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/EmailVerificationCode.java`
- Create: `src/main/java/com/imin/iminapi/repository/EmailVerificationCodeRepository.java`

- [ ] **Step 6.1: Create the entity**

Create `src/main/java/com/imin/iminapi/model/EmailVerificationCode.java`:

```java
package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "email_verification_codes")
@Getter
@Setter
public class EmailVerificationCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 4)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (consumedAt != null) consumedAt = consumedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 6.2: Create the repository**

Create `src/main/java/com/imin/iminapi/repository/EmailVerificationCodeRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    Optional<EmailVerificationCode> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("UPDATE EmailVerificationCode c SET c.consumedAt = :now " +
           "WHERE c.userId = :userId AND c.consumedAt IS NULL")
    int invalidateActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
```

- [ ] **Step 6.3: Quick compile check**

Run: `./mvnw test-compile -q`
Expected: no errors.

- [ ] **Step 6.4: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/EmailVerificationCode.java \
        src/main/java/com/imin/iminapi/repository/EmailVerificationCodeRepository.java
git commit -m "Add EmailVerificationCode entity and repository"
```

---

## Task 7: `EmailVerificationService` + tests

**Goal:** Issue 4-digit codes (invalidating any pending), verify with attempt tracking, set `users.verified_at` on success.

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/auth/verification/EmailVerificationService.java`
- Test: `src/test/java/com/imin/iminapi/service/auth/verification/EmailVerificationServiceTest.java`

- [ ] **Step 7.1: Write the failing tests**

Create `src/test/java/com/imin/iminapi/service/auth/verification/EmailVerificationServiceTest.java`:

```java
package com.imin.iminapi.service.auth.verification;

import com.imin.iminapi.model.EmailVerificationCode;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EmailVerificationCodeRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {

    EmailVerificationCodeRepository codes = mock(EmailVerificationCodeRepository.class);
    UserRepository users = mock(UserRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC);

    EmailVerificationService sut;

    @BeforeEach
    void setUp() {
        sut = new EmailVerificationService(codes, users, clock, Duration.ofMinutes(10), 5);
        when(codes.save(any(EmailVerificationCode.class))).thenAnswer(inv -> {
            EmailVerificationCode c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User newUser(String email) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setOrgId(UUID.randomUUID());
        u.setEmail(email);
        return u;
    }

    @Test
    void issueCode_invalidates_existing_active_and_returns_4_digit_code() {
        User u = newUser("ada@example.com");
        String code = sut.issueCode(u);

        assertThat(code).hasSize(4).matches("\\d{4}");
        verify(codes).invalidateActiveForUser(eq(u.getId()), any(Instant.class));
        verify(codes).save(any(EmailVerificationCode.class));
    }

    @Test
    void verify_success_sets_verifiedAt_consumes_code_returns_user() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        User result = sut.verify("ada@example.com", "1234");

        assertThat(result.getVerifiedAt()).isNotNull();
        assertThat(active.getConsumedAt()).isNotNull();
        verify(users).save(u);
    }

    @Test
    void verify_wrong_code_increments_attempts_and_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "0000"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        assertThat(active.getAttempts()).isEqualTo(1);
        assertThat(active.getConsumedAt()).isNull();
    }

    @Test
    void verify_after_max_attempts_throws_INVALID_CODE_without_incrementing() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", 5, 5); // already at max
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        assertThat(active.getAttempts()).isEqualTo(5);
    }

    @Test
    void verify_expired_code_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", -1, 0); // expired 1 min ago
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    @Test
    void verify_with_no_pending_code_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("ada@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    @Test
    void verify_with_unknown_email_throws_INVALID_CODE() {
        when(users.findByEmailLower("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("nobody@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    private EmailVerificationCode activeCode(User u, String code, long minutesUntilExpiry, int attempts) {
        EmailVerificationCode c = new EmailVerificationCode();
        c.setId(UUID.randomUUID());
        c.setUserId(u.getId());
        c.setCode(code);
        c.setExpiresAt(clock.instant().plus(Duration.ofMinutes(minutesUntilExpiry)));
        c.setAttempts(attempts);
        return c;
    }
}
```

- [ ] **Step 7.2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EmailVerificationServiceTest -q`
Expected: FAIL — `EmailVerificationService` doesn't exist.

- [ ] **Step 7.3: Create `EmailVerificationService`**

Create `src/main/java/com/imin/iminapi/service/auth/verification/EmailVerificationService.java`:

```java
package com.imin.iminapi.service.auth.verification;

import com.imin.iminapi.model.EmailVerificationCode;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EmailVerificationCodeRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class EmailVerificationService {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final int EXPIRES_IN_MINUTES = (int) CODE_TTL.toMinutes();

    private final EmailVerificationCodeRepository codes;
    private final UserRepository users;
    private final Clock clock;
    private final Duration ttl;
    private final int maxAttempts;
    private final SecureRandom rnd = new SecureRandom();

    public EmailVerificationService(EmailVerificationCodeRepository codes,
                                     UserRepository users) {
        this(codes, users, Clock.systemUTC(), CODE_TTL, MAX_ATTEMPTS);
    }

    /** Constructor used by tests for clock + parameter overrides. */
    public EmailVerificationService(EmailVerificationCodeRepository codes,
                                     UserRepository users,
                                     Clock clock,
                                     Duration ttl,
                                     int maxAttempts) {
        this.codes = codes;
        this.users = users;
        this.clock = clock;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public String issueCode(User user) {
        codes.invalidateActiveForUser(user.getId(), clock.instant());
        String code = String.format(Locale.ROOT, "%04d", rnd.nextInt(10_000));
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setUserId(user.getId());
        entity.setCode(code);
        entity.setExpiresAt(clock.instant().plus(ttl));
        codes.save(entity);
        return code;
    }

    @Transactional
    public User verify(String email, String code) {
        Optional<User> maybeUser = users.findByEmailLower(email.toLowerCase());
        if (maybeUser.isEmpty()) {
            throw invalidCode();
        }
        User user = maybeUser.get();
        Optional<EmailVerificationCode> maybeActive =
                codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId());
        if (maybeActive.isEmpty()) {
            throw invalidCode();
        }
        EmailVerificationCode active = maybeActive.get();

        if (active.getAttempts() >= maxAttempts) throw invalidCode();
        if (active.getExpiresAt().isBefore(clock.instant())) throw invalidCode();

        if (!active.getCode().equals(code)) {
            active.setAttempts(active.getAttempts() + 1);
            codes.save(active);
            throw invalidCode();
        }

        active.setConsumedAt(clock.instant());
        codes.save(active);
        user.setVerifiedAt(clock.instant());
        users.save(user);
        return user;
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CODE,
                "Invalid or expired verification code");
    }
}
```

- [ ] **Step 7.4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=EmailVerificationServiceTest -q`
Expected: PASS (7/7).

- [ ] **Step 7.5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/auth/verification/EmailVerificationService.java \
        src/test/java/com/imin/iminapi/service/auth/verification/EmailVerificationServiceTest.java
git commit -m "Add EmailVerificationService with issue/verify and attempt tracking"
```

---

## Task 8: `PasswordResetToken` entity + repository

**Goal:** JPA mapping + repo for `password_reset_tokens`.

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/PasswordResetToken.java`
- Create: `src/main/java/com/imin/iminapi/repository/PasswordResetTokenRepository.java`

- [ ] **Step 8.1: Create the entity**

Create `src/main/java/com/imin/iminapi/model/PasswordResetToken.java`:

```java
package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (consumedAt != null) consumedAt = consumedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 8.2: Create the repository**

Create `src/main/java/com/imin/iminapi/repository/PasswordResetTokenRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
```

- [ ] **Step 8.3: Quick compile check**

Run: `./mvnw test-compile -q`
Expected: no errors.

- [ ] **Step 8.4: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/PasswordResetToken.java \
        src/main/java/com/imin/iminapi/repository/PasswordResetTokenRepository.java
git commit -m "Add PasswordResetToken entity and repository"
```

---

## Task 9: `PasswordResetService` + tests

**Goal:** Issue token (returns cleartext + persists hash), consume token (validates + updates password). Reuses existing `TokenService` for the random + sha256 logic.

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/auth/PasswordResetService.java`
- Test: `src/test/java/com/imin/iminapi/service/auth/PasswordResetServiceTest.java`

- [ ] **Step 9.1: Write the failing tests**

Create `src/test/java/com/imin/iminapi/service/auth/PasswordResetServiceTest.java`:

```java
package com.imin.iminapi.service.auth;

import com.imin.iminapi.model.PasswordResetToken;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.PasswordResetTokenRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    UserRepository users = mock(UserRepository.class);
    TokenService tokenSvc = new TokenService();
    PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    Clock clock = Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC);

    PasswordResetService sut;

    @BeforeEach
    void setUp() {
        sut = new PasswordResetService(tokens, users, tokenSvc, hasher, clock, Duration.ofMinutes(30));
        when(tokens.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User newUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setOrgId(UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setPasswordHash(hasher.hash("oldpassword12"));
        return u;
    }

    @Test
    void issueToken_persists_hash_returns_cleartext() {
        User u = newUser();
        String token = sut.issueToken(u);

        assertThat(token).isNotBlank();
        verify(tokens).save(argThat(t ->
                t.getTokenHash().equals(tokenSvc.hashOf(token))
                && t.getUserId().equals(u.getId())));
    }

    @Test
    void consume_valid_token_updates_password_and_marks_consumed() {
        User u = newUser();
        String cleartext = "abc123";
        PasswordResetToken stored = active(u, cleartext, 10);
        when(tokens.findByTokenHash(tokenSvc.hashOf(cleartext))).thenReturn(Optional.of(stored));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        User result = sut.consume(cleartext, "newpassword12");

        assertThat(result.getId()).isEqualTo(u.getId());
        assertThat(stored.getConsumedAt()).isNotNull();
        assertThat(hasher.verify("newpassword12", u.getPasswordHash())).isTrue();
    }

    @Test
    void consume_unknown_token_throws_INVALID_TOKEN() {
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sut.consume("garbage", "newpassword12"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_TOKEN);
    }

    @Test
    void consume_expired_token_throws_INVALID_TOKEN() {
        User u = newUser();
        String cleartext = "abc123";
        PasswordResetToken stored = active(u, cleartext, -1); // expired 1 min ago
        when(tokens.findByTokenHash(tokenSvc.hashOf(cleartext))).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> sut.consume(cleartext, "newpassword12"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_TOKEN);
    }

    @Test
    void consume_already_used_token_throws_INVALID_TOKEN() {
        User u = newUser();
        String cleartext = "abc123";
        PasswordResetToken stored = active(u, cleartext, 10);
        stored.setConsumedAt(clock.instant().minusSeconds(60));
        when(tokens.findByTokenHash(tokenSvc.hashOf(cleartext))).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> sut.consume(cleartext, "newpassword12"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_TOKEN);
    }

    private PasswordResetToken active(User u, String cleartext, long minutesUntilExpiry) {
        PasswordResetToken t = new PasswordResetToken();
        t.setId(UUID.randomUUID());
        t.setUserId(u.getId());
        t.setTokenHash(tokenSvc.hashOf(cleartext));
        t.setExpiresAt(clock.instant().plus(Duration.ofMinutes(minutesUntilExpiry)));
        return t;
    }
}
```

- [ ] **Step 9.2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=PasswordResetServiceTest -q`
Expected: FAIL — `PasswordResetService` doesn't exist.

- [ ] **Step 9.3: Create `PasswordResetService`**

Create `src/main/java/com/imin/iminapi/service/auth/PasswordResetService.java`:

```java
package com.imin.iminapi.service.auth;

import com.imin.iminapi.model.PasswordResetToken;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.PasswordResetTokenRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Service
public class PasswordResetService {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final int EXPIRES_IN_MINUTES = (int) TOKEN_TTL.toMinutes();

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final TokenService tokenSvc;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final Duration ttl;

    public PasswordResetService(PasswordResetTokenRepository tokens,
                                 UserRepository users,
                                 TokenService tokenSvc,
                                 PasswordHasher hasher) {
        this(tokens, users, tokenSvc, hasher, Clock.systemUTC(), TOKEN_TTL);
    }

    /** Constructor used by tests. */
    public PasswordResetService(PasswordResetTokenRepository tokens,
                                 UserRepository users,
                                 TokenService tokenSvc,
                                 PasswordHasher hasher,
                                 Clock clock,
                                 Duration ttl) {
        this.tokens = tokens;
        this.users = users;
        this.tokenSvc = tokenSvc;
        this.hasher = hasher;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public String issueToken(User user) {
        TokenService.IssuedToken issued = tokenSvc.issue();
        PasswordResetToken entity = new PasswordResetToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(issued.tokenHash());
        entity.setExpiresAt(clock.instant().plus(ttl));
        tokens.save(entity);
        return issued.token();
    }

    @Transactional
    public User consume(String cleartext, String newPassword) {
        String hash = tokenSvc.hashOf(cleartext);
        Optional<PasswordResetToken> maybe = tokens.findByTokenHash(hash);
        if (maybe.isEmpty()) throw invalidToken();
        PasswordResetToken token = maybe.get();
        if (token.getConsumedAt() != null) throw invalidToken();
        if (token.getExpiresAt().isBefore(clock.instant())) throw invalidToken();

        User user = users.findById(token.getUserId()).orElseThrow(this::invalidToken);
        user.setPasswordHash(hasher.hash(newPassword));
        users.save(user);

        token.setConsumedAt(clock.instant());
        tokens.save(token);
        return user;
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN,
                "Invalid or expired reset token");
    }
}
```

- [ ] **Step 9.4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=PasswordResetServiceTest -q`
Expected: PASS (5/5).

- [ ] **Step 9.5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/auth/PasswordResetService.java \
        src/test/java/com/imin/iminapi/service/auth/PasswordResetServiceTest.java
git commit -m "Add PasswordResetService for issuing/consuming reset tokens"
```

---

## Task 10: `AuthSessionRepository.revokeAllForUser` + new auth DTOs

**Goal:** Bulk session revocation for password-reset, plus the four new request/response DTOs.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/repository/AuthSessionRepository.java`
- Create: `src/main/java/com/imin/iminapi/dto/auth/VerificationPendingResponse.java`
- Create: `src/main/java/com/imin/iminapi/dto/auth/VerifyEmailRequest.java`
- Create: `src/main/java/com/imin/iminapi/dto/auth/ResendVerificationRequest.java`
- Create: `src/main/java/com/imin/iminapi/dto/auth/ForgotPasswordRequest.java`
- Create: `src/main/java/com/imin/iminapi/dto/auth/ResetPasswordRequest.java`

- [ ] **Step 10.1: Add bulk-revoke method to `AuthSessionRepository`**

Replace contents of `src/main/java/com/imin/iminapi/repository/AuthSessionRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    Optional<AuthSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :now " +
           "WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
```

- [ ] **Step 10.2: Create the response DTO**

Create `src/main/java/com/imin/iminapi/dto/auth/VerificationPendingResponse.java`:

```java
package com.imin.iminapi.dto.auth;

public record VerificationPendingResponse(String message, String email) {
    public static VerificationPendingResponse forEmail(String email) {
        return new VerificationPendingResponse("Verification email sent", email);
    }
}
```

- [ ] **Step 10.3: Create the request DTOs**

Create `src/main/java/com/imin/iminapi/dto/auth/VerifyEmailRequest.java`:

```java
package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "\\d{4}", message = "must be 4 digits") String code) {}
```

Create `src/main/java/com/imin/iminapi/dto/auth/ResendVerificationRequest.java`:

```java
package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationRequest(@NotBlank @Email String email) {}
```

Create `src/main/java/com/imin/iminapi/dto/auth/ForgotPasswordRequest.java`:

```java
package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(@NotBlank @Email String email) {}
```

Create `src/main/java/com/imin/iminapi/dto/auth/ResetPasswordRequest.java`:

```java
package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank String newPassword) {
    /** Mirrors SignupRequest: ≥10 chars, ≥1 letter, ≥1 digit. */
    @AssertTrue(message = "Password must be at least 10 characters and contain a letter and a digit")
    public boolean isPasswordPolicyValid() {
        if (newPassword == null || newPassword.length() < 10) return false;
        boolean hasLetter = false, hasDigit = false;
        for (int i = 0; i < newPassword.length(); i++) {
            char c = newPassword.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }
}
```

- [ ] **Step 10.4: Compile check**

Run: `./mvnw test-compile -q`
Expected: no errors.

- [ ] **Step 10.5: Commit**

```bash
git add src/main/java/com/imin/iminapi/repository/AuthSessionRepository.java \
        src/main/java/com/imin/iminapi/dto/auth/VerificationPendingResponse.java \
        src/main/java/com/imin/iminapi/dto/auth/VerifyEmailRequest.java \
        src/main/java/com/imin/iminapi/dto/auth/ResendVerificationRequest.java \
        src/main/java/com/imin/iminapi/dto/auth/ForgotPasswordRequest.java \
        src/main/java/com/imin/iminapi/dto/auth/ResetPasswordRequest.java
git commit -m "Add session bulk-revoke method and auth email DTOs"
```

---

## Task 11: Wire `AuthService.signup` to issue code + send email (no session)

**Goal:** `signup` returns `VerificationPendingResponse`, persists user with `verifiedAt = null`, issues code, sends verification email synchronously, and propagates Resend failures. The `AuthController` is not yet updated — it will fail to compile until Task 14, which is fine (we hold the whole change set together via test-only `AuthServiceTest` for now).

Wait — `AuthController.signup` returns `AuthResponse` and calls `authService.signup(req)`. If we change `signup`'s return type, `AuthController` won't compile. So we must change them together.

**Adjusted approach:** Tasks 11–14 collectively form the auth-service rewrite. We'll keep them in order but merge controller changes into Task 14 so each task's commit compiles.

To keep Task 11 standalone: change `signup`'s return type and update `AuthController.signup` together. The test class for `AuthService.signup` updates with the new return type.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/auth/AuthService.java`
- Modify: `src/main/java/com/imin/iminapi/controller/auth/AuthController.java`
- Modify: `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java`
- Modify: `src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java`

- [ ] **Step 11.1: Update existing `AuthServiceTest.signup_*` tests**

In `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java`:

(a) Add fields and constructor change at the top of the class. Replace the `tokens = new TokenService();` line and constructor with:

```java
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    UserRepository users = mock(UserRepository.class);
    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    TokenService tokens = new TokenService();
    com.imin.iminapi.service.auth.verification.EmailVerificationService verificationSvc =
            mock(com.imin.iminapi.service.auth.verification.EmailVerificationService.class);
    com.imin.iminapi.service.auth.PasswordResetService passwordResetSvc =
            mock(com.imin.iminapi.service.auth.PasswordResetService.class);
    com.imin.iminapi.email.AccountEmailService accountEmail =
            mock(com.imin.iminapi.email.AccountEmailService.class);

    AuthService sut = new AuthService(orgs, users, sessions, hasher, tokens,
            verificationSvc, passwordResetSvc, accountEmail,
            "http://localhost:3000", Duration.ofDays(30));
```

(b) Replace the `signup_creates_org_and_owner_then_issues_session` test with a new behaviour matching the spec:

```java
    @Test
    void signup_creates_org_and_owner_issues_code_sends_email_returns_pending() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(java.util.UUID.randomUUID()); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(java.util.UUID.randomUUID()); return u;
        });
        when(verificationSvc.issueCode(any(User.class))).thenReturn("1234");

        com.imin.iminapi.dto.auth.VerificationPendingResponse r =
                sut.signup(new SignupRequest("ada@example.com", "lovelace12", "Ada Co", "GB"));

        assertThat(r.message()).isEqualTo("Verification email sent");
        assertThat(r.email()).isEqualTo("ada@example.com");
        verify(verificationSvc).issueCode(any(User.class));
        verify(accountEmail).sendVerificationCode(any(User.class), eq("1234"),
                eq(com.imin.iminapi.service.auth.verification.EmailVerificationService.EXPIRES_IN_MINUTES));
        verify(sessions, never()).save(any(AuthSession.class));
    }
```

Add the missing static imports at the top:

```java
import static org.mockito.ArgumentMatchers.eq;
```

(c) Delete the `avatar_initials_are_derived_from_email_local_part_when_no_name` test's session expectation — replace its body with the same shape as above (it now asserts avatar from the saved user, not from the response):

```java
    @Test
    void avatar_initials_are_derived_from_email_local_part_when_no_name() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(java.util.UUID.randomUUID()); return o;
        });
        java.util.concurrent.atomic.AtomicReference<User> savedUser = new java.util.concurrent.atomic.AtomicReference<>();
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(java.util.UUID.randomUUID());
            savedUser.set(u); return u;
        });
        when(verificationSvc.issueCode(any(User.class))).thenReturn("0001");

        sut.signup(new SignupRequest("ada@example.com", "lovelace12", "X", "GB"));

        assertThat(savedUser.get().getAvatarInitials()).isEqualTo("AD");
    }
```

(d) The `signup_with_existing_email_throws_DUPLICATE` test stays as-is — DUPLICATE precedes any email work.

- [ ] **Step 11.2: Update `AuthService` constructor + `signup` method**

In `src/main/java/com/imin/iminapi/service/auth/AuthService.java`:

(a) Add imports near the top:

```java
import com.imin.iminapi.dto.auth.VerificationPendingResponse;
import com.imin.iminapi.email.AccountEmailService;
import com.imin.iminapi.service.auth.verification.EmailVerificationService;
```

(b) Add new fields after the existing `tokens` / `sessionTtl` fields:

```java
    private final EmailVerificationService verificationSvc;
    private final PasswordResetService passwordResetSvc;
    private final AccountEmailService accountEmail;
    private final String appBaseUrl;
```

(c) Replace both constructors:

```java
    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(OrganizationRepository orgs,
                       UserRepository users,
                       AuthSessionRepository sessions,
                       PasswordHasher hasher,
                       TokenService tokens,
                       EmailVerificationService verificationSvc,
                       PasswordResetService passwordResetSvc,
                       AccountEmailService accountEmail,
                       @Value("${imin.email.app-base-url:http://localhost:3000}") String appBaseUrl,
                       @Value("${imin.auth.session-ttl-days}") long sessionTtlDays) {
        this(orgs, users, sessions, hasher, tokens, verificationSvc, passwordResetSvc, accountEmail,
                appBaseUrl, Duration.ofDays(sessionTtlDays));
    }

    /** Constructor used by tests. */
    public AuthService(OrganizationRepository orgs,
                       UserRepository users,
                       AuthSessionRepository sessions,
                       PasswordHasher hasher,
                       TokenService tokens,
                       EmailVerificationService verificationSvc,
                       PasswordResetService passwordResetSvc,
                       AccountEmailService accountEmail,
                       String appBaseUrl,
                       Duration sessionTtl) {
        this.orgs = orgs;
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.verificationSvc = verificationSvc;
        this.passwordResetSvc = passwordResetSvc;
        this.accountEmail = accountEmail;
        this.appBaseUrl = appBaseUrl;
        this.sessionTtl = sessionTtl;
    }
```

(d) Replace `signup`:

```java
    @Transactional
    public VerificationPendingResponse signup(SignupRequest req) {
        String emailLower = req.email().toLowerCase();
        if (users.existsByEmailLower(emailLower)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Email already registered", java.util.Map.of("email", "already registered"));
        }
        Organization org = new Organization();
        org.setName(req.orgName());
        org.setContactEmail(req.email());
        org.setCountry(req.country().toUpperCase());
        org.setTimezone("UTC");
        Organization savedOrg = orgs.save(org);

        User user = new User();
        user.setOrgId(savedOrg.getId());
        user.setEmail(req.email());
        user.setName("");
        user.setPasswordHash(hasher.hash(req.password()));
        user.setRole(UserRole.OWNER);
        user.setAvatarInitials(deriveInitials(req.email()));
        // verifiedAt left null until /verify-email succeeds
        User savedUser = users.save(user);

        String code = verificationSvc.issueCode(savedUser);
        // Sync, propagate failure: signup must fail loudly if the user can't receive the code.
        accountEmail.sendVerificationCode(savedUser, code, EmailVerificationService.EXPIRES_IN_MINUTES);

        return VerificationPendingResponse.forEmail(savedUser.getEmail());
    }
```

(Keep `login`, `logout`, `me`, `issueSession`, `deriveInitials` unchanged for now — they get touched in later tasks.)

- [ ] **Step 11.3: Update `AuthController.signup` return type**

In `src/main/java/com/imin/iminapi/controller/auth/AuthController.java`:

(a) Update imports:

```java
import com.imin.iminapi.dto.auth.VerificationPendingResponse;
```

(b) Change the `signup` method:

```java
    @PostMapping("/signup")
    public VerificationPendingResponse signup(@Valid @RequestBody SignupRequest req) {
        return authService.signup(req);
    }
```

- [ ] **Step 11.4: Update `AuthControllerTest.signup_returns_token_user_org`**

In `src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java`, replace the `signup_returns_token_user_org` test:

```java
    @Test
    void signup_returns_verification_pending_response() throws Exception {
        when(authService.signup(any(SignupRequest.class)))
                .thenReturn(com.imin.iminapi.dto.auth.VerificationPendingResponse.forEmail("ada@example.com"));

        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", "ada@example.com",
                                "password", "lovelace12",
                                "orgName", "Ada Co",
                                "country", "GB"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"))
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }
```

- [ ] **Step 11.5: Run the affected tests**

Run: `./mvnw test -Dtest=AuthServiceTest -q`
Expected: PASS.

Run: `./mvnw test -Dtest=AuthControllerTest -q`
Expected: PASS.

- [ ] **Step 11.6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/auth/AuthService.java \
        src/main/java/com/imin/iminapi/controller/auth/AuthController.java \
        src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java \
        src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java
git commit -m "Make signup issue verification code instead of session"
```

---

## Task 12: Hard-block login for unverified users

**Goal:** `login` returns `403 EMAIL_NOT_VERIFIED` when `user.verifiedAt == null`.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/auth/AuthService.java`
- Modify: `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java`

- [ ] **Step 12.1: Write the failing test**

In `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java`, add:

```java
    @Test
    void login_with_unverified_user_throws_EMAIL_NOT_VERIFIED() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
        stored.setVerifiedAt(null); // <-- unverified
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(stored));

        assertThatThrownBy(() -> sut.login(new com.imin.iminapi.dto.auth.LoginRequest("ada@example.com", "lovelace12")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.EMAIL_NOT_VERIFIED);
    }
```

Also update `login_with_valid_password_returns_token` and any other login test that builds a `User` to set `stored.setVerifiedAt(java.time.Instant.now());`. Specifically, edit:

```java
    @Test
    void login_with_valid_password_returns_token() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
        stored.setVerifiedAt(java.time.Instant.now()); // <-- ADDED
        // ... rest unchanged
```

(Same edit for `login_with_wrong_password_throws_AUTH_INVALID_CREDENTIALS` is unnecessary — that test fails before the verified check.)

- [ ] **Step 12.2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=AuthServiceTest#login_with_unverified_user_throws_EMAIL_NOT_VERIFIED -q`
Expected: FAIL — currently login doesn't check `verifiedAt`.

- [ ] **Step 12.3: Update `AuthService.login`**

In `src/main/java/com/imin/iminapi/service/auth/AuthService.java`, replace `login`:

```java
    @Transactional
    public AuthResponse login(LoginRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty() || maybe.get().getPasswordHash() == null
                || !hasher.verify(req.password(), maybe.get().getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
        }
        User user = maybe.get();
        if (user.getVerifiedAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.EMAIL_NOT_VERIFIED, "Email not verified");
        }
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        user.setLastActiveAt(Instant.now());
        users.save(user);
        String token = issueSession(user);
        return new AuthResponse(token, UserDto.from(user), OrganizationDto.from(org));
    }
```

- [ ] **Step 12.4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=AuthServiceTest -q`
Expected: PASS.

- [ ] **Step 12.5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/auth/AuthService.java \
        src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java
git commit -m "Hard-block login for users with verified_at NULL"
```

---

## Task 13: `AuthService.verifyEmail`, `resendVerification`, `forgotPassword`, `resetPassword`

**Goal:** Add the four new methods to `AuthService` with the sync split per spec. The controller endpoints come in Task 14.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/auth/AuthService.java`
- Modify: `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java`

- [ ] **Step 13.1: Write failing tests for the four new methods**

Append to `src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java`:

```java
    // ----- verifyEmail -----

    @Test
    void verifyEmail_marks_user_verified_issues_session_sends_welcome() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setOrgId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER);
        u.setVerifiedAt(java.time.Instant.now());

        Organization org = new Organization();
        org.setId(u.getOrgId());
        org.setName("Ada Co");
        org.setContactEmail("ada@example.com");
        org.setCountry("GB");

        when(verificationSvc.verify("ada@example.com", "1234")).thenReturn(u);
        when(orgs.findById(u.getOrgId())).thenReturn(java.util.Optional.of(org));
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        com.imin.iminapi.dto.auth.AuthResponse r = sut.verifyEmail(
                new com.imin.iminapi.dto.auth.VerifyEmailRequest("ada@example.com", "1234"));

        assertThat(r.token()).isNotBlank();
        assertThat(r.user().email()).isEqualTo("ada@example.com");
        verify(accountEmail).sendWelcome(u);
    }

    @Test
    void verifyEmail_swallows_welcome_email_failure() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setOrgId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER);
        u.setVerifiedAt(java.time.Instant.now());

        Organization org = new Organization();
        org.setId(u.getOrgId());
        org.setName("Ada Co");
        org.setContactEmail("ada@example.com");
        org.setCountry("GB");

        when(verificationSvc.verify(any(), any())).thenReturn(u);
        when(orgs.findById(u.getOrgId())).thenReturn(java.util.Optional.of(org));
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE, "down"))
                .when(accountEmail).sendWelcome(any());

        com.imin.iminapi.dto.auth.AuthResponse r = sut.verifyEmail(
                new com.imin.iminapi.dto.auth.VerifyEmailRequest("ada@example.com", "1234"));
        assertThat(r.token()).isNotBlank(); // not failed
    }

    // ----- resendVerification -----

    @Test
    void resendVerification_for_unverified_user_issues_new_code_and_sends() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setVerifiedAt(null);
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));
        when(verificationSvc.issueCode(u)).thenReturn("9999");

        sut.resendVerification(new com.imin.iminapi.dto.auth.ResendVerificationRequest("ada@example.com"));

        verify(verificationSvc).issueCode(u);
        verify(accountEmail).sendVerificationCode(eq(u), eq("9999"), anyInt());
    }

    @Test
    void resendVerification_for_verified_user_is_a_noop() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setVerifiedAt(java.time.Instant.now());
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));

        sut.resendVerification(new com.imin.iminapi.dto.auth.ResendVerificationRequest("ada@example.com"));

        verify(verificationSvc, never()).issueCode(any());
        verify(accountEmail, never()).sendVerificationCode(any(), any(), anyInt());
    }

    @Test
    void resendVerification_for_unknown_email_is_a_noop() {
        when(users.findByEmailLower(any())).thenReturn(java.util.Optional.empty());

        sut.resendVerification(new com.imin.iminapi.dto.auth.ResendVerificationRequest("nobody@example.com"));

        verify(verificationSvc, never()).issueCode(any());
    }

    // ----- forgotPassword -----

    @Test
    void forgotPassword_for_existing_user_issues_token_and_sends_email() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));
        when(passwordResetSvc.issueToken(u)).thenReturn("reset-token-abc");

        sut.forgotPassword(new com.imin.iminapi.dto.auth.ForgotPasswordRequest("ada@example.com"));

        verify(accountEmail).sendPasswordReset(eq(u),
                eq("http://localhost:3000/reset-password?token=reset-token-abc"),
                anyInt());
    }

    @Test
    void forgotPassword_for_unknown_email_is_silent_noop() {
        when(users.findByEmailLower(any())).thenReturn(java.util.Optional.empty());

        sut.forgotPassword(new com.imin.iminapi.dto.auth.ForgotPasswordRequest("nobody@example.com"));

        verify(passwordResetSvc, never()).issueToken(any());
        verify(accountEmail, never()).sendPasswordReset(any(), any(), anyInt());
    }

    @Test
    void forgotPassword_swallows_resend_failure() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));
        when(passwordResetSvc.issueToken(u)).thenReturn("tok");
        doThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE, "down"))
                .when(accountEmail).sendPasswordReset(any(), any(), anyInt());

        // Should NOT throw — anti-enumeration
        sut.forgotPassword(new com.imin.iminapi.dto.auth.ForgotPasswordRequest("ada@example.com"));
    }

    // ----- resetPassword -----

    @Test
    void resetPassword_consumes_token_revokes_sessions_sends_notification() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(passwordResetSvc.consume("token-abc", "newpassword12")).thenReturn(u);

        sut.resetPassword(new com.imin.iminapi.dto.auth.ResetPasswordRequest("token-abc", "newpassword12"));

        verify(passwordResetSvc).consume("token-abc", "newpassword12");
        verify(sessions).revokeAllForUser(eq(u.getId()), any(java.time.Instant.class));
        verify(accountEmail).sendPasswordChangedNotification(u);
    }

    @Test
    void resetPassword_swallows_notification_email_failure() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(passwordResetSvc.consume(any(), any())).thenReturn(u);
        doThrow(new RuntimeException("email down"))
                .when(accountEmail).sendPasswordChangedNotification(any());

        // Should NOT throw — notification is non-critical
        sut.resetPassword(new com.imin.iminapi.dto.auth.ResetPasswordRequest("token-abc", "newpassword12"));
    }
```

Add the missing static imports if not already present:

```java
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
```

- [ ] **Step 13.2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=AuthServiceTest -q`
Expected: compilation FAIL — methods don't exist on `AuthService`.

- [ ] **Step 13.3: Add a static logger field**

In `src/main/java/com/imin/iminapi/service/auth/AuthService.java`, add the imports near the top:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

And add a static field at the top of the class (just under `public class AuthService {`):

```java
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
```

(Avoids shadowing by the local variable name `org` for `Organization` in `verifyEmail`.)

- [ ] **Step 13.4: Add the four methods to `AuthService`**

In `src/main/java/com/imin/iminapi/service/auth/AuthService.java`, append before the closing brace (and before `private String issueSession(User user)`):

```java
    @Transactional
    public AuthResponse verifyEmail(com.imin.iminapi.dto.auth.VerifyEmailRequest req) {
        User user = verificationSvc.verify(req.email(), req.code());
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        user.setLastActiveAt(Instant.now());
        users.save(user);
        String token = issueSession(user);
        // Welcome email is non-critical — swallow failures so a Resend outage doesn't block verification.
        try {
            accountEmail.sendWelcome(user);
        } catch (RuntimeException e) {
            log.warn("Welcome email send failed for {}: {}", user.getEmail(), e.getMessage());
        }
        return new AuthResponse(token, UserDto.from(user), OrganizationDto.from(org));
    }

    @Transactional
    public void resendVerification(com.imin.iminapi.dto.auth.ResendVerificationRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty()) return;                                  // anti-enumeration
        User user = maybe.get();
        if (user.getVerifiedAt() != null) return;                     // already verified
        String code = verificationSvc.issueCode(user);
        // Sync, propagate failure: user explicitly asked for a code.
        accountEmail.sendVerificationCode(user, code, EmailVerificationService.EXPIRES_IN_MINUTES);
    }

    @Transactional
    public void forgotPassword(com.imin.iminapi.dto.auth.ForgotPasswordRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty()) return;                                  // anti-enumeration
        User user = maybe.get();
        String token = passwordResetSvc.issueToken(user);
        String resetUrl = appBaseUrl + "/reset-password?token=" + token;
        // Sync, swallow + log: anti-enumeration trumps loud-fail; we cannot signal failure to the caller.
        try {
            accountEmail.sendPasswordReset(user, resetUrl, PasswordResetService.EXPIRES_IN_MINUTES);
        } catch (RuntimeException e) {
            log.error("Password-reset email send failed for {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }

    @Transactional
    public void resetPassword(com.imin.iminapi.dto.auth.ResetPasswordRequest req) {
        User user = passwordResetSvc.consume(req.token(), req.newPassword());
        sessions.revokeAllForUser(user.getId(), Instant.now());
        try {
            accountEmail.sendPasswordChangedNotification(user);
        } catch (RuntimeException e) {
            log.warn("Password-changed notification failed for {}: {}", user.getEmail(), e.getMessage());
        }
    }
```

The imports `EmailVerificationService` and `AccountEmailService` were added in Task 11; `PasswordResetService` is in the same `service.auth` package and needs no import.

- [ ] **Step 13.5: Run all tests**

Run: `./mvnw test -Dtest=AuthServiceTest -q`
Expected: PASS (all old + new).

- [ ] **Step 13.6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/auth/AuthService.java \
        src/test/java/com/imin/iminapi/service/auth/AuthServiceTest.java
git commit -m "Add verifyEmail/resendVerification/forgotPassword/resetPassword to AuthService"
```

---

## Task 14: Controller endpoints + tests + SecurityConfig permitAll

**Goal:** Expose the four new methods at `/api/v1/auth/...`, mark them `permitAll`, and end-to-end test them.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/controller/auth/AuthController.java`
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`
- Modify: `src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java`

- [ ] **Step 14.1: Add the four endpoints to `AuthController`**

In `src/main/java/com/imin/iminapi/controller/auth/AuthController.java`, add imports and endpoints:

```java
import com.imin.iminapi.dto.auth.ForgotPasswordRequest;
import com.imin.iminapi.dto.auth.ResendVerificationRequest;
import com.imin.iminapi.dto.auth.ResetPasswordRequest;
import com.imin.iminapi.dto.auth.VerifyEmailRequest;
```

After `signup` and before `login`, add:

```java
    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        return authService.verifyEmail(req);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.OK)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        rateLimiter.consume("verification-resend", req.email().toLowerCase());
        authService.resendVerification(req);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        rateLimiter.consume("password-reset", req.email().toLowerCase());
        authService.forgotPassword(req);
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
    }
```

(Re-using the existing `RateLimiter` to throttle the two anti-enumeration endpoints — bucket configs come from existing `imin.ratelimit` config; they fall back to a default if not configured. If running into errors here, just remove the `rateLimiter.consume(...)` lines and follow up later.)

- [ ] **Step 14.2: Permit the new endpoints in `SecurityConfig`**

In `src/main/java/com/imin/iminapi/config/SecurityConfig.java`, find the existing line listing auth permits (around line 75–76):

```java
                                         "/api/v1/auth/logout").permitAll()
```

Replace it with:

```java
                        .requestMatchers(HttpMethod.POST,
                                         "/api/v1/auth/signup",
                                         "/api/v1/auth/login",
                                         "/api/v1/auth/logout",
                                         "/api/v1/auth/verify-email",
                                         "/api/v1/auth/resend-verification",
                                         "/api/v1/auth/forgot-password",
                                         "/api/v1/auth/reset-password").permitAll()
```

(If the existing block already uses a different shape — e.g. one matcher per line — extend it consistently; the goal is the same set of paths.)

- [ ] **Step 14.3: Add controller tests**

In `src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java`, append:

```java
    @Test
    void verify_email_returns_auth_response() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(authService.verifyEmail(any(com.imin.iminapi.dto.auth.VerifyEmailRequest.class)))
                .thenReturn(new AuthResponse("tok-xyz", sampleUser(orgId), sampleOrg(orgId)));

        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com", "code", "1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok-xyz"));
    }

    @Test
    void verify_email_with_bad_code_format_returns_FIELD_INVALID() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com", "code", "abcd"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    void verify_email_with_invalid_code_returns_INVALID_CODE() throws Exception {
        when(authService.verifyEmail(any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_CODE, "Invalid or expired verification code"));
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com", "code", "0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
    }

    @Test
    void resend_verification_returns_200() throws Exception {
        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void forgot_password_returns_200_for_any_email() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "nobody@example.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void reset_password_returns_200_on_success() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("token", "abc", "newPassword", "newpassword12"))))
                .andExpect(status().isOk());
    }

    @Test
    void reset_password_with_invalid_token_returns_INVALID_TOKEN() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_TOKEN, "Invalid or expired reset token"))
                .when(authService).resetPassword(any());
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("token", "garbage", "newPassword", "newpassword12"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    void reset_password_with_short_password_returns_FIELD_INVALID() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("token", "abc", "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }
```

- [ ] **Step 14.4: Run the tests**

Run: `./mvnw test -Dtest=AuthControllerTest -q`
Expected: PASS.

- [ ] **Step 14.5: Run the full suite**

Run: `./mvnw test -q`
Expected: all tests PASS.

- [ ] **Step 14.6: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/auth/AuthController.java \
        src/main/java/com/imin/iminapi/config/SecurityConfig.java \
        src/test/java/com/imin/iminapi/controller/auth/AuthControllerTest.java
git commit -m "Expose verify/resend/forgot/reset endpoints with anti-enumeration responses"
```

---

## Task 15: Update CLAUDE.md and rate-limit config

**Goal:** Document the new env vars and add config blocks for the two new rate-limit buckets.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application.yaml`

- [ ] **Step 15.1: Add rate-limit buckets to `application.yaml`**

In `src/main/resources/application.yaml`, under `imin.ratelimit:` (alongside existing `login:` and `ai-concept:`), add:

```yaml
    verification-resend:
      capacity: 3
      window-minutes: 15
    password-reset:
      capacity: 3
      window-minutes: 15
```

In `src/test/resources/application.yaml`, mirror with high capacity to avoid test flake:

```yaml
    verification-resend:
      capacity: 1000
      window-minutes: 15
    password-reset:
      capacity: 1000
      window-minutes: 15
```

- [ ] **Step 15.2: Update `CLAUDE.md` env vars section**

In `/Users/ikozmuliak/imin/imin-api/CLAUDE.md`, find the "Required env vars for running locally" section and add:

```markdown
- `RESEND_API_KEY` — Resend API key (required to send any auth email; signup/login/forgot-password fail without it)
- `IMIN_EMAIL_FROM_ADDRESS` — sender email address (default `noreply@imin.local` — must be a verified Resend sender in prod)
- `IMIN_EMAIL_FROM_NAME` — sender display name (default `imin`)
- `IMIN_EMAIL_REPLY_TO` — optional reply-to header
- `IMIN_APP_BASE_URL` — frontend base URL used to build password-reset links (default `http://localhost:3000`)
```

Also under the "Architecture" / "Conventions" section, add a short note (after the existing security bullet):

```markdown
- **Auth flows email users via Resend.** Signup persists the user with `verified_at = NULL` and emails a 4-digit code; login is hard-blocked with `403 EMAIL_NOT_VERIFIED` until verification. Password reset uses a long random token link. See `docs/superpowers/specs/2026-05-04-resend-integration-design.md`.
```

- [ ] **Step 15.3: Run the full suite once more**

Run: `./mvnw test -q`
Expected: PASS.

- [ ] **Step 15.4: Commit**

```bash
git add CLAUDE.md src/main/resources/application.yaml src/test/resources/application.yaml
git commit -m "Document Resend env vars and add rate-limit buckets for resend/forgot endpoints"
```

---

## Manual verification (after merge)

These can't be automated — run them on a dev environment with `RESEND_API_KEY` set and a real inbox:

1. `POST /api/v1/auth/signup` → confirm 200 with `{ message, email }`, no token, and the verification email arrives.
2. `POST /api/v1/auth/login` with the new account → 403 `EMAIL_NOT_VERIFIED`.
3. `POST /api/v1/auth/verify-email` with the code from the email → 200 with `AuthResponse`. Welcome email arrives.
4. `POST /api/v1/auth/login` again → 200.
5. `POST /api/v1/auth/forgot-password` → 200, reset email arrives with a `${IMIN_APP_BASE_URL}/reset-password?token=...` link.
6. `POST /api/v1/auth/reset-password` with the token → 200, password-changed notification email arrives, prior session token now returns 401 on `/api/v1/auth/me`.
7. `POST /api/v1/auth/forgot-password` for a non-existent email → 200 (no email sent, no error to the client).

---

## Self-review notes

- **Spec coverage:** all spec sections — architecture, schema, API surface, templates, error handling, sync split, testing — have at least one task. ✓
- **Type consistency:** `EmailService.send` signature matches across `ResendEmailService`, `RecordingEmailService`, and `AccountEmailService`. `EmailVerificationService.EXPIRES_IN_MINUTES` and `PasswordResetService.EXPIRES_IN_MINUTES` are referenced in `AuthService` and tests with matching names. `revokeAllForUser` is referenced in tests (Task 13) and defined in Task 10. ✓
- **No placeholders:** every step shows actual code or actual commands. ✓
- **Migration-runs-against-H2 caveat:** noted in implementer notes; SQL uses `TIMESTAMP`/`CURRENT_TIMESTAMP` which both engines accept. ✓
- **Caveat to executor:** if the Resend SDK class names differ from the snippets (e.g. SDK rename across major versions), adapt by reading the SDK's published Javadoc — the call shape (build options, send) is stable.
