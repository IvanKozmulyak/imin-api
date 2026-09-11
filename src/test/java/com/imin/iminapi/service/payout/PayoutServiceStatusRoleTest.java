package com.imin.iminapi.service.payout;

import com.imin.iminapi.controller.payout.dto.PayoutsStatusResponse;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.stripe.StripeConnectState;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code dashboardUrl} is an Express login link — a bearer credential to the org's Stripe
 * dashboard. It is minted for ADMIN+ only; the rest of the status payload stays
 * MEMBER-readable, and the shape is unchanged (the field is already nullable).
 */
class PayoutServiceStatusRoleTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ACCT = "acct_role_test";

    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final StripeConnectService connect = mock(StripeConnectService.class);
    private final StripeClient stripeClient = mock(StripeClient.class);

    private PayoutService readyService(AuthPrincipal p) {
        PayoutService sut = spy(new PayoutService(settlements, events, connect, stripeClient));
        when(connect.getStatus(p, ORG)).thenReturn(new StripeConnectService.StatusResult(
                ACCT, StripeConnectState.ACTIVE, true, true, List.of(), List.of(), null));
        doReturn("4242").when(sut).fetchAccountLast4(anyString());
        doReturn("https://connect.stripe.com/express/login").when(sut).fetchDashboardUrl(anyString());
        return sut;
    }

    @Test
    void memberGetsNullDashboardUrl() {
        AuthPrincipal member = new AuthPrincipal(UUID.randomUUID(), ORG, UserRole.MEMBER, UUID.randomUUID());
        PayoutService sut = readyService(member);

        PayoutsStatusResponse status = sut.status(member, ORG);

        assertThat(status.dashboardUrl()).isNull();
        verify(sut, never()).fetchDashboardUrl(anyString());
        // The rest of the read is unaffected.
        assertThat(status.stripeConnected()).isTrue();
        assertThat(status.accountLast4()).isEqualTo("4242");
    }

    @Test
    void adminGetsDashboardUrl() {
        AuthPrincipal admin = new AuthPrincipal(UUID.randomUUID(), ORG, UserRole.ADMIN, UUID.randomUUID());
        PayoutService sut = readyService(admin);

        PayoutsStatusResponse status = sut.status(admin, ORG);

        assertThat(status.dashboardUrl()).isEqualTo("https://connect.stripe.com/express/login");
        assertThat(status.accountLast4()).isEqualTo("4242");
    }
}
