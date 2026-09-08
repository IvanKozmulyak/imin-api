package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.settlement.SettlementRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrgServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    TicketRepository tickets = mock(TicketRepository.class);
    SettlementRepository settlements = mock(SettlementRepository.class);
    PayoutRunRepository payouts = mock(PayoutRunRepository.class);
    AuditLogger audit = mock(AuditLogger.class);
    IfMatchSupport ifMatch = new IfMatchSupport();
    OrgService sut = new OrgService(orgs, ifMatch, orders, tickets, settlements, payouts, audit);

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
    void patch_to_sanctioned_country_throws_COUNTRY_NOT_ALLOWED() {
        UUID orgId = UUID.randomUUID();
        Organization o = new Organization();
        o.setId(orgId); o.setName("Old"); o.setContactEmail("a@b.com"); o.setCountry("GB");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        o.setUpdatedAt(updated);
        when(orgs.findById(orgId)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> sut.patch(owner(orgId), "\"" + updated + "\"",
                new OrgPatchRequest(null, null, "IR", null)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.COUNTRY_NOT_ALLOWED);
        verify(orgs, never()).save(any(Organization.class));
    }

    @Test
    void patch_to_unsupported_country_throws_COUNTRY_NOT_SUPPORTED() {
        UUID orgId = UUID.randomUUID();
        Organization o = new Organization();
        o.setId(orgId); o.setName("Old"); o.setContactEmail("a@b.com"); o.setCountry("GB");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        o.setUpdatedAt(updated);
        when(orgs.findById(orgId)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> sut.patch(owner(orgId), "\"" + updated + "\"",
                new OrgPatchRequest(null, null, "UA", null)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.COUNTRY_NOT_SUPPORTED);
        verify(orgs, never()).save(any(Organization.class));
    }

    @Test
    void patch_keeping_grandfathered_unsupported_country_succeeds() {
        UUID orgId = UUID.randomUUID();
        Organization o = new Organization();
        o.setId(orgId); o.setName("Old"); o.setContactEmail("a@b.com"); o.setCountry("UA");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        o.setUpdatedAt(updated);
        when(orgs.findById(orgId)).thenReturn(Optional.of(o));
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        OrganizationDto dto = sut.patch(owner(orgId), "\"" + updated + "\"",
                new OrgPatchRequest("New Name", null, "UA", null));
        assertThat(dto.name()).isEqualTo("New Name");
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

    /**
     * organizations → events → orders → tickets are all ON DELETE CASCADE
     * (V6:3, V24:15), so one call used to destroy every buyer's ticket and every
     * record imin is required to keep for tax. Refuse instead.
     */
    @Test
    void delete_is_refused_once_the_org_has_taken_an_order() {
        UUID orgId = UUID.randomUUID();
        when(orgs.existsById(orgId)).thenReturn(true);
        when(orders.existsByOrgId(orgId)).thenReturn(true);

        assertThatThrownBy(() -> sut.delete(owner(orgId)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.ORG_HAS_RECORDS)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.CONFLICT);
        verify(orgs, never()).deleteById(any());
    }

    @Test
    void delete_is_refused_once_a_ticket_exists() {
        UUID orgId = UUID.randomUUID();
        when(orgs.existsById(orgId)).thenReturn(true);
        when(tickets.existsByOrgId(orgId)).thenReturn(true);

        assertThatThrownBy(() -> sut.delete(owner(orgId)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.ORG_HAS_RECORDS);
        verify(orgs, never()).deleteById(any());
    }

    @Test
    void delete_is_refused_once_money_has_moved() {
        UUID orgId = UUID.randomUUID();
        when(orgs.existsById(orgId)).thenReturn(true);
        when(settlements.existsByOrgId(orgId)).thenReturn(true);

        assertThatThrownBy(() -> sut.delete(owner(orgId)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.ORG_HAS_RECORDS);

        reset(orgs, settlements);
        when(orgs.existsById(orgId)).thenReturn(true);
        when(payouts.existsByOrgId(orgId)).thenReturn(true);

        assertThatThrownBy(() -> sut.delete(owner(orgId)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.ORG_HAS_RECORDS);
        verify(orgs, never()).deleteById(any());
    }

    /** A destructive, irreversible action with no trail is not auditable. */
    @Test
    void a_successful_delete_writes_an_audit_row() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        when(orgs.existsById(orgId)).thenReturn(true);

        sut.delete(p);

        verify(audit).record(eq(p), eq(AuditActions.ORG_DELETED), eq("org"), eq(orgId), anyString());
    }

    @Test
    void a_refused_delete_writes_no_audit_row() {
        UUID orgId = UUID.randomUUID();
        when(orgs.existsById(orgId)).thenReturn(true);
        when(orders.existsByOrgId(orgId)).thenReturn(true);

        assertThatThrownBy(() -> sut.delete(owner(orgId))).isNotNull();
        verifyNoInteractions(audit);
    }
}
