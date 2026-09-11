package com.imin.iminapi.stripe;

import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Role gate on the three Stripe Connect POSTs. They mint onboarding surfaces that bind
 * the org's legal entity and bank details to Stripe; before this guard any MEMBER of the
 * org could open them. {@code GET /status} deliberately stays MEMBER-readable.
 */
class StripeConnectControllerTest {

    private final StripeConnectService connect = mock(StripeConnectService.class);
    private final StripeConnectController controller = new StripeConnectController(connect);

    private final UUID orgId = UUID.randomUUID();
    private final AuthPrincipal member =
            new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.MEMBER, UUID.randomUUID());
    private final AuthPrincipal admin =
            new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.ADMIN, UUID.randomUUID());

    @Test
    void memberCannotMintOnboardingLink() {
        assertThatThrownBy(() -> controller.onboardingLink(member, orgId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
                });

        verifyNoInteractions(connect);
    }

    @Test
    void memberCannotConnect() {
        assertThatThrownBy(() -> controller.connect(member, orgId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(connect);
    }

    @Test
    void memberCannotMintAccountSession() {
        assertThatThrownBy(() -> controller.accountSession(member, orgId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(connect);
    }

    @Test
    void adminCanMintOnboardingLink() {
        when(connect.createOnboardingLink(eq(admin), eq(orgId), any(), any()))
                .thenReturn("https://connect.stripe.com/setup/s/abc");

        assertThat(controller.onboardingLink(admin, orgId, null).url())
                .isEqualTo("https://connect.stripe.com/setup/s/abc");
    }

    @Test
    void adminCanConnectAndMintAccountSession() {
        when(connect.getOrCreateAccount(admin, orgId))
                .thenReturn(new StripeConnectService.ConnectResult("acct_123", true));
        when(connect.createAccountSession(admin, orgId)).thenReturn("cs_secret");

        assertThat(controller.connect(admin, orgId).accountId()).isEqualTo("acct_123");
        assertThat(controller.connect(admin, orgId).created()).isTrue();
        assertThat(controller.accountSession(admin, orgId).clientSecret()).isEqualTo("cs_secret");
    }

    @Test
    void memberCanStillReadStatus() {
        when(connect.getStatus(member, orgId)).thenReturn(new StripeConnectService.StatusResult(
                "acct_123", StripeConnectState.ACTIVE, true, true, List.of(), List.of(), null));

        var status = controller.status(member, orgId);

        assertThat(status.accountId()).isEqualTo("acct_123");
        assertThat(status.readyToReceivePayments()).isTrue();
    }
}
