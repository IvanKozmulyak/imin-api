package com.imin.iminapi.marketing.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Resend delivery webhook receiver (spec §2.4/§2.5). No auth — verified by the
 * Svix signature inside the handler. Reads the raw body via HttpEntity so the
 * bytes exactly match what was signed (same reason as StripeWebhookController).
 *
 * <p>Response contract: 200 on an accepted OR duplicate event (Resend must not
 * keep retrying a valid delivery), 401 on signature failure (never silently
 * accept a forgery), 200 (no-op) when the message id can't be resolved to a
 * recipient (out-of-band or already-purged row — nothing to project).
 */
@RestController
@RequestMapping("/api/v1/public/webhooks")
public class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SvixSignatureVerifier verifier;
    private final ResendWebhookProperties props;
    private final ProviderEventDedupService dedup;
    private final ResendWebhookProjector projector;
    private final CampaignRecipientRepository recipientRepo;

    public ResendWebhookController(SvixSignatureVerifier verifier,
                                   ResendWebhookProperties props,
                                   ProviderEventDedupService dedup,
                                   ResendWebhookProjector projector,
                                   CampaignRecipientRepository recipientRepo) {
        this.verifier = verifier;
        this.props = props;
        this.dedup = dedup;
        this.projector = projector;
        this.recipientRepo = recipientRepo;
    }

    @PostMapping("/resend")
    @Transactional
    public ResponseEntity<Void> receive(HttpEntity<String> entity) {
        HttpHeaders h = entity.getHeaders();
        String svixId = h.getFirst("svix-id");
        String svixTs = h.getFirst("svix-timestamp");
        String svixSig = h.getFirst("svix-signature");
        String body = entity.getBody() == null ? "" : entity.getBody();

        if (!verifier.verify(props.getSecret(), svixId, svixTs, body, svixSig, props.getToleranceSeconds())) {
            log.warn("[resend-webhook] signature verification failed svixId={}", svixId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String type;
        String messageId;
        String email;
        Instant occurredAt;
        try {
            JsonNode root = MAPPER.readTree(body);
            type = text(root, "type");
            JsonNode data = root.path("data");
            messageId = text(data, "email_id");
            email = data.path("to").isArray() && data.path("to").size() > 0
                    ? data.path("to").get(0).asText(null)
                    : text(data, "to");
            occurredAt = parseInstant(text(root, "created_at"));
        } catch (Exception e) {
            log.warn("[resend-webhook] unparseable body — acking to stop retries: {}", e.getMessage());
            return ResponseEntity.ok().build(); // malformed but signed — don't loop Resend
        }

        CampaignRecipient r = messageId == null ? null
                : recipientRepo.findByProviderMessageId(messageId).orElse(null);
        UUID recipientId = r == null ? null : r.getId();
        UUID campaignId = r == null ? null : r.getCampaignId();
        UUID membershipId = r == null ? null : r.getMembershipId();

        // Idempotent claim keyed on the svix message id (the provider_event_id).
        boolean fresh = dedup.tryClaim(ProviderEvent.PROVIDER_RESEND, svixId, messageId,
                campaignId, recipientId, type, body);
        if (!fresh) {
            log.info("[resend-webhook] duplicate svixId={} — acked", svixId);
            return ResponseEntity.ok().build();
        }

        if (r != null) {
            // org id is derived inside the projector from the campaign (recipient
            // rows carry no org_id — spec §2.2 V53).
            projector.project(campaignId, recipientId, membershipId, email, type, occurredAt);
        } else {
            log.info("[resend-webhook] no recipient for messageId={} type={} — logged only", messageId, type);
        }
        return ResponseEntity.ok().build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) return Instant.now();
        try { return Instant.parse(iso); }
        catch (DateTimeParseException e) { return Instant.now(); }
    }
}
