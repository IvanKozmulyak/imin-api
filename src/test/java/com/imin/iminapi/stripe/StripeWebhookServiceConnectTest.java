package com.imin.iminapi.stripe;

import com.stripe.StripeClient;
import com.stripe.events.V2CoreAccountIncludingRequirementsUpdatedEvent;
import com.stripe.model.v2.core.Event;
import com.stripe.model.v2.core.EventNotification;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeWebhookServiceConnectTest {

    @Test
    void v2_requirements_updated_triggers_mirror_with_account_id() throws Exception {
        StripeClient stripeClient = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        StripeProperties props = new StripeProperties();
        props.setWebhookSecretV2("whsec_test");

        EventNotification notif = mock(EventNotification.class);
        when(notif.getType()).thenReturn("v2.core.account.requirements.updated");
        when(notif.getId()).thenReturn("evt_123");
        when(stripeClient.parseEventNotification(any(), any(), any())).thenReturn(notif);

        // Build a real concrete v2 Event subclass so the reflection-based
        // related-object extraction in the webhook code works end-to-end.
        String eventJson = """
            {
              "id": "evt_123",
              "type": "v2.core.account.requirements.updated",
              "related_object": { "id": "acct_777", "type": "v2.core.account",
                                  "url": "/v2/core/accounts/acct_777" }
            }
            """;
        V2CoreAccountIncludingRequirementsUpdatedEvent full =
                com.stripe.net.ApiResource.GSON.fromJson(
                        eventJson, V2CoreAccountIncludingRequirementsUpdatedEvent.class);
        when(stripeClient.v2().core().events().retrieve("evt_123")).thenReturn((Event) full);

        StripeWebhookService svc = new StripeWebhookService(
                stripeClient, props, mock(com.imin.iminapi.repository.PromoCodeRepository.class),
                mock(com.imin.iminapi.service.event.InventoryService.class),
                mock(WebhookEventDedupService.class),
                mock(com.imin.iminapi.service.ticket.PaidCheckoutService.class),
                mock(com.imin.iminapi.refund.RefundService.class));
        svc.setConnectMirror(mirror);

        svc.handleV2Endpoint("{}", "t=1,v1=fake");

        verify(mirror).syncFromStripe(eq("acct_777"));
    }
}
