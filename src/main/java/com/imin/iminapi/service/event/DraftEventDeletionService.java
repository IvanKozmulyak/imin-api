package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Soft delete: deleted_at is already filtered by every read path; FK children stay intact.
 * Only a draft that was never published and has no orders qualifies.
 */
@Service
public class DraftEventDeletionService {

    private final EventRepository events;
    private final OrderRepository orders;
    private final AuditLogger auditLogger;

    public DraftEventDeletionService(EventRepository events, OrderRepository orders, AuditLogger auditLogger) {
        this.events = events;
        this.orders = orders;
        this.auditLogger = auditLogger;
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public void deleteDraft(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id)
                .filter(ev -> ev.getOrgId().equals(p.orgId()))
                .orElseThrow(() -> ApiException.notFound("Event"));
        if (e.getStatus() != EventStatus.DRAFT) {
            throw ApiException.invalidState("Only a draft can be deleted");
        }
        // Unpublish keeps publishedAt, and such a draft may carry refunded orders.
        if (e.getPublishedAt() != null) {
            throw ApiException.invalidState("A draft that was published before cannot be deleted");
        }
        if (orders.countByEventId(id) > 0) {
            throw ApiException.invalidState("A draft with orders cannot be deleted");
        }
        String label = (e.getName() == null || e.getName().isBlank()) ? "Untitled" : e.getName();
        // Lost race: a publish (or another delete) committed after the read above.
        if (events.softDeleteNeverPublishedDraft(id, p.orgId(), Instant.now().truncatedTo(ChronoUnit.MICROS)) == 0) {
            throw ApiException.invalidState("Only a draft that was never published can be deleted");
        }
        auditLogger.record(p, AuditActions.EVENT_DELETED, "event", id, "Deleted draft \"" + label + "\"");
    }
}
