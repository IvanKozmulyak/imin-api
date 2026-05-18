package com.imin.iminapi.service.audit;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.audit.AuditLogDto;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditQueryServiceTest {

    AuditLogRepository auditLogs;
    AuditQueryService sut;

    UUID orgId = UUID.randomUUID();
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        auditLogs = mock(AuditLogRepository.class);
        sut = new AuditQueryService(auditLogs);
        principal = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void list_returnsPageScopedToPrincipalOrg_withDefaultPagination() {
        AuditLog row = makeRow(orgId);
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        PageResponse<AuditLogDto> resp = sut.list(principal, 1, 20);

        assertThat(resp.total()).isEqualTo(1);
        assertThat(resp.page()).isEqualTo(1);
        assertThat(resp.pageSize()).isEqualTo(20);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).id()).isEqualTo(row.getId());
        assertThat(resp.items().get(0).action()).isEqualTo("EVENT_CREATED");
    }

    @Test
    void list_passesCorrectPageRequestToRepo_withCustomPageSize() {
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        sut.list(principal, 3, 50);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogs).findByOrgIdOrderByOccurredAtDesc(eq(orgId), captor.capture());
        Pageable used = captor.getValue();
        assertThat(used.getPageNumber()).isEqualTo(2); // 0-based: page 3 → index 2
        assertThat(used.getPageSize()).isEqualTo(50);
    }

    @Test
    void list_clampsPageSizeAboveMax_to100() {
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        sut.list(principal, 1, 9999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogs).findByOrgIdOrderByOccurredAtDesc(eq(orgId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void list_clampsPageSizeBelowMin_to1() {
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        sut.list(principal, 1, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogs).findByOrgIdOrderByOccurredAtDesc(eq(orgId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void list_clampsNegativePage_to1() {
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        sut.list(principal, -5, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogs).findByOrgIdOrderByOccurredAtDesc(eq(orgId), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
    }

    private AuditLog makeRow(UUID orgId) {
        AuditLog r = new AuditLog();
        r.setId(UUID.randomUUID());
        r.setOrgId(orgId);
        r.setActorId(UUID.randomUUID());
        r.setActorEmail("a@b.com");
        r.setAction("EVENT_CREATED");
        r.setTargetType("event");
        r.setTargetId(UUID.randomUUID());
        r.setSummary("Created event");
        r.setOccurredAt(Instant.parse("2026-05-18T10:00:00Z"));
        return r;
    }
}
