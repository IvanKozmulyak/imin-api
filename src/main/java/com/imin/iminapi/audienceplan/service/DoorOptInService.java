package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.dto.DoorOptInPageResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.dto.DoorOptInResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInSettingsResponse;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Door QR opt-in: a guest at the event ticks an unticked box naming the organizer and becomes an explicit
 * member (source {@code door_qr}). Never soft opt-in; erased or unsubscribed addresses are accepted and not stored.
 */
@Service
public class DoorOptInService {

    public static final String SOURCE = "door_qr";

    static final int EMAIL_MAX = 254;
    static final int CONSENT_TEXT_MAX = 2000;
    static final Set<String> LOCALES = Set.of("en", "es", "fr", "uk");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final ErasedAddressRepository erased;
    private final MarketingOptOutRepository optOuts;
    private final SuppressionRepository suppressions;
    private final ConsentRecordRepository consentRecords;
    private final AudienceOrderProjector projector;
    private final ConsentService consentService;
    private final AudiencePlanAccess access;
    private final AudiencePlanLogic logic;
    private final EmailProperties emailProps;

    public DoorOptInService(EventRepository events, OrganizationRepository orgs, ConsumerRepository consumers,
                            MembershipRepository memberships, ErasedAddressRepository erased,
                            MarketingOptOutRepository optOuts, SuppressionRepository suppressions,
                            ConsentRecordRepository consentRecords,
                            AudienceOrderProjector projector, ConsentService consentService,
                            AudiencePlanAccess access, AudiencePlanLogic logic, EmailProperties emailProps) {
        this.events = events;
        this.orgs = orgs;
        this.consumers = consumers;
        this.memberships = memberships;
        this.erased = erased;
        this.optOuts = optOuts;
        this.suppressions = suppressions;
        this.consentRecords = consentRecords;
        this.projector = projector;
        this.consentService = consentService;
        this.access = access;
        this.logic = logic;
        this.emailProps = emailProps;
    }

    // ---- organizer ----

    @Transactional(readOnly = true)
    public DoorOptInSettingsResponse settings(AuthPrincipal principal, UUID eventId) {
        return toSettings(loadOwned(principal, eventId));
    }

    /** Switching on for the first time creates the token; switching off keeps it for a later re-enable. */
    @Transactional
    public DoorOptInSettingsResponse setEnabled(AuthPrincipal principal, UUID eventId, boolean enabled) {
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "change door QR sign-up");
        Event e = loadOwned(principal, eventId);
        String token = e.getDoorOptinToken();
        if (enabled && token == null) token = newToken();
        events.updateDoorOptin(e.getId(), enabled, token);
        e.setDoorOptinEnabled(enabled);
        e.setDoorOptinToken(token);
        return toSettings(e);
    }

    private DoorOptInSettingsResponse toSettings(Event e) {
        String url = e.getDoorOptinToken() == null ? null
                : emailProps.getBuyerSiteBaseUrl() + "/e/" + e.getId() + "/door?t=" + e.getDoorOptinToken();
        return new DoorOptInSettingsResponse(e.isDoorOptinEnabled(), url,
                consentRecords.countMembersByEventAndSource(e.getId(), SOURCE));
    }

    private Event loadOwned(AuthPrincipal principal, UUID eventId) {
        access.requireEnabled(principal.orgId());
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    // ---- public ----

    @Transactional(readOnly = true)
    public DoorOptInPageResponse page(UUID eventId, String token) {
        Event e = requireOpen(eventId, token);
        Organization org = requireOrg(e);
        return new DoorOptInPageResponse(e.getId(), e.getName(), org.getName(), e.getStartsAt(), e.getTimezone(),
                e.getVenueCity());
    }

    @Transactional
    public DoorOptInResponse optIn(UUID eventId, DoorOptInRequest body) {
        Event e = requireOpen(eventId, body == null ? null : body.token());
        Organization org = requireOrg(e);
        String email = validEmail(body);
        requireTicked(body);
        String version = validVersion(body);
        String text = validText(body, org);
        String locale = locale(body.locale());

        UUID orgId = e.getOrgId();
        // Accepted without storing, same answer as a stored sign-up: an erasure is never undone here.
        if (erased.existsForOrg(orgId, email) || erased.existsPlatformWide(email)) return DoorOptInResponse.ok();
        // Unsubscribed or marketing-suppressed always wins, as at checkout.
        if (optOuts.existsById(new MarketingOptOutId(email, orgId, "email"))) return DoorOptInResponse.ok();
        Membership existing = findMembership(orgId, email);
        if (existing != null && ("unsubscribed".equals(existing.getConsentStatus())
                || "erase_pending".equals(existing.getStatus())
                || suppressions.findMarketingByOrgAndMembership(orgId, existing.getMembershipId()).isPresent())) {
            return DoorOptInResponse.ok();
        }

        projector.upsertMembership(orgId, email, null);
        Membership m = findMembership(orgId, email);
        if (m == null) throw new IllegalStateException("Membership missing after door upsert");
        consentService.capture(orgId, m.getMembershipId(), "explicit", SOURCE,
                "Ticked the door QR sign-up at event " + e.getId() + " (locale " + locale + ") next to: \""
                        + text + "\"",
                "email", version, null, e.getId(), ConsentOrigin.DATA_SUBJECT, null);
        return DoorOptInResponse.ok();
    }

    /** One 404 for unknown, deleted, draft, cancelled, switched-off, wrong token and the kill switch. */
    private Event requireOpen(UUID eventId, String token) {
        Event e = eventId == null ? null : events.findActive(eventId).orElse(null);
        if (e == null || !access.isEnabled(e.getOrgId()) || !e.isDoorOptinEnabled()
                || e.getPublishedAt() == null || e.getStatus() == EventStatus.DRAFT
                || e.getStatus() == EventStatus.CANCELLED || !tokenMatches(e.getDoorOptinToken(), token)) {
            throw ApiException.notFound("Event");
        }
        return e;
    }

    private Organization requireOrg(Event e) {
        return orgs.findById(e.getOrgId()).orElseThrow(() -> ApiException.notFound("Event"));
    }

    private Membership findMembership(UUID orgId, String email) {
        Consumer c = consumers.findByNormalizedEmail(email).orElse(null);
        return c == null ? null : memberships.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElse(null);
    }

    private static boolean tokenMatches(String expected, String given) {
        if (expected == null || given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- validation (400 INVALID_REQUEST with a fields map, as the other public endpoints) ----

    private static String validEmail(DoorOptInRequest body) {
        String raw = body == null || body.email() == null ? "" : body.email().trim();
        if (raw.isEmpty()) throw invalid("email", "is required");
        if (raw.length() > EMAIL_MAX) throw invalid("email", "must be at most " + EMAIL_MAX + " characters");
        String email = EmailNormalizer.normalize(raw);
        if (!VALIDATOR.validate(new EmailHolder(email)).isEmpty()) throw invalid("email", "must be a valid email address");
        return email;
    }

    private static void requireTicked(DoorOptInRequest body) {
        if (!Boolean.TRUE.equals(body.consentGiven())) throw invalid("consentGiven", "the consent box must be ticked");
    }

    private String validVersion(DoorOptInRequest body) {
        String v = body.consentTextVersion() == null ? "" : body.consentTextVersion().trim();
        if (!logic.logic().legal().doorQrTextVersions().contains(v)) {
            throw invalid("consentTextVersion", "is not a known door consent text version");
        }
        return v;
    }

    private static String validText(DoorOptInRequest body, Organization org) {
        String t = body.consentText() == null ? "" : body.consentText().trim();
        if (t.isEmpty()) throw invalid("consentText", "is required");
        if (t.length() > CONSENT_TEXT_MAX) throw invalid("consentText", "must be at most " + CONSENT_TEXT_MAX + " characters");
        String name = org.getName() == null ? "" : org.getName().trim();
        if (name.isEmpty() || !fold(t).contains(fold(name))) throw invalid("consentText", "must name the organizer");
        return t;
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
