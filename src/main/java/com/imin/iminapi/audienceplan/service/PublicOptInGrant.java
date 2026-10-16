package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentConfirmationService;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The unticked-box sign-up shared by the door QR page and the imin.wtf follow form: request checks that answer 400
 * with a fields map, then an explicit consent that waits for its confirmation email. Never soft opt-in.
 */
@Component
public class PublicOptInGrant {

    static final int EMAIL_MAX = 254;
    static final int CONSENT_TEXT_MAX = 2000;
    static final Set<String> LOCALES = Set.of("en", "es", "fr", "uk");

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final Pattern NOT_PLAIN = Pattern.compile("[\\p{Cc}\\p{Cf}\"]");

    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final ErasedAddressRepository erased;
    private final MarketingOptOutRepository optOuts;
    private final SuppressionRepository suppressions;
    private final ConsentConfirmationTokenRepository tokens;
    private final AudienceOrderProjector projector;
    private final ConsentService consentService;
    private final ConsentConfirmationService confirmations;
    private final Clock clock;

    public PublicOptInGrant(ConsumerRepository consumers, MembershipRepository memberships,
                            ErasedAddressRepository erased, MarketingOptOutRepository optOuts,
                            SuppressionRepository suppressions, ConsentConfirmationTokenRepository tokens,
                            AudienceOrderProjector projector, ConsentService consentService,
                            ConsentConfirmationService confirmations, Clock clock) {
        this.consumers = consumers;
        this.memberships = memberships;
        this.erased = erased;
        this.optOuts = optOuts;
        this.suppressions = suppressions;
        this.tokens = tokens;
        this.projector = projector;
        this.consentService = consentService;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    /**
     * Records an explicit {@code source} consent and asks for its confirmation; an erased, opted-out, unsubscribed,
     * erase-pending or suppressed address stores nothing. Run in the caller's transaction (membership row lock).
     */
    public void grant(UUID orgId, String email, String source, String proofText, String textVersion, UUID eventId,
                      String locale) {
        grant(orgId, email, source, proofText, textVersion, eventId, locale, false);
    }

    /** As above; with {@code holdWhileLinkLive}, stores nothing while this member's {@code source} link still works. */
    public void grant(UUID orgId, String email, String source, String proofText, String textVersion, UUID eventId,
                      String locale, boolean holdWhileLinkLive) {
        // Accepted without storing, same answer as a stored sign-up: an erasure is never undone here.
        if (erased.existsForOrg(orgId, email) || erased.existsPlatformWide(email)) return;
        // Unsubscribed or marketing-suppressed always wins, as at checkout.
        if (optOuts.existsById(new MarketingOptOutId(email, orgId, "email"))) return;
        Membership existing = findMembership(orgId, email);
        if (existing != null && ("unsubscribed".equals(existing.getConsentStatus())
                || "erase_pending".equals(existing.getStatus())
                || suppressions.findMarketingByOrgAndMembership(orgId, existing.getMembershipId()).isPresent())) {
            return;
        }
        // A failed or expired email leaves no live link, so the retry is stored and mailed.
        if (existing != null && holdWhileLinkLive
                && tokens.existsLiveForPendingSource(existing.getMembershipId(), source, clock.instant())) {
            return;
        }

        projector.upsertMembership(orgId, email, null);
        Membership m = findMembership(orgId, email);
        if (m == null) throw new IllegalStateException("Membership missing after " + source + " upsert");
        UUID recordId = consentService.capture(orgId, m.getMembershipId(), "explicit", source, proofText,
                "email", textVersion, null, eventId, ConsentOrigin.DATA_SUBJECT, null);
        confirmations.requestFor(orgId, m.getMembershipId(), recordId, locale);
    }

    /** Row-locked from this first read, so the opt-out checks and the grant see the same row. */
    private Membership findMembership(UUID orgId, String email) {
        Consumer c = consumers.findByNormalizedEmail(email).orElse(null);
        return c == null ? null : memberships.lockByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElse(null);
    }

    // ---- validation (400 INVALID_REQUEST with a fields map, as the other public endpoints) ----

    static String validEmail(String rawEmail) {
        String raw = rawEmail == null ? "" : rawEmail.trim();
        if (raw.isEmpty()) throw invalid("email", "is required");
        if (raw.length() > EMAIL_MAX) throw invalid("email", "must be at most " + EMAIL_MAX + " characters");
        String email = EmailNormalizer.normalize(raw);
        if (!VALIDATOR.validate(new EmailHolder(email)).isEmpty()) throw invalid("email", "must be a valid email address");
        return email;
    }

    static void requireTicked(Boolean consentGiven) {
        if (!Boolean.TRUE.equals(consentGiven)) throw invalid("consentGiven", "the consent box must be ticked");
    }

    /** {@code kind} names the sentence family in the message, e.g. "door". */
    static String validVersion(String raw, Set<String> accepted, String kind) {
        String v = raw == null ? "" : raw.trim();
        if (!accepted.contains(v)) {
            throw invalid("consentTextVersion", "is not a known " + kind + " consent text version");
        }
        return v;
    }

    static String validText(String raw, Organization org) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) throw invalid("consentText", "is required");
        if (t.length() > CONSENT_TEXT_MAX) throw invalid("consentText", "must be at most " + CONSENT_TEXT_MAX + " characters");
        String name = org.getName() == null ? "" : org.getName().trim();
        if (name.isEmpty() || !fold(t).contains(fold(name))) throw invalid("consentText", "must name the organizer");
        return t;
    }

    /** The proof quotes the sentence, so a quote or a control or format character could blur or fake it. */
    static void requirePlainText(String text) {
        if (NOT_PLAIN.matcher(text).find()) throw invalid("consentText", "must not contain quotes or control characters");
    }

    static String locale(String raw) {
        String l = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return LOCALES.contains(l) ? l : "en";
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static ApiException invalid(String field, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid request body", fields);
    }

    private record EmailHolder(@Email String email) {}
}
