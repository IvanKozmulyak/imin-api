package com.imin.iminapi.stripe;

import com.imin.iminapi.security.ApiException;
import com.stripe.StripeClient;
import com.stripe.events.V2CoreAccountIncludingRequirementsUpdatedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.v2.core.Event;
import com.stripe.model.v2.core.EventNotification;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeWebhookServiceConnectTest {

    /**
     * Stripe delivers v2 core account events with BRACKET notation in event.type
     * (verified against docs.stripe.com/api/v2/core/accounts/event-types). The dispatcher
     * must match these literal strings — the old dot-notation match never fired, so the
     * Connect mirror was never driven by a real webhook.
     */
    @Test
    void v2_requirements_updated_bracket_triggers_mirror_with_account_id() throws Exception {
        StripeClient stripeClient = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        StripeProperties props = new StripeProperties();
        props.setWebhookSecretV2("whsec_test");

        EventNotification notif = mock(EventNotification.class);
        when(notif.getType()).thenReturn("v2.core.account[requirements].updated");
        when(notif.getId()).thenReturn("evt_123");
        when(stripeClient.parseEventNotification(any(), any(), any())).thenReturn(notif);

        String eventJson = """
            {
              "id": "evt_123",
              "type": "v2.core.account[requirements].updated",
              "related_object": { "id": "acct_777", "type": "v2.core.account",
                                  "url": "/v2/core/accounts/acct_777" }
            }
            """;
        V2CoreAccountIncludingRequirementsUpdatedEvent full =
                com.stripe.net.ApiResource.GSON.fromJson(
                        eventJson, V2CoreAccountIncludingRequirementsUpdatedEvent.class);
        when(stripeClient.v2().core().events().retrieve("evt_123")).thenReturn((Event) full);

        newService(stripeClient, props, mirror).handleV2Endpoint("{}", "t=1,v1=fake");

        verify(mirror).syncFromStripe(eq("acct_777"));
    }

    /** The bracketed capability-status event must also drive the mirror. */
    @Test
    void v2_capability_status_updated_bracket_triggers_mirror() throws Exception {
        StripeClient stripeClient = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        StripeProperties props = new StripeProperties();
        props.setWebhookSecretV2("whsec_test");

        EventNotification notif = mock(EventNotification.class);
        when(notif.getType()).thenReturn("v2.core.account[configuration.recipient].capability_status_updated");
        when(notif.getId()).thenReturn("evt_cap");
        when(stripeClient.parseEventNotification(any(), any(), any())).thenReturn(notif);

        String eventJson = """
            {
              "id": "evt_cap",
              "type": "v2.core.account[requirements].updated",
              "related_object": { "id": "acct_888", "type": "v2.core.account",
                                  "url": "/v2/core/accounts/acct_888" }
            }
            """;
        V2CoreAccountIncludingRequirementsUpdatedEvent full =
                com.stripe.net.ApiResource.GSON.fromJson(
                        eventJson, V2CoreAccountIncludingRequirementsUpdatedEvent.class);
        when(stripeClient.v2().core().events().retrieve("evt_cap")).thenReturn((Event) full);

        newService(stripeClient, props, mirror).handleV2Endpoint("{}", "t=1,v1=fake");

        verify(mirror).syncFromStripe(eq("acct_888"));
    }

    /**
     * A failure fetching the full event must surface as a non-2xx (so Stripe retries the
     * delivery) rather than being swallowed with a 200 ack — otherwise the account-state
     * change is silently dropped.
     */
    @Test
    void v2_retrieve_failure_rethrows_so_stripe_retries() throws Exception {
        StripeClient stripeClient = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        StripeProperties props = new StripeProperties();
        props.setWebhookSecretV2("whsec_test");

        EventNotification notif = mock(EventNotification.class);
        when(notif.getType()).thenReturn("v2.core.account[requirements].updated");
        when(notif.getId()).thenReturn("evt_boom");
        when(stripeClient.parseEventNotification(any(), any(), any())).thenReturn(notif);

        StripeException boom = mock(StripeException.class);
        when(stripeClient.v2().core().events().retrieve("evt_boom")).thenThrow(boom);

        StripeWebhookService svc = newService(stripeClient, props, mirror);

        assertThatThrownBy(() -> svc.handleV2Endpoint("{}", "t=1,v1=fake"))
                .isInstanceOf(ApiException.class);
        verify(mirror, never()).syncFromStripe(any());
    }

    private static StripeWebhookService newService(StripeClient stripeClient,
                                                   StripeProperties props,
                                                   StripeConnectStatusMirror mirror) {
        StripeWebhookService svc = new StripeWebhookService(
                stripeClient, props, mock(com.imin.iminapi.repository.PromoCodeRepository.class),
                mock(com.imin.iminapi.service.event.InventoryService.class),
                mock(WebhookEventDedupService.class),
                mock(com.imin.iminapi.service.ticket.PaidCheckoutService.class),
                mock(com.imin.iminapi.refund.RefundService.class),
                mock(SettlementIngestService.class));
        svc.setConnectMirror(mirror);
        return svc;
    }
}
