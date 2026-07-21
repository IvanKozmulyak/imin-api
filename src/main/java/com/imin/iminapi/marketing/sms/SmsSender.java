package com.imin.iminapi.marketing.sms;

import com.imin.iminapi.service.audience.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Marketing-SMS send entry point with France compliance + consent baked in
 * (spec §7, and {@code docs/research/sms-provider-decision.md} §France compliance).
 *
 * <p>{@link #send(String, String)} is the MARKETING path. For every call it:
 * <ol>
 *   <li>normalizes {@code to} to E.164 (invalid ⇒ {@link Status#INVALID_NUMBER}, nothing sent);</li>
 *   <li>refuses numbers without explicit, un-revoked SMS consent
 *       ({@link Status#REFUSED_NO_CONSENT}) — the GDPR gate;</li>
 *   <li>appends the mandatory French opt-out mention {@code STOP au 36180} if absent;</li>
 *   <li><b>dry-run when {@link SmsProperties#isEnabled()} is false</b> — logs the
 *       would-be message and returns {@link Status#SENT_DRY_RUN}, never touching the
 *       provider. This is the default while SMS billing is unresolved.</li>
 *   <li>otherwise sends via {@link BirdSmsClient} and maps the result.</li>
 * </ol>
 *
 * <p>A transactional SMS (OTP, ticket delivery) would be a separate path that skips
 * the consent gate and the STOP mention (FR exempts transactional) — not built here.
 *
 * <p>Error contract: consent refusal / invalid number / provider rejection are
 * NON-throwing outcomes (the caller skips that recipient); a transport failure
 * propagates as the {@link BirdSmsClient} {@code ApiException} (retryable).
 */
@Service
public class SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

    /** France marketing SMS must carry this opt-out (36180 is the interoperable short code). */
    static final String FR_STOP_MENTION = "STOP au 36180";

    private final SmsProperties props;
    private final SmsConsentGate consentGate;
    private final BirdSmsClient client;

    public SmsSender(SmsProperties props, SmsConsentGate consentGate, BirdSmsClient client) {
        this.props = props;
        this.consentGate = consentGate;
        this.client = client;
    }

    public enum Status {
        SENT,               // delivered to the provider, accepted
        SENT_DRY_RUN,       // dry-run: logged, provider NOT called
        REFUSED_NO_CONSENT, // no explicit/un-revoked SMS consent for this number
        INVALID_NUMBER,     // could not normalize to E.164
        REJECTED            // provider rejected this message (4xx)
    }

    public record Outcome(Status status, String phoneE164, String providerMessageId, String detail) {}

    /**
     * Send one MARKETING SMS. Never throws for a business refusal (returns the reason
     * in {@link Outcome}); only a provider transport failure throws.
     */
    public Outcome send(String to, String body) {
        Optional<String> normalized = PhoneNormalizer.normalize(to);
        if (normalized.isEmpty()) {
            return new Outcome(Status.INVALID_NUMBER, null, null, "not a valid E.164 number");
        }
        String phone = normalized.get();

        if (!consentGate.canSendMarketing(phone)) {
            log.info("[sms-sender] refused — no explicit SMS consent for {}", mask(phone));
            return new Outcome(Status.REFUSED_NO_CONSENT, phone, null, "no explicit SMS consent");
        }

        String composed = withStopMention(body);

        if (!props.isEnabled()) {
            // Dry-run: the record of a would-be send is this log line + the SENT_DRY_RUN
            // outcome. Nothing hits Bird. This is the default until billing is resolved.
            log.info("[sms-sender] DRY-RUN (imin.sms.api-key blank) → from='{}' to={} body='{}'",
                    props.getSenderId(), mask(phone), composed);
            return new Outcome(Status.SENT_DRY_RUN, phone, null, "dry-run: provider not called");
        }

        BirdSmsClient.Result r = client.send(props.getSenderId(), phone, composed);
        return r.accepted()
                ? new Outcome(Status.SENT, phone, r.providerMessageId(), null)
                : new Outcome(Status.REJECTED, phone, null, r.rejectReason());
    }

    /** Append the FR opt-out mention unless the body already carries a STOP instruction. */
    static String withStopMention(String body) {
        String b = body == null ? "" : body;
        if (b.toUpperCase().contains("STOP")) return b; // author already included one
        String sep = b.isBlank() || b.endsWith(" ") || b.endsWith("\n") ? "" : "\n";
        return b + sep + FR_STOP_MENTION;
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        return phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2);
    }
}
