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
