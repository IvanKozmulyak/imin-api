# Resend integration: transactional email for auth flows

**Status:** Draft
**Date:** 2026-05-04

## Goal

Wire the Resend email API into the application and use it to power three auth-related transactional flows:

1. **Email verification** — required after signup. 4-digit code, hard-block on login until verified.
2. **Password reset** — long random link token sent to the user's email.
3. **Welcome email** — sent on successful verification.

A non-goal of this spec is wiring email into team invitations or any other surface; the integration is built generic enough that future callers can reuse it without changes here.

## Background

The codebase has no email infrastructure today. `AuthService.signup` returns a session token immediately; `AuthService.login` only checks credentials. There is no `verified_at` column on `users`, no token tables, and no email-sending bean. The closest precedent for a third-party HTTP integration is `ReplicateClient` / `OpenAiImageClient` — both written as Spring `RestClient` wrappers — but for Resend we'll use the official `com.resend:resend-java` SDK to avoid hand-rolling the API surface.

Existing relevant pieces:

- `src/main/java/com/imin/iminapi/service/auth/AuthService.java` — owns signup/login/logout/me.
- `src/main/java/com/imin/iminapi/controller/auth/AuthController.java` — REST surface.
- `src/main/java/com/imin/iminapi/security/SecurityConfig.java` — `/api/auth/**` is `permitAll`; new endpoints inherit this.
- `src/main/java/com/imin/iminapi/security/ErrorCode.java` — typed error codes returned in API responses.
- `src/main/resources/db/migration/` — Flyway migrations.

## Architecture

### New package: `com.imin.iminapi.email`

- **`EmailProperties`** — `@ConfigurationProperties("imin.email")`. Fields: `apiKey`, `fromAddress`, `fromName`, `replyTo`, `appBaseUrl`. Sourced from env (`RESEND_API_KEY`, `IMIN_EMAIL_FROM_ADDRESS`, `IMIN_EMAIL_FROM_NAME`, `IMIN_EMAIL_REPLY_TO`, `IMIN_APP_BASE_URL`).
- **`ResendConfig`** — `@Configuration` that builds a singleton `com.resend.Resend` SDK client from `EmailProperties.apiKey`.
- **`EmailService`** — interface with one method: `void send(String to, String subject, String html, String text)`. Single seam for tests.
- **`ResendEmailService`** — production implementation. Wraps `Resend.emails().send(...)`, sets `from` and `replyTo` from `EmailProperties`. Translates SDK exceptions into typed application errors.
- **`EmailTemplateRenderer`** — loads classpath HTML/text files from `email-templates/` and performs `{{key}}` substitution from a `Map<String, String>`. Returns `Rendered(String html, String text)`.
- **`AccountEmailService`** — domain-level facade. Methods:
  - `sendVerificationCode(User user, String code, int expiresInMinutes)`
  - `sendWelcome(User user)`
  - `sendPasswordReset(User user, String resetUrl, int expiresInMinutes)`
  - `sendPasswordChangedNotification(User user)`

  Each method composes the renderer + `EmailService`. Subjects are hardcoded constants on this class.

### New package: `com.imin.iminapi.service.auth.verification`

- **`EmailVerificationService`** — `String issueCode(User user)` and `User verify(String email, String code)`. Owns `email_verification_codes`. On verify success, sets `users.verified_at = NOW()` and marks the code consumed.
- **`PasswordResetService`** — `String issueToken(User user)` (returns the cleartext token to embed in the email link) and `User consume(String token, String newPassword)`. Owns `password_reset_tokens`. Stores only `sha256(token)`.

### `AuthService` changes

- `signup(SignupRequest)` — return type changes from `AuthResponse` to a new `VerificationPendingResponse(String message, String email)`. After saving user + org, calls `EmailVerificationService.issueCode` and `AccountEmailService.sendVerificationCode`. **No session is issued.** Email send is synchronous; failure propagates as a 500.
- `login(LoginRequest)` — adds a check after credential validation: if `user.verifiedAt == null`, throw `ApiException(403, ErrorCode.EMAIL_NOT_VERIFIED, "Email not verified")`.
- New methods: `verifyEmail(VerifyEmailRequest) -> AuthResponse`, `resendVerification(ResendVerificationRequest) -> void`, `forgotPassword(ForgotPasswordRequest) -> void`, `resetPassword(ResetPasswordRequest) -> void`.
- `verifyEmail` issues a session via the existing `issueSession` helper so the user lands logged in directly after verification, and triggers `AccountEmailService.sendWelcome` (sync, swallow + Sentry).
- `resetPassword` revokes all existing sessions for the user (sets `revoked_at = NOW()` for any `auth_sessions` rows where `user_id = ?`) and triggers `AccountEmailService.sendPasswordChangedNotification` (sync, swallow + Sentry).

## API surface

All endpoints live on `AuthController` and are `permitAll` (already covered by `/api/auth/**` in `SecurityConfig`).

### Changed: `POST /api/auth/signup`

- Body: unchanged (`SignupRequest`).
- Response: `VerificationPendingResponse { message: "Verification email sent", email: string }` (200 OK).
- Errors: existing `409 DUPLICATE` for email-in-use; new `500 INTERNAL` propagated from Resend failures.

### New: `POST /api/auth/verify-email`

- Body: `{ email: string (valid email), code: string (exactly 4 digits) }`.
- Response: `AuthResponse { token, user, organization }` (200 OK). User is now logged in.
- Errors: `400 INVALID_CODE` for any failure mode (wrong digits / expired / consumed / max attempts exceeded — single code so we don't help an attacker distinguish).
- Side effects: sets `users.verified_at = NOW()`, marks code consumed, issues a new session, sends welcome email (sync, swallow failure).

### New: `POST /api/auth/resend-verification`

- Body: `{ email: string }`.
- Response: `200 OK` with empty body. Always 200 if the email format is valid (anti-enumeration).
- Behavior:
  - Email valid + user exists + unverified: invalidate any pending code (`UPDATE … SET consumed_at = NOW() WHERE user_id = ? AND consumed_at IS NULL`), issue new code, send email. Resend failure propagates as 500 (the user explicitly asked for it).
  - Email valid + user exists + already verified: do nothing, return 200.
  - Email valid + user does not exist: do nothing, return 200.

### New: `POST /api/auth/forgot-password`

- Body: `{ email: string }`.
- Response: `200 OK` with empty body. Always 200 (anti-enumeration).
- Behavior:
  - Email valid + user exists: issue token, send email with link `${imin.email.appBaseUrl}/reset-password?token={token}`. Resend failure logged via Sentry, response is still 200 — anti-enumeration trumps loud-fail (we couldn't surface the failure without leaking that the email exists).
  - Email valid + user does not exist: do nothing, return 200.
- Issuing a new token does NOT invalidate prior unexpired tokens; the user can click the link from any unconsumed email.

### New: `POST /api/auth/reset-password`

- Body: `{ token: string, newPassword: string (min length per existing password policy) }`.
- Response: `200 OK` with empty body.
- Side effects: updates `users.password_hash`, marks token consumed, revokes all existing sessions for the user, sends "your password was changed" notification email (sync, swallow failure).
- Errors: `400 INVALID_TOKEN` for unknown / expired / consumed tokens (single code).

### Changed: `POST /api/auth/login`

- New failure mode: `403 EMAIL_NOT_VERIFIED` when the matched user has `verified_at IS NULL`. Frontend uses this to redirect to the verify screen.

## Schema changes

One forward Flyway migration: `V<next>__email_verification_and_password_reset.sql`.

```sql
-- 1. Track email verification on users
ALTER TABLE users ADD COLUMN verified_at TIMESTAMPTZ NULL;
-- Backfill so existing users don't get locked out on deploy
UPDATE users SET verified_at = NOW() WHERE verified_at IS NULL;

-- 2. Email verification codes
CREATE TABLE email_verification_codes (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code            CHAR(4) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ NULL,
    attempts        INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_evc_user_active
    ON email_verification_codes(user_id)
    WHERE consumed_at IS NULL;

-- 3. Password reset tokens
CREATE TABLE password_reset_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      CHAR(64) NOT NULL UNIQUE,  -- sha256 hex of the cleartext token
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_prt_token_hash ON password_reset_tokens(token_hash);
```

### Token parameters

- **Verification code:** 4 digits, generated with `SecureRandom` in range `[0, 9999]` and zero-padded. 10-minute expiry. Max 5 attempts per code; the 6th wrong submission marks it as effectively dead (consumed_at is left NULL but `attempts >= 5` makes verify fail with `INVALID_CODE` and the user must call `resend-verification`). Single-use on success.
- **Password reset token:** 32 random bytes from `SecureRandom`, base64url-encoded. Stored as sha256 hex. 30-minute expiry. Single-use.

## Email templates

Six files under `src/main/resources/email-templates/`, each as `.html` + `.txt` pair (sent multipart):

| Template                    | Placeholders                              |
| --------------------------- | ----------------------------------------- |
| `verification-code.{html,txt}` | `{{code}}`, `{{expiresInMinutes}}`         |
| `welcome.{html,txt}`        | `{{name}}`, `{{appBaseUrl}}`              |
| `password-reset.{html,txt}` | `{{resetUrl}}`, `{{expiresInMinutes}}`    |
| `password-changed.{html,txt}` | `{{appBaseUrl}}`                          |

`EmailTemplateRenderer` substitutes `{{key}}` with values from a `Map<String, String>`. A missing key (placeholder in template, no entry in map) is treated as a programming error and throws `IllegalStateException` — fail loudly, since silent substitution would ship broken emails. Subject lines are hardcoded constants on `AccountEmailService`:

- Verification code: `"Your verification code"`
- Welcome: `"Welcome to imin"`
- Password reset: `"Reset your password"`
- Password changed: `"Your password was changed"`

Initial copy/markup will be plain and brand-light. Designers can iterate on the files without touching Java code.

## Error handling

### Resend SDK failures

- 4xx from Resend (e.g. invalid `from`, malformed payload) → treat as configuration bug: log full detail to Sentry, throw `ApiException(500, ErrorCode.INTERNAL, "Email service error")`.
- 429 / 5xx from Resend → log to Sentry, throw `ApiException(503, ErrorCode.UPSTREAM_UNAVAILABLE, "Email service unavailable")`.
- Network / SDK exceptions → same as 5xx.

### Sync split (reaffirms brainstorming decision)

| Operation                          | Behavior on email failure                  |
| ---------------------------------- | ------------------------------------------ |
| `signup` → verification email      | propagate (500)                            |
| `resend-verification` → verification email | propagate (500)                       |
| `verify-email` → welcome email     | swallow + Sentry                           |
| `forgot-password` → reset email    | swallow + Sentry (anti-enumeration)        |
| `reset-password` → password-changed email | swallow + Sentry                       |

### New `ErrorCode` entries

- `EMAIL_NOT_VERIFIED` — used by login when `verified_at IS NULL`.
- `INVALID_CODE` — used by `verify-email` for any code-rejection mode.
- `INVALID_TOKEN` — used by `reset-password` for any token-rejection mode.

## Configuration

### `application.yaml` (defaults)

```yaml
imin:
  email:
    api-key: ${RESEND_API_KEY:}
    from-address: ${IMIN_EMAIL_FROM_ADDRESS:noreply@imin.local}
    from-name: ${IMIN_EMAIL_FROM_NAME:imin}
    reply-to: ${IMIN_EMAIL_REPLY_TO:}
    app-base-url: ${IMIN_APP_BASE_URL:http://localhost:3000}
```

### Test profile

`src/test/resources/application.yaml` does not set `RESEND_API_KEY`. A test-only configuration class registers a `RecordingEmailService` (in-memory `EmailService` that records calls) as a `@Primary` bean so nothing hits the network. Tests assert against the recording instead of mocking the Resend SDK.

### Environment variables documentation

Add the following to `CLAUDE.md`'s "Required env vars" section:

- `RESEND_API_KEY` — required for any email-sending flow (signup, login retries, password reset)
- `IMIN_EMAIL_FROM_ADDRESS`, `IMIN_EMAIL_FROM_NAME`, `IMIN_EMAIL_REPLY_TO` — sender identity
- `IMIN_APP_BASE_URL` — frontend URL used to build password-reset links

## Testing

- **`EmailTemplateRendererTest`** — substitution happy path, missing-key throws, empty-map renders templates with no placeholders, multi-occurrence substitution.
- **`EmailVerificationServiceTest`** — issue creates row with 10-minute expiry, verify success consumes + sets `verified_at`, wrong code increments attempts, expired code rejected, 5 wrong attempts then correct attempt rejected, double-consume rejected.
- **`PasswordResetServiceTest`** — issue stores hash (not cleartext), consume happy path, expired rejected, double-consume rejected, unknown token rejected, password updated correctly.
- **`AuthServiceTest`** (extend existing) — signup returns `VerificationPendingResponse` and no session, signup persists code and triggers email, login on unverified user → 403 `EMAIL_NOT_VERIFIED`, login after verification → 200, `verifyEmail` issues session and sends welcome, `forgotPassword` always 200, `resetPassword` revokes prior sessions.
- **`AuthControllerTest`** — end-to-end coverage of the four new endpoints with `@MockBean EmailService` (or via the `RecordingEmailService` test-profile bean), verifying status codes, response shapes, and that `EmailService.send` was called with the right arguments.

External services (Resend) are never hit from tests — the recording bean covers all assertions about what would have been sent.

## Out of scope

- Team invitation emails (the `TeamService.invite` placeholder remains unchanged).
- HTML email theming / brand polish (templates start plain).
- Localization of email copy.
- Webhook handling for Resend bounces / deliverability events.
- Rate limiting on `forgot-password` / `resend-verification` (worth adding, but tracked separately).
