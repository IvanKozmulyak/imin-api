package com.imin.iminapi.controller.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.event.AttendeeExportService;
import com.imin.iminapi.service.event.SalesDashboardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The attendee CSV is the largest single disclosure of personal data this API
 * performs, and the only one that leaves the platform entirely — and it wrote no
 * record of having happened. An org could not answer "who took a copy of our
 * list, and when", which is where both an Art. 30 record and a leak
 * investigation start.
 */
@Import(TestRateLimitConfig.class)
class AttendeeExportAuditTest {

    private final SalesDashboardService dashboard = mock(SalesDashboardService.class);
    private final AttendeeExportService exportService = mock(AttendeeExportService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final SalesDashboardController sut =
            new SalesDashboardController(dashboard, exportService, audit);

    private static final AuthPrincipal ORGANIZER = new AuthPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());

    @Test
    void exporting_the_attendee_list_writes_an_audit_row_naming_the_event_and_the_size() {
        UUID eventId = UUID.randomUUID();
        when(exportService.toCsv(ORGANIZER, eventId))
                .thenReturn("email,name\nada@example.com,Ada\ngrace@example.com,Grace\n");

        sut.exportAttendees(ORGANIZER, eventId);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ORGANIZER), eq(AuditActions.ATTENDEES_EXPORTED), eq("event"),
                eq(eventId), summary.capture());
        assertThat(summary.getValue()).contains("2 row(s)");
        // The row records that a copy was taken, never a copy of the contents.
        assertThat(summary.getValue()).doesNotContain("ada@example.com");
    }

    /** A request the export service refuses is not an export and must not read as one. */
    @Test
    void a_refused_export_writes_no_row() {
        UUID eventId = UUID.randomUUID();
        when(exportService.toCsv(ORGANIZER, eventId))
                .thenThrow(com.imin.iminapi.security.ApiException.notFound("Event"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> sut.exportAttendees(ORGANIZER, eventId))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class);

        verify(audit, org.mockito.Mockito.never())
                .record(any(), any(), any(), any(), any());
    }
}
