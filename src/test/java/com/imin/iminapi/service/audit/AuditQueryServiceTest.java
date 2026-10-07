package com.imin.iminapi.service.audit;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.audit.AuditLogDto;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    /** Wire pages are 1-based; page size is clamped to 1..100 and a page below 1 reads the first page. */
    @ParameterizedTest(name = "page {0}, size {1} -> index {2}, size {3}")
    @CsvSource({
            "3, 50, 2, 50",     // custom page and size
            "1, 9999, 0, 100",  // size above max
            "1, 0, 0, 1",       // size below min
            "-5, 20, 0, 20"     // negative page
    })
    void list_passesTheClampedPageRequestToRepo(int page, int pageSize, int expectedIndex, int expectedSize) {
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        sut.list(principal, page, pageSize);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogs).findByOrgIdOrderByOccurredAtDesc(eq(orgId), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(expectedIndex);
        assertThat(captor.getValue().getPageSize()).isEqualTo(expectedSize);
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
