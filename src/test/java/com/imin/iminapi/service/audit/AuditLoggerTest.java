package com.imin.iminapi.service.audit;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLoggerTest {

    AuditLogRepository auditLogs;
    UserRepository users;
    AuditLogger sut;

    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        auditLogs = mock(AuditLogRepository.class);
        users = mock(UserRepository.class);
        sut = new AuditLogger(auditLogs, users);
        principal = new AuthPrincipal(userId, orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void record_happyPath_persistsAllFieldsWithActorEmailFromUserRepo() {
        User actor = new User();
        actor.setId(userId);
        actor.setEmail("alice@example.com");
        when(users.findById(userId)).thenReturn(Optional.of(actor));
        when(auditLogs.saveAndFlush(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID targetId = UUID.randomUUID();
        sut.record(principal, AuditActions.EVENT_CREATED, "event", targetId,
                "Created event \"Sample\"");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).saveAndFlush(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getOrgId()).isEqualTo(orgId);
        assertThat(saved.getActorId()).isEqualTo(userId);
        assertThat(saved.getActorEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getAction()).isEqualTo(AuditActions.EVENT_CREATED);
        assertThat(saved.getTargetType()).isEqualTo("event");
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.getSummary()).isEqualTo("Created event \"Sample\"");
    }

    @Test
    void record_userLookupMiss_leavesActorEmailNullAndStillPersists() {
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(auditLogs.saveAndFlush(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.record(principal, AuditActions.EVENT_UPDATED, "event", UUID.randomUUID(),
                "Updated event");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).saveAndFlush(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getActorEmail()).isNull();
        assertThat(saved.getActorId()).isEqualTo(userId);
        assertThat(saved.getAction()).isEqualTo(AuditActions.EVENT_UPDATED);
    }

    @Test
    void record_repositoryThrows_swallowsException() {
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(auditLogs.saveAndFlush(any(AuditLog.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> sut.record(principal, AuditActions.EVENT_PUBLISHED, "event",
                UUID.randomUUID(), "Published event"))
                .doesNotThrowAnyException();
    }

    @Test
    void record_userLookupThrows_swallowsAndProceedsWithNullEmail() {
        when(users.findById(userId)).thenThrow(new RuntimeException("io error"));
        when(auditLogs.saveAndFlush(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.record(principal, AuditActions.MEMBER_INVITED, "user", UUID.randomUUID(),
                "Invited member");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getActorEmail()).isNull();
    }

    // ── The two guards that make the "best-effort" contract actually true ──

    /**
     * {@code audit_logs.org_id} is NOT NULL (V21:3), so an org-less row cannot be
     * written. It must be dropped BEFORE the INSERT, not caught after it: this
     * method runs in its own {@code REQUIRES_NEW} transaction, and a failed
     * INSERT marks that transaction rollback-only so its commit — raised by the
     * interceptor, after the catch block is out of scope — takes the caller's
     * business transaction down with it.
     *
     * <p>That is not hypothetical. {@code AudienceErasureJob} passed exactly this
     * principal, and {@code DsarService.executeErase} therefore erased nothing at
     * all, every night, while logging a caught "Erasure failed".
     */
    @Test
    void record_nullOrgPrincipal_skipsTheWriteEntirelyRatherThanFailingIt() {
        AuthPrincipal orgless = new AuthPrincipal(null, null, UserRole.MEMBER, null);

        assertThatCode(() -> sut.record(orgless, AuditActions.DSAR_ERASE_EXECUTED,
                "membership", UUID.randomUUID(), "erased"))
                .doesNotThrowAnyException();

        verify(auditLogs, never()).saveAndFlush(any(AuditLog.class));
        verify(auditLogs, never()).save(any(AuditLog.class));
    }

    @Test
    void record_nullPrincipal_isAlsoSkippedRatherThanThrowingNpe() {
        assertThatCode(() -> sut.record(null, AuditActions.EVENT_CREATED, "event",
                UUID.randomUUID(), "created"))
                .doesNotThrowAnyException();

        verify(auditLogs, never()).saveAndFlush(any(AuditLog.class));
    }

    /** {@code summary} is {@code VARCHAR(512)} — an overlong one is clipped, never rejected. */
    @Test
    void record_overlongSummary_isTruncatedToTheColumnWidth() {
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(auditLogs.saveAndFlush(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.record(principal, AuditActions.EVENT_UPDATED, "event", UUID.randomUUID(),
                "x".repeat(600));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSummary()).hasSize(512);
    }

    @Test
    void record_nullSummary_becomesEmptyNotNull() {
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(auditLogs.saveAndFlush(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.record(principal, AuditActions.EVENT_UPDATED, "event", UUID.randomUUID(), null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSummary()).isEmpty();
    }
}
