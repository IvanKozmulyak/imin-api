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
