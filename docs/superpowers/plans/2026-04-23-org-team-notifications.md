# Org, Team & Notification Prefs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Depends on:** `2026-04-23-foundation.md`, `2026-04-23-auth.md`.

**Goal:** Implement V1 organization / team / notification-preference endpoints from contract §3:

- `GET /api/v1/org`
- `PATCH /api/v1/org` (If-Match)
- `DELETE /api/v1/org` (owner only; hard cascade)
- `GET /api/v1/org/team`
- `POST /api/v1/org/team/invite`
- `DELETE /api/v1/org/team/:userId`
- `GET /api/v1/me/notifications`
- `PATCH /api/v1/me/notifications`

**Architecture:** Org PATCH uses `IfMatchSupport`. Org DELETE relies on the `ON DELETE CASCADE` set up in foundation V5 + events V6 + studio V7 to wipe everything in one statement. Team is modelled as `User` rows with a nullable `password_hash` — invited users get a row immediately and become "pending" until they set a password. (Password-set / invite-accept is reserved for post-V1 — the spec already lists `/auth/password/*` as out of scope.) Notification preferences live in a new `notification_preferences` table keyed by `user_id` and lazily created on first GET.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Data JPA + Flyway, Spring MVC, JUnit 5, MockMvc.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V8__notification_preferences.sql` | Create | `notification_preferences` table |
| `src/main/java/com/imin/iminapi/model/NotificationPreferences.java` | Create | JPA entity |
| `src/main/java/com/imin/iminapi/repository/NotificationPreferencesRepository.java` | Create | |
| `src/main/java/com/imin/iminapi/dto/NotificationPreferencesDto.java` | Create | API shape |
| `src/main/java/com/imin/iminapi/dto/org/TeamMemberDto.java` | Create | API shape |
| `src/main/java/com/imin/iminapi/dto/org/InviteRequest.java` | Create | invite body |
| `src/main/java/com/imin/iminapi/dto/org/InviteResponse.java` | Create | `{inviteId, email, role}` |
| `src/main/java/com/imin/iminapi/dto/org/OrgPatchRequest.java` | Create | partial org update |
| `src/main/java/com/imin/iminapi/dto/me/NotificationPrefsPatchRequest.java` | Create | partial prefs update |
| `src/main/java/com/imin/iminapi/service/org/OrgService.java` | Create | get / patch / delete |
| `src/main/java/com/imin/iminapi/service/org/TeamService.java` | Create | list / invite / remove |
| `src/main/java/com/imin/iminapi/service/me/NotificationPrefsService.java` | Create | get / patch |
| `src/main/java/com/imin/iminapi/controller/org/OrgController.java` | Create | /org endpoints |
| `src/main/java/com/imin/iminapi/controller/org/TeamController.java` | Create | /org/team endpoints |
| `src/main/java/com/imin/iminapi/controller/me/MeController.java` | Create | /me/notifications endpoints |
| Tests for each service + controller | Create | |

---

## Task 1: Flyway V8 — notification_preferences

**Files:**
- Create: `src/main/resources/db/migration/V8__notification_preferences.sql`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE notification_preferences (
    user_id           UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    ticket_sold       BOOLEAN      NOT NULL DEFAULT TRUE,
    squad_formed      BOOLEAN      NOT NULL DEFAULT TRUE,
    predictor_shift   BOOLEAN      NOT NULL DEFAULT TRUE,
    fill_milestone    BOOLEAN      NOT NULL DEFAULT TRUE,
    post_event_report BOOLEAN      NOT NULL DEFAULT TRUE,
    campaign_ended    BOOLEAN      NOT NULL DEFAULT TRUE,
    payout_arrived    BOOLEAN      NOT NULL DEFAULT TRUE
);
```

- [ ] **Step 2: Apply + commit**

Run: `./mvnw -q -DskipTests spring-boot:run` (Ctrl-C after Flyway)

```bash
git add src/main/resources/db/migration/V8__notification_preferences.sql
git commit -m "db: V8 notification_preferences"
```

---

## Task 2: NotificationPreferences entity + repo + DTO

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/NotificationPreferences.java`
- Create: `src/main/java/com/imin/iminapi/repository/NotificationPreferencesRepository.java`
- Create: `src/main/java/com/imin/iminapi/dto/NotificationPreferencesDto.java`

- [ ] **Step 1: Entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
public class NotificationPreferences {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "ticket_sold", nullable = false) private boolean ticketSold = true;
    @Column(name = "squad_formed", nullable = false) private boolean squadFormed = true;
    @Column(name = "predictor_shift", nullable = false) private boolean predictorShift = true;
    @Column(name = "fill_milestone", nullable = false) private boolean fillMilestone = true;
    @Column(name = "post_event_report", nullable = false) private boolean postEventReport = true;
    @Column(name = "campaign_ended", nullable = false) private boolean campaignEnded = true;
    @Column(name = "payout_arrived", nullable = false) private boolean payoutArrived = true;
}
```

- [ ] **Step 2: Repository**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.NotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface NotificationPreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {
}
```

- [ ] **Step 3: DTO**

```java
package com.imin.iminapi.dto;

import com.imin.iminapi.model.NotificationPreferences;

import java.util.UUID;

public record NotificationPreferencesDto(
        UUID userId,
        boolean ticketSold, boolean squadFormed, boolean predictorShift,
        boolean fillMilestone, boolean postEventReport, boolean campaignEnded,
        boolean payoutArrived) {
    public static NotificationPreferencesDto from(NotificationPreferences p) {
        return new NotificationPreferencesDto(p.getUserId(),
                p.isTicketSold(), p.isSquadFormed(), p.isPredictorShift(),
                p.isFillMilestone(), p.isPostEventReport(), p.isCampaignEnded(),
                p.isPayoutArrived());
    }
}
```

- [ ] **Step 4: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/model/NotificationPreferences.java src/main/java/com/imin/iminapi/repository/NotificationPreferencesRepository.java src/main/java/com/imin/iminapi/dto/NotificationPreferencesDto.java
git commit -m "model: NotificationPreferences entity + repo + DTO"
```

---

## Task 3: OrgPatchRequest + OrgService + tests

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/org/OrgPatchRequest.java`
- Create: `src/main/java/com/imin/iminapi/service/org/OrgService.java`
- Create: `src/test/java/com/imin/iminapi/service/org/OrgServiceTest.java`

- [ ] **Step 1: OrgPatchRequest**

```java
package com.imin.iminapi.dto.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgPatchRequest(
        @Size(max = 255) String name,
        @Email String contactEmail,
        @Size(min = 2, max = 2) String country,
        @Size(max = 64) String timezone) {}
```

- [ ] **Step 2: Test**

```java
package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrgServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    IfMatchSupport ifMatch = new IfMatchSupport();
    OrgService sut = new OrgService(orgs, ifMatch);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void get_returns_org() {
        UUID orgId = UUID.randomUUID();
        Organization o = new Organization(); o.setId(orgId); o.setName("X"); o.setContactEmail("a@b.com"); o.setCountry("GB");
        when(orgs.findById(orgId)).thenReturn(Optional.of(o));
        OrganizationDto dto = sut.get(owner(orgId));
        assertThat(dto.id()).isEqualTo(orgId);
    }

    @Test
    void patch_updates_fields_on_match() {
        UUID orgId = UUID.randomUUID();
        Organization o = new Organization();
        o.setId(orgId); o.setName("Old"); o.setContactEmail("a@b.com"); o.setCountry("GB");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        o.setUpdatedAt(updated);
        when(orgs.findById(orgId)).thenReturn(Optional.of(o));
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        OrganizationDto dto = sut.patch(owner(orgId), "\"" + updated + "\"",
                new OrgPatchRequest("New Name", null, null, "Europe/Berlin"));
        assertThat(dto.name()).isEqualTo("New Name");
        assertThat(dto.timezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void patch_with_mismatch_throws_STALE_WRITE() {
        UUID orgId = UUID.randomUUID();
        Organization o = new Organization();
        o.setId(orgId); o.setName("Old"); o.setContactEmail("a@b.com"); o.setCountry("GB");
        o.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));
        when(orgs.findById(orgId)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> sut.patch(owner(orgId), "\"2026-01-01T00:00:00Z\"",
                new OrgPatchRequest("X", null, null, null)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.STALE_WRITE);
    }

    @Test
    void delete_only_allowed_for_owner() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal admin = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.ADMIN, UUID.randomUUID());
        when(orgs.existsById(orgId)).thenReturn(true);
        assertThatThrownBy(() -> sut.delete(admin))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
    }

    @Test
    void delete_owner_cascades() {
        UUID orgId = UUID.randomUUID();
        when(orgs.existsById(orgId)).thenReturn(true);
        sut.delete(owner(orgId));
        verify(orgs).deleteById(orgId);
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./mvnw -q test -Dtest=OrgServiceTest`
Expected: FAIL.

- [ ] **Step 4: Implement OrgService**

```java
package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrgService {

    private final OrganizationRepository orgs;
    private final IfMatchSupport ifMatch;

    public OrgService(OrganizationRepository orgs, IfMatchSupport ifMatch) {
        this.orgs = orgs;
        this.ifMatch = ifMatch;
    }

    @Transactional(readOnly = true)
    public OrganizationDto get(AuthPrincipal p) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        return OrganizationDto.from(o);
    }

    @Transactional
    public OrganizationDto patch(AuthPrincipal p, String ifMatchHeader, OrgPatchRequest body) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        ifMatch.requireMatch(ifMatchHeader, o.getUpdatedAt());
        if (body.name() != null) o.setName(body.name());
        if (body.contactEmail() != null) o.setContactEmail(body.contactEmail());
        if (body.country() != null) o.setCountry(body.country().toUpperCase());
        if (body.timezone() != null) o.setTimezone(body.timezone());
        o.setUpdatedAt(Instant.now());
        return OrganizationDto.from(orgs.save(o));
    }

    @Transactional
    public void delete(AuthPrincipal p) {
        if (p.role() != UserRole.OWNER) throw ApiException.forbidden("Only the org owner can delete the organization");
        if (!orgs.existsById(p.orgId())) throw ApiException.notFound("Organization");
        orgs.deleteById(p.orgId()); // FK ON DELETE CASCADE wipes users, events, etc.
    }
}
```

- [ ] **Step 5: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=OrgServiceTest`
Expected: 5 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/org/OrgPatchRequest.java src/main/java/com/imin/iminapi/service/org/OrgService.java src/test/java/com/imin/iminapi/service/org/OrgServiceTest.java
git commit -m "org: OrgService get / patch (If-Match) / delete (owner-only cascade)"
```

---

## Task 4: TeamMemberDto + InviteRequest/Response

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/org/TeamMemberDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/org/InviteRequest.java`
- Create: `src/main/java/com/imin/iminapi/dto/org/InviteResponse.java`

- [ ] **Step 1: TeamMemberDto**

```java
package com.imin.iminapi.dto.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.model.User;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberDto(
        UUID id, String email, String name, String role,
        String avatarInitials, UUID orgId,
        Instant createdAt, Instant lastActive) {
    public static TeamMemberDto from(User u) {
        return new TeamMemberDto(u.getId(), u.getEmail(), u.getName(),
                u.getRole().wireValue(), u.getAvatarInitials(), u.getOrgId(),
                u.getCreatedAt(), u.getLastActiveAt());
    }
}
```

- [ ] **Step 2: InviteRequest**

```java
package com.imin.iminapi.dto.org;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record InviteRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "admin|member") String role) {}
```

- [ ] **Step 3: InviteResponse**

```java
package com.imin.iminapi.dto.org;

import java.util.UUID;

public record InviteResponse(UUID inviteId, String email, String role) {}
```

- [ ] **Step 4: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/dto/org/
git commit -m "dto: TeamMemberDto, InviteRequest, InviteResponse"
```

---

## Task 5: TeamService

**Files:**
- Modify: `src/main/java/com/imin/iminapi/repository/UserRepository.java`
- Create: `src/main/java/com/imin/iminapi/service/org/TeamService.java`
- Create: `src/test/java/com/imin/iminapi/service/org/TeamServiceTest.java`

- [ ] **Step 1: Repo addition**

Add to `UserRepository`:

```java
java.util.List<com.imin.iminapi.model.User> findByOrgIdOrderByCreatedAtAsc(java.util.UUID orgId);
```

- [ ] **Step 2: Test**

```java
package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TeamServiceTest {

    UserRepository users = mock(UserRepository.class);
    TeamService sut = new TeamService(users);

    private AuthPrincipal admin(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.ADMIN, UUID.randomUUID());
    }
    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void list_returns_org_members() {
        UUID orgId = UUID.randomUUID();
        User u = new User(); u.setId(UUID.randomUUID()); u.setOrgId(orgId);
        u.setEmail("a@b.com"); u.setRole(UserRole.OWNER);
        when(users.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of(u));

        List<TeamMemberDto> r = sut.list(admin(orgId));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).email()).isEqualTo("a@b.com");
    }

    @Test
    void invite_creates_user_with_no_password_hash_and_returns_inviteId() {
        UUID orgId = UUID.randomUUID();
        when(users.existsByEmailLower("new@x.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(UUID.randomUUID()); return u;
        });

        InviteResponse r = sut.invite(admin(orgId), new InviteRequest("New@X.com", "member"));
        assertThat(r.email()).isEqualTo("New@X.com");
        assertThat(r.role()).isEqualTo("member");
        assertThat(r.inviteId()).isNotNull();
    }

    @Test
    void invite_existing_email_returns_DUPLICATE() {
        UUID orgId = UUID.randomUUID();
        when(users.existsByEmailLower("dupe@x.com")).thenReturn(true);
        assertThatThrownBy(() -> sut.invite(admin(orgId), new InviteRequest("dupe@x.com", "member")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.DUPLICATE);
    }

    @Test
    void remove_owner_throws_FORBIDDEN() {
        UUID orgId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User own = new User(); own.setId(ownerId); own.setOrgId(orgId); own.setRole(UserRole.OWNER);
        when(users.findById(ownerId)).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> sut.remove(owner(orgId), ownerId))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
    }

    @Test
    void remove_member_in_other_org_returns_NOT_FOUND() {
        UUID orgId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        User u = new User(); u.setId(otherUser); u.setOrgId(UUID.randomUUID()); u.setRole(UserRole.MEMBER);
        when(users.findById(otherUser)).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> sut.remove(admin(orgId), otherUser))
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
    }

    @Test
    void remove_member_deletes_row() {
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        User u = new User(); u.setId(memberId); u.setOrgId(orgId); u.setRole(UserRole.MEMBER);
        when(users.findById(memberId)).thenReturn(Optional.of(u));
        sut.remove(admin(orgId), memberId);
        verify(users).delete(u);
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./mvnw -q test -Dtest=TeamServiceTest`
Expected: FAIL.

- [ ] **Step 4: Implement TeamService**

```java
package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private final UserRepository users;

    public TeamService(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public List<TeamMemberDto> list(AuthPrincipal p) {
        return users.findByOrgIdOrderByCreatedAtAsc(p.orgId()).stream().map(TeamMemberDto::from).toList();
    }

    @Transactional
    public InviteResponse invite(AuthPrincipal p, InviteRequest req) {
        String emailLower = req.email().toLowerCase();
        if (users.existsByEmailLower(emailLower)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Email already in use", Map.of("email", "already in use"));
        }
        User u = new User();
        u.setOrgId(p.orgId());
        u.setEmail(req.email());
        u.setName("");
        u.setRole(UserRole.fromWire(req.role()));
        u.setAvatarInitials(initialsOf(req.email()));
        u.setPasswordHash(null); // pending until invite-accept (post-V1)
        User saved = users.save(u);
        return new InviteResponse(saved.getId(), saved.getEmail(), saved.getRole().wireValue());
    }

    @Transactional
    public void remove(AuthPrincipal p, UUID userId) {
        User target = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!target.getOrgId().equals(p.orgId())) throw ApiException.notFound("User");
        if (target.getRole() == UserRole.OWNER) throw ApiException.forbidden("Cannot remove the org owner");
        users.delete(target);
    }

    private static String initialsOf(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        String src = at > 0 ? email.substring(0, at) : email;
        return src.length() <= 1 ? src.toUpperCase() : src.substring(0, 2).toUpperCase();
    }
}
```

- [ ] **Step 5: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=TeamServiceTest`
Expected: 6 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/repository/UserRepository.java src/main/java/com/imin/iminapi/service/org/TeamService.java src/test/java/com/imin/iminapi/service/org/TeamServiceTest.java
git commit -m "team: TeamService list / invite (creates pending user) / remove"
```

---

## Task 6: NotificationPrefsService

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/me/NotificationPrefsPatchRequest.java`
- Create: `src/main/java/com/imin/iminapi/service/me/NotificationPrefsService.java`
- Create: `src/test/java/com/imin/iminapi/service/me/NotificationPrefsServiceTest.java`

- [ ] **Step 1: NotificationPrefsPatchRequest**

```java
package com.imin.iminapi.dto.me;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPrefsPatchRequest(
        Boolean ticketSold, Boolean squadFormed, Boolean predictorShift,
        Boolean fillMilestone, Boolean postEventReport, Boolean campaignEnded,
        Boolean payoutArrived) {}
```

- [ ] **Step 2: Test**

```java
package com.imin.iminapi.service.me;

import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.dto.me.NotificationPrefsPatchRequest;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationPrefsServiceTest {

    NotificationPreferencesRepository repo = mock(NotificationPreferencesRepository.class);
    NotificationPrefsService sut = new NotificationPrefsService(repo);

    private AuthPrincipal user(UUID userId) {
        return new AuthPrincipal(userId, UUID.randomUUID(), UserRole.MEMBER, UUID.randomUUID());
    }

    @Test
    void get_creates_default_row_if_missing() {
        UUID userId = UUID.randomUUID();
        when(repo.findById(userId)).thenReturn(Optional.empty());
        when(repo.save(any(NotificationPreferences.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationPreferencesDto dto = sut.get(user(userId));
        assertThat(dto.userId()).isEqualTo(userId);
        assertThat(dto.ticketSold()).isTrue();
    }

    @Test
    void patch_only_overwrites_present_fields() {
        UUID userId = UUID.randomUUID();
        NotificationPreferences existing = new NotificationPreferences();
        existing.setUserId(userId);
        existing.setTicketSold(true);
        existing.setSquadFormed(true);
        when(repo.findById(userId)).thenReturn(Optional.of(existing));
        when(repo.save(any(NotificationPreferences.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationPreferencesDto dto = sut.patch(user(userId),
                new NotificationPrefsPatchRequest(false, null, null, null, null, null, null));

        assertThat(dto.ticketSold()).isFalse();
        assertThat(dto.squadFormed()).isTrue(); // unchanged
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./mvnw -q test -Dtest=NotificationPrefsServiceTest`
Expected: FAIL.

- [ ] **Step 4: Implement**

```java
package com.imin.iminapi.service.me;

import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.dto.me.NotificationPrefsPatchRequest;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationPrefsService {

    private final NotificationPreferencesRepository repo;

    public NotificationPrefsService(NotificationPreferencesRepository repo) { this.repo = repo; }

    @Transactional
    public NotificationPreferencesDto get(AuthPrincipal p) {
        NotificationPreferences row = repo.findById(p.userId()).orElseGet(() -> {
            NotificationPreferences fresh = new NotificationPreferences();
            fresh.setUserId(p.userId());
            return repo.save(fresh);
        });
        return NotificationPreferencesDto.from(row);
    }

    @Transactional
    public NotificationPreferencesDto patch(AuthPrincipal p, NotificationPrefsPatchRequest body) {
        NotificationPreferences row = repo.findById(p.userId()).orElseGet(() -> {
            NotificationPreferences fresh = new NotificationPreferences();
            fresh.setUserId(p.userId());
            return fresh;
        });
        if (body.ticketSold() != null) row.setTicketSold(body.ticketSold());
        if (body.squadFormed() != null) row.setSquadFormed(body.squadFormed());
        if (body.predictorShift() != null) row.setPredictorShift(body.predictorShift());
        if (body.fillMilestone() != null) row.setFillMilestone(body.fillMilestone());
        if (body.postEventReport() != null) row.setPostEventReport(body.postEventReport());
        if (body.campaignEnded() != null) row.setCampaignEnded(body.campaignEnded());
        if (body.payoutArrived() != null) row.setPayoutArrived(body.payoutArrived());
        return NotificationPreferencesDto.from(repo.save(row));
    }
}
```

- [ ] **Step 5: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=NotificationPrefsServiceTest`
Expected: 2 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/me/NotificationPrefsPatchRequest.java src/main/java/com/imin/iminapi/service/me/NotificationPrefsService.java src/test/java/com/imin/iminapi/service/me/NotificationPrefsServiceTest.java
git commit -m "me: NotificationPrefsService get (lazy default) / patch (partial)"
```

---

## Task 7: Controllers

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/org/OrgController.java`
- Create: `src/main/java/com/imin/iminapi/controller/org/TeamController.java`
- Create: `src/main/java/com/imin/iminapi/controller/me/MeController.java`

- [ ] **Step 1: OrgController**

```java
package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.org.OrgService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) { this.orgService = orgService; }

    @GetMapping
    public OrganizationDto get(@CurrentUser AuthPrincipal p) { return orgService.get(p); }

    @PatchMapping
    public OrganizationDto patch(@CurrentUser AuthPrincipal p,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                 @Valid @RequestBody OrgPatchRequest body) {
        return orgService.patch(p, ifMatch, body);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p) { orgService.delete(p); }
}
```

- [ ] **Step 2: TeamController**

```java
package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.org.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/org/team")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) { this.teamService = teamService; }

    @GetMapping
    public List<TeamMemberDto> list(@CurrentUser AuthPrincipal p) { return teamService.list(p); }

    @PostMapping("/invite")
    public InviteResponse invite(@CurrentUser AuthPrincipal p, @Valid @RequestBody InviteRequest body) {
        return teamService.invite(p, body);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@CurrentUser AuthPrincipal p, @PathVariable UUID userId) {
        teamService.remove(p, userId);
    }
}
```

- [ ] **Step 3: MeController**

```java
package com.imin.iminapi.controller.me;

import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.dto.me.NotificationPrefsPatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.me.NotificationPrefsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final NotificationPrefsService prefs;

    public MeController(NotificationPrefsService prefs) { this.prefs = prefs; }

    @GetMapping("/notifications")
    public NotificationPreferencesDto get(@CurrentUser AuthPrincipal p) { return prefs.get(p); }

    @PatchMapping("/notifications")
    public NotificationPreferencesDto patch(@CurrentUser AuthPrincipal p, @RequestBody NotificationPrefsPatchRequest body) {
        return prefs.patch(p, body);
    }
}
```

- [ ] **Step 4: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/controller/org/ src/main/java/com/imin/iminapi/controller/me/
git commit -m "controllers: /org, /org/team, /me/notifications"
```

---

## Task 8: Smoke (with active token from auth plan)

**Files:** none

- [ ] **Step 1: Boot + token**

```bash
docker compose up -d && ./mvnw -q -DskipTests spring-boot:run &
sleep 6
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"team-smoke@example.com","password":"lovelace12","orgName":"X","country":"GB"}' | jq -r .token)
```

- [ ] **Step 2: GET /org and PATCH it**

```bash
ORG=$(curl -s http://localhost:8085/api/v1/org -H "Authorization: Bearer $TOKEN")
ORG_TS=$(echo "$ORG" | jq -r .updatedAt 2>/dev/null || echo "")
# Org returned has no updatedAt in the DTO; emit one via a fresh GET cycle if needed.
curl -s -X PATCH http://localhost:8085/api/v1/org -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"timezone":"Europe/Berlin"}' | jq
```

> Note: `OrganizationDto` doesn't expose `updatedAt`. If the FE needs `If-Match`, update the DTO to include it. For V1, PATCH without `If-Match` is permitted (no header → no check).

- [ ] **Step 3: Invite + list + remove**

```bash
INVITE=$(curl -s -X POST http://localhost:8085/api/v1/org/team/invite \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"email":"newbie@example.com","role":"member"}')
INVITE_ID=$(echo "$INVITE" | jq -r .inviteId)
curl -s http://localhost:8085/api/v1/org/team -H "Authorization: Bearer $TOKEN" | jq
curl -s -i -X DELETE "http://localhost:8085/api/v1/org/team/$INVITE_ID" -H "Authorization: Bearer $TOKEN"
```
Expected: list shows two members; DELETE returns 204.

- [ ] **Step 4: Notifications**

```bash
curl -s http://localhost:8085/api/v1/me/notifications -H "Authorization: Bearer $TOKEN" | jq
curl -s -X PATCH http://localhost:8085/api/v1/me/notifications \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"ticketSold": false}' | jq
```
Expected: GET returns all true; PATCH returns ticketSold=false, others unchanged.

- [ ] **Step 5: Stop**

Ctrl-C the spring-boot.

---

## Self-Review

- **Spec coverage (§3):** GET/PATCH/DELETE /org ✓, /org/team list+invite+remove ✓, /me/notifications GET+PATCH ✓.
- **If-Match:** PATCH /org wired through `IfMatchSupport`. The note in Task 8.Step 2 highlights that `OrganizationDto` should expose `updatedAt` if FE wants to round-trip — record this DTO change as a small follow-up if the FE complains; the contract §10 `Organization` interface doesn't list `updatedAt` as a field, so leaving it off is consistent.
- **Hard-cascade delete:** confirmed — `users.org_id`, `events.org_id`, `auth_sessions.user_id`, `idempotency_keys.org_id`, `notification_preferences.user_id`, `generated_event.org_id` all have `ON DELETE CASCADE`.
- **Pending invites:** invited users are real `users` rows with `password_hash = NULL`. They appear in `/org/team`. Invite-accept / password-set is post-V1 (§12 lists `/auth/password/*` as reserved).
- **Placeholder scan:** none.
- **Type consistency:** `TeamMemberDto.role` is wire form ("owner"/"admin"/"member"). `NotificationPreferences*` Boolean wrappers in the patch DTO so null = unchanged.
- **Gap:** the spec's `OrganizationDto` doesn't expose `updatedAt` so PATCH /org's `If-Match` is effectively decorative. If you want true optimistic locking, add `updatedAt` to `OrganizationDto` in a follow-up.
