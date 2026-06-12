package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.BrandBookDto;
import com.imin.iminapi.dto.org.BrandUpdateRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrgBrandServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    OrgBrandService sut = new OrgBrandService(orgs);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Organization org(UUID id) {
        Organization o = new Organization();
        o.setId(id); o.setName("Org"); o.setContactEmail("a@b.com"); o.setCountry("DE");
        return o;
    }

    private void stub(Organization o) {
        when(orgs.findById(o.getId())).thenReturn(Optional.of(o));
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void get_returns_current_brand() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        o.setBrandName("Tortuga Collective");
        o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899")));
        o.setBrandLogoOnPosters(false);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        BrandBookDto dto = sut.get(owner(id));
        assertThat(dto.brandName()).isEqualTo("Tortuga Collective");
        assertThat(dto.accentColors()).containsExactly("#ec4899");
        assertThat(dto.logoOnPosters()).isFalse();
    }

    @Test
    void put_replaces_fields_and_normalizes_hex_to_lowercase() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        stub(o);

        BrandBookDto dto = sut.put(owner(id),
                new BrandUpdateRequest("  Tortuga Collective  ",
                        List.of("#EC4899", "#F6C04A", "#A78BFA"), true));

        assertThat(dto.brandName()).isEqualTo("Tortuga Collective"); // trimmed
        assertThat(dto.accentColors()).containsExactly("#ec4899", "#f6c04a", "#a78bfa"); // lowercased, order kept
        assertThat(dto.logoOnPosters()).isTrue();
    }

    @Test
    void put_blank_name_becomes_null() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        stub(o);

        BrandBookDto dto = sut.put(owner(id), new BrandUpdateRequest("   ", List.of(), false));
        assertThat(dto.brandName()).isNull();
    }

    @Test
    void put_empty_list_clears_palette() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899")));
        stub(o);

        BrandBookDto dto = sut.put(owner(id), new BrandUpdateRequest(null, List.of(), true));
        assertThat(dto.accentColors()).isEmpty();
    }

    @Test
    void put_case_insensitive_dedupe_keeps_first_occurrence() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        stub(o);

        BrandBookDto dto = sut.put(owner(id),
                new BrandUpdateRequest(null, List.of("#EC4899", "#ec4899", "#a78bfa"), true));
        assertThat(dto.accentColors()).containsExactly("#ec4899", "#a78bfa");
    }

    @Test
    void put_more_than_three_colours_throws_indexed_field_error() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> sut.put(owner(id),
                new BrandUpdateRequest(null,
                        List.of("#111111", "#222222", "#333333", "#444444"), true)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID)
                .extracting("fields").asInstanceOf(map(String.class, String.class))
                .containsKey("accentColors");
        verify(orgs, never()).save(any(Organization.class));
    }

    @Test
    void put_invalid_hex_throws_per_index_field_key() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> sut.put(owner(id),
                new BrandUpdateRequest(null, List.of("#ec4899", "ec4899", "#a78bfa"), true)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID)
                .extracting("fields").asInstanceOf(map(String.class, String.class))
                .containsKey("accentColors[1]");
        verify(orgs, never()).save(any(Organization.class));
    }

    @Test
    void put_rejects_three_digit_and_named_and_rgb() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> sut.put(owner(id),
                new BrandUpdateRequest(null, List.of("#fff"), true)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sut.put(owner(id),
                new BrandUpdateRequest(null, List.of("red"), true)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sut.put(owner(id),
                new BrandUpdateRequest(null, List.of("rgb(0,0,0)"), true)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void put_null_logoOnPosters_defaults_true() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        stub(o);

        BrandBookDto dto = sut.put(owner(id), new BrandUpdateRequest(null, List.of(), null));
        assertThat(dto.logoOnPosters()).isTrue();
    }

    @Test
    void put_name_over_120_throws_field_error() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        when(orgs.findById(id)).thenReturn(Optional.of(o));

        String tooLong = "x".repeat(121);
        assertThatThrownBy(() -> sut.put(owner(id), new BrandUpdateRequest(tooLong, List.of(), true)))
                .isInstanceOf(ApiException.class)
                .extracting("fields").asInstanceOf(map(String.class, String.class))
                .containsKey("brandName");
    }

    @Test
    void clearLogoUrl_nulls_the_logo_only() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        o.setBrandLogoUrl("https://cdn/logo.png");
        o.setBrandLogoOnPosters(true);
        stub(o);

        sut.clearLogoUrl(owner(id));
        assertThat(o.getBrandLogoUrl()).isNull();
        assertThat(o.isBrandLogoOnPosters()).isTrue(); // toggle untouched
    }

    @Test
    void setLogoUrl_persists_url() {
        UUID id = UUID.randomUUID();
        Organization o = org(id);
        stub(o);

        sut.setLogoUrl(owner(id), "https://cdn/logo-ab12cd34.png");
        assertThat(o.getBrandLogoUrl()).isEqualTo("https://cdn/logo-ab12cd34.png");
    }
}
