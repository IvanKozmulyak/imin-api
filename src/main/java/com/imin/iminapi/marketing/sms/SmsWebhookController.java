package com.imin.iminapi.marketing.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.webhook.ProviderEventDedupService;
import com.imin.iminapi.service.audience.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Inbound SMS webhook (spec §7 opt-out; FR mandatory STOP). Public, unauthenticated,
 * verified by an HMAC signature inside the handler — same contract as the Resend
 * webhook. Reads the raw body via {@link HttpEntity} so the bytes match what was signed.
 *
 * <p>Handles two provider event kinds on one endpoint:
 * <ul>
 *   <li><b>Inbound message (MO)</b> — if the text is a STOP keyword, unsubscribe the
 *       originating number from SMS across all orgs ({@link SmsStopService}).</li>
 *   <li><b>Delivery receipt</b> — logged + deduped for now; recipient-row projection
 *       is wired when the campaign send path is (see the decision doc's wiring step).</li>
 * </ul>
 *
 * <p>Response contract: 200 on accepted OR duplicate (provider must stop retrying),
 * 401 on signature failure (never accept a forged STOP), 200 no-op on anything we
 * can't act on.
 */
@RestController
@RequestMapping("/api/v1/public/webhooks")
public class SmsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SmsWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Opt-out keywords honored on inbound SMS (EN + FR). Case-insensitive. */
    private static final Set<String> STOP_KEYWORDS =
            Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "ARRET", "ARRETER", "ARRÊT", "ARRÊTER");

    private final SmsWebhookSignatureVerifier verifier;
    private final SmsProperties props;
    private final ProviderEventDedupService dedup;
    private final SmsStopService stopService;

    public SmsWebhookController(SmsWebhookSignatureVerifier verifier, SmsProperties props,
                               ProviderEventDedupService dedup, SmsStopService stopService) {
        this.verifier = verifier;
        this.props = props;
        this.dedup = dedup;
        this.stopService = stopService;
    }

    @PostMapping("/sms")
    @Transactional
    public ResponseEntity<Void> receive(HttpEntity<String> entity) {
        String body = entity.getBody() == null ? "" : entity.getBody();
        String signature = entity.getHeaders().getFirst("Messagebird-Signature");

        if (!verifier.verify(props.getWebhookSecret(), body, signature)) {
            log.warn("[sms-webhook] signature verification failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String eventId;
        String type;
        String originator;
        String text;
        try {
            JsonNode root = MAPPER.readTree(body);
            eventId = text(root, "id");
            type = text(root, "type");                 // "mo" (inbound) | "dlr" (receipt)
            originator = text(root, "originator");
            text = text(root, "body");
        } catch (Exception e) {
            log.warn("[sms-webhook] unparseable body — acking to stop retries: {}", e.getMessage());
            return ResponseEntity.ok().build();
        }

        // Idempotent claim on (provider=bird, provider_event_id). Duplicate ⇒ ack + skip.
        boolean fresh = dedup.tryClaim(ProviderEvent.PROVIDER_BIRD, eventId, null,
                null, null, type, body);
        if (!fresh) {
            log.info("[sms-webhook] duplicate eventId={} — acked", eventId);
            return ResponseEntity.ok().build();
        }

        if (isStop(text)) {
            PhoneNormalizer.normalize(originator).ifPresentOrElse(
                    phone -> stopService.suppressPhone(phone, "sms_stop_reply"),
                    () -> log.info("[sms-webhook] STOP from unparseable originator — logged only"));
        } else {
            // Delivery receipts / non-STOP inbound: logged + deduped; projection deferred to wiring.
            log.info("[sms-webhook] non-STOP event type={} — logged", type);
        }
        return ResponseEntity.ok().build();
    }

    private static boolean isStop(String text) {
        if (text == null) return false;
        // Match on the first token so "STOP", "STOP au 36180", "Arrêter" all count.
        String first = text.trim().split("[\\s,.;!]+", 2)[0].toUpperCase();
        return STOP_KEYWORDS.contains(first);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
