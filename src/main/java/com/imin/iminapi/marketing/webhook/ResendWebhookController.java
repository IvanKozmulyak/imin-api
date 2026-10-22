package com.imin.iminapi.marketing.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderDeliveryProjector;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
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
 * keep retrying a valid delivery), 401 on signature failure against both configured
 * secrets (never silently accept a forgery), 503 without claiming the event when it is
 * tagged with a campaign whose recipient row for that address is still queued and the
 * event is under {@code recipient-grace} old (its batch has not committed yet; Svix
 * retries), and 200 when the message id can't be resolved to a recipient. A tagged
 * event whose id resolves no row is matched by campaign and address among the rows
 * that left. A message id with no campaign recipient may be a checkout reminder's,
 * projected by {@link CheckoutReminderDeliveryProjector}; anything else unmatched
 * (transactional mail) only puts a Permanent bounce on the deliverability list.
 *
 * <p>Opens and clicks are projected onto the recipient row like every other type, but claimed by svix id
 * alone (no message, campaign or recipient id), so {@code provider_events} keeps no per-person event log.
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
    private final CheckoutReminderDeliveryProjector reminderProjector;
    private final CampaignRepository campaignRepo;

    public ResendWebhookController(SvixSignatureVerifier verifier,
                                   ResendWebhookProperties props,
                                   ProviderEventDedupService dedup,
                                   ResendWebhookProjector projector,
                                   CampaignRecipientRepository recipientRepo,
                                   CheckoutReminderDeliveryProjector reminderProjector,
                                   CampaignRepository campaignRepo) {
        this.verifier = verifier;
        this.props = props;
        this.dedup = dedup;
        this.projector = projector;
        this.recipientRepo = recipientRepo;
        this.reminderProjector = reminderProjector;
        this.campaignRepo = campaignRepo;
    }

    @PostMapping("/resend")
    @Transactional
    public ResponseEntity<Void> receive(HttpEntity<String> entity) {
        HttpHeaders h = entity.getHeaders();
        String svixId = h.getFirst("svix-id");
        String svixTs = h.getFirst("svix-timestamp");
        String svixSig = h.getFirst("svix-signature");
        String body = entity.getBody() == null ? "" : entity.getBody();

        // Two Resend teams (transactional and marketing) may post here, each signing with its own secret.
        if (!verifier.verify(props.getSecret(), svixId, svixTs, body, svixSig, props.getToleranceSeconds())
                && !verifier.verify(props.getMarketingSecret(), svixId, svixTs, body, svixSig,
                        props.getToleranceSeconds())) {
            log.warn("[resend-webhook] signature verification failed svixId={}", svixId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String type;
        String messageId;
        String email;
        String bounceType;
        String clickLink;
        Instant occurredAt;
        Instant createdAt;
        UUID campaignTag;
        try {
            JsonNode root = MAPPER.readTree(body);
            type = text(root, "type");
            JsonNode data = root.path("data");
            messageId = text(data, "email_id");
            email = data.path("to").isArray() && data.path("to").size() > 0
                    ? data.path("to").get(0).asText(null)
                    : text(data, "to");
            // mkt-edge-6: Resend classifies every bounce as Permanent | Transient |
            // Undetermined in data.bounce.type. Dropping it here was what made the projector
            // treat a full mailbox exactly like a dead address and suppress it for every org
            // on the platform, for ever.
            bounceType = text(data.path("bounce"), "type");
            clickLink = text(data.path("click"), "link");
            createdAt = parseInstantOrNull(text(root, "created_at"));
            occurredAt = createdAt == null ? Instant.now() : createdAt;
            campaignTag = campaignTag(data.path("tags"));
        } catch (Exception e) {
            log.warn("[resend-webhook] unparseable body — acking to stop retries: {}", e.getMessage());
            return ResponseEntity.ok().build(); // malformed but signed — don't loop Resend
        }

        CampaignRecipient r = messageId == null ? null
                : recipientRepo.findByProviderMessageId(messageId).orElse(null);
        if (r == null && campaignTag != null && email != null && !email.isBlank()) {
            String address = EmailNormalizer.normalize(email);
            r = recipientRepo.findLeftByCampaignIdAndEmail(campaignTag, address).orElse(null);
            if (r != null && messageId != null) {
                // A row sent without an id (or before its outcome committed) is found again by campaign and address.
                recipientRepo.attachProviderMessageIdIfNull(r.getId(), messageId);
            } else if (r == null && recipientRepo.existsPendingByCampaignIdAndEmail(campaignTag, address)
                    && withinGrace(createdAt)) {
                // Its batch has not committed yet: unclaimed, so Svix's retry projects it once the row is recorded.
                log.info("[resend-webhook] {} for campaign {} arrived before its batch committed — 503, retried",
                        type, campaignTag);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        // An event no row claims is still filed under its tagged campaign, when that campaign exists, so the breaker counts it.
        UUID taggedCampaign = r != null || campaignTag == null ? null
                : campaignRepo.findById(campaignTag).map(com.imin.iminapi.marketing.model.Campaign::getId).orElse(null);
        UUID recipientId = r == null ? null : r.getId();
        UUID campaignId = r == null ? taggedCampaign : r.getCampaignId();
        UUID membershipId = r == null ? null : r.getMembershipId();

        // Idempotent claim keyed on the svix message id (the provider_event_id).
        boolean engagement = ProviderEvent.TYPE_OPENED.equals(type) || ProviderEvent.TYPE_CLICKED.equals(type);
        boolean fresh = engagement
                ? dedup.tryClaim(ProviderEvent.PROVIDER_RESEND, svixId, null, null, null, type)
                : dedup.tryClaim(ProviderEvent.PROVIDER_RESEND, svixId, messageId, campaignId, recipientId, type);
        if (!fresh) {
            log.info("[resend-webhook] duplicate svixId={} — acked", svixId);
            return ResponseEntity.ok().build();
        }

        CheckoutReminder reminder = r == null ? reminderProjector.find(messageId).orElse(null) : null;
        if (r != null) {
            // org id is derived inside the projector from the campaign (recipient
            // rows carry no org_id — spec §2.2 V53).
            projector.project(campaignId, recipientId, membershipId, email, type, bounceType, occurredAt, clickLink);
        } else if (reminder != null) {
            reminderProjector.project(reminder, email, type, bounceType, occurredAt);
        } else {
            log.info("[resend-webhook] no recipient for messageId={} type={}", messageId, type);
            if (taggedCampaign != null && ProviderEvent.TYPE_COMPLAINED.equals(type)) {
                projector.projectTaggedComplaint(taggedCampaign, email, occurredAt);
            }
            projector.projectUnmatched(type, email, bounceType);
        }
        return ResponseEntity.ok().build();
    }

    private boolean withinGrace(Instant createdAt) {
        // A missing created_at counts as outside the grace: never hold an event back on a guess.
        return createdAt != null && createdAt.isAfter(Instant.now().minus(props.getRecipientGrace()));
    }

    /** {@code data.tags.imin_campaign}: Resend sends tags as an object map; a name/value array is read too. */
    private static UUID campaignTag(JsonNode tags) {
        String value = null;
        if (tags.isObject()) {
            value = text(tags, CampaignEmailProvider.CAMPAIGN_TAG);
        } else if (tags.isArray()) {
            for (JsonNode t : tags) {
                if (CampaignEmailProvider.CAMPAIGN_TAG.equals(text(t, "name"))) value = text(t, "value");
            }
        }
        if (value == null) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Instant parseInstantOrNull(String iso) {
        if (iso == null) return null;
        try { return Instant.parse(iso); }
        catch (DateTimeParseException e) { return null; }
    }
}
