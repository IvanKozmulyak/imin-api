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
        when(auditLogs.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID targetId = UUID.randomUUID();
        sut.record(principal, AuditActions.EVENT_CREATED, "event", targetId,
                "Created event \"Sample\"");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).save(captor.capture());
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
        when(auditLogs.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.record(principal, AuditActions.EVENT_UPDATED, "event", UUID.randomUUID(),
                "Updated event");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getActorEmail()).isNull();
        assertThat(saved.getActorId()).isEqualTo(userId);
        assertThat(saved.getAction()).isEqualTo(AuditActions.EVENT_UPDATED);
    }

    @Test
    void record_repositoryThrows_swallowsException() {
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(auditLogs.save(any(AuditLog.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> sut.record(principal, AuditActions.EVENT_PUBLISHED, "event",
                UUID.randomUUID(), "Published event"))
                .doesNotThrowAnyException();
    }

    @Test
    void record_userLookupThrows_swallowsAndProceedsWithNullEmail() {
        when(users.findById(userId)).thenThrow(new RuntimeException("io error"));
        when(auditLogs.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.record(principal, AuditActions.MEMBER_INVITED, "user", UUID.randomUUID(),
                "Invited member");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).save(captor.capture());
        assertThat(captor.getValue().getActorEmail()).isNull();
    }
}
