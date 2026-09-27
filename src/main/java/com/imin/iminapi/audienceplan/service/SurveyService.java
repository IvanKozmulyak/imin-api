package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
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
import com.imin.iminapi.audienceplan.dto.SurveyPageResponse;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.dto.SurveyResponseResult;
import com.imin.iminapi.audienceplan.dto.SurveySettingsResponse;
import com.imin.iminapi.audienceplan.model.SurveyResponse;
import com.imin.iminapi.audienceplan.repository.SurveyResponseRepository;
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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Post-event survey: five optional questions, no identity questions, answers never linked to a person. The
 * unticked box naming the organizer records a separate explicit {@code survey} consent. Never soft opt-in.
 */
@Service
public class SurveyService {

    public static final String SOURCE = "survey";

    static final Set<String> HEARD_FROM = Set.of("friend", "instagram", "tiktok", "facebook", "poster", "imin", "other");
    static final Set<String> AGE_BANDS = Set.of("18_24", "25_34", "35_44", "45_plus");
    static final int COMMUNE_MAX = 80;
    static final int EMAIL_MAX = 254;
    static final int CONSENT_TEXT_MAX = 2000;
    // Letters and the punctuation of place names only: no digits, so no phone or ID numbers.
    private static final Pattern COMMUNE = Pattern.compile("[\\p{L}\\p{M} .'’-]+");

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final ErasedAddressRepository erased;
    private final MarketingOptOutRepository optOuts;
    private final SuppressionRepository suppressions;
    private final SurveyResponseRepository responses;
    private final AudienceOrderProjector projector;
    private final ConsentService consentService;
    private final AudiencePlanAccess access;
    private final AudiencePlanLogic logic;
    private final EmailProperties emailProps;

    public SurveyService(EventRepository events, OrganizationRepository orgs, ConsumerRepository consumers,
                         MembershipRepository memberships, ErasedAddressRepository erased,
                         MarketingOptOutRepository optOuts, SuppressionRepository suppressions,
                         SurveyResponseRepository responses, AudienceOrderProjector projector,
                         ConsentService consentService, AudiencePlanAccess access, AudiencePlanLogic logic,
                         EmailProperties emailProps) {
        this.events = events;
        this.orgs = orgs;
        this.consumers = consumers;
        this.memberships = memberships;
        this.erased = erased;
        this.optOuts = optOuts;
        this.suppressions = suppressions;
        this.responses = responses;
        this.projector = projector;
        this.consentService = consentService;
        this.access = access;
        this.logic = logic;
        this.emailProps = emailProps;
    }

    // ---- organizer ----

    @Transactional(readOnly = true)
    public SurveySettingsResponse settings(AuthPrincipal principal, UUID eventId) {
        return toSettings(loadOwned(principal, eventId));
    }

    /** Switching on for the first time creates the token; switching off keeps it for a later re-enable. */
    @Transactional
    public SurveySettingsResponse setEnabled(AuthPrincipal principal, UUID eventId, boolean enabled) {
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "change the post-event survey");
        Event e = loadOwned(principal, eventId);
        String token = e.getSurveyToken();
        if (enabled && token == null) token = DoorOptInService.newToken();
        events.updateSurvey(e.getId(), enabled, token);
        e.setSurveyEnabled(enabled);
        e.setSurveyToken(token);
        return toSettings(e);
    }

    private SurveySettingsResponse toSettings(Event e) {
        String url = e.getSurveyToken() == null ? null
                : emailProps.getBuyerSiteBaseUrl() + "/e/" + e.getId() + "/survey?t=" + e.getSurveyToken();
        return new SurveySettingsResponse(e.isSurveyEnabled(), url, responses.countByEventId(e.getId()));
    }

    private Event loadOwned(AuthPrincipal principal, UUID eventId) {
        access.requireEnabled(principal.orgId());
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    // ---- public ----

    @Transactional(readOnly = true)
    public SurveyPageResponse page(String token) {
        Event e = requireOpen(token);
        Organization org = requireOrg(e);
        return new SurveyPageResponse(e.getId(), e.getName(), org.getName(), blankToNull(org.getLegalName()),
                blankToNull(org.getLegalContact()), e.getStartsAt(), e.getTimezone(), e.getVenueCity());
    }

    @Transactional
    public SurveyResponseResult submit(String token, SurveyResponseRequest body) {
        Event e = requireOpen(token);
        Organization org = requireOrg(e);
        if (body == null) throw invalid("answers", "answer at least one question");
        rejectUnknown(body);
        String commune = validCommune(body.homeCommune());
        List<String> genres = validGenres(body.otherGenres());
        String heardFrom = oneOf("heardFrom", body.heardFrom(), HEARD_FROM);
        String ageBand = oneOf("ageBand", body.ageBand(), AGE_BANDS);
        if (commune == null && genres.isEmpty() && heardFrom == null && ageBand == null && body.firstTime() == null) {
            throw invalid("answers", "answer at least one question");
        }
        String notice = validNotice(body.noticeVersion());
        String locale = DoorOptInService.locale(body.locale());

        SurveyResponse r = new SurveyResponse();
        r.setOrgId(e.getOrgId());
        r.setEventId(e.getId());
        r.setHomeCommune(commune);
        r.setOtherGenres(genres.isEmpty() ? null : json(genres));
        r.setHeardFrom(heardFrom);
        r.setAgeBand(ageBand);
        r.setFirstTime(body.firstTime());
        r.setNoticeVersion(notice);
        r.setLocale(locale);

        boolean ticked = Boolean.TRUE.equals(body.consentGiven());
        boolean hasEmail = body.email() != null && !body.email().isBlank();
        if (!ticked && hasEmail) throw invalid("consentGiven", "tick the box to leave an email");
        if (ticked) {
            String email = validEmail(body.email());
            String version = validVersion(body.consentTextVersion());
            String text = validText(body.consentText(), org);
            recordConsent(e, email, version, text, locale);
        }
        responses.save(r);
        return SurveyResponseResult.ok();
    }

    /** Records the explicit consent on its own; an erased or opted-out address records nothing. */
    private void recordConsent(Event e, String email, String version, String text, String locale) {
        UUID orgId = e.getOrgId();
        if (erased.existsForOrg(orgId, email) || erased.existsPlatformWide(email)) return;
        if (optOuts.existsById(new MarketingOptOutId(email, orgId, "email"))) return;
        Membership existing = findMembership(orgId, email);
        if (existing != null && ("unsubscribed".equals(existing.getConsentStatus())
                || "erase_pending".equals(existing.getStatus())
                || suppressions.findMarketingByOrgAndMembership(orgId, existing.getMembershipId()).isPresent())) {
            return;
        }
        projector.upsertMembership(orgId, email, null);
        Membership m = findMembership(orgId, email);
        if (m == null) throw new IllegalStateException("Membership missing after survey upsert");
        consentService.capture(orgId, m.getMembershipId(), "explicit", SOURCE,
                "Ticked the survey sign-up at event " + e.getId() + " (locale " + locale + ") next to: \""
                        + text + "\"",
                "email", version, null, e.getId(), ConsentOrigin.DATA_SUBJECT, null);
    }

    /** One 404 for unknown token, deleted, draft, cancelled, unpublished, switched off and the kill switch. */
    private Event requireOpen(String token) {
        Event e = token == null || token.isBlank() ? null : events.findActiveBySurveyToken(token).orElse(null);
        if (e == null || !access.isEnabled(e.getOrgId()) || !e.isSurveyEnabled()
                || e.getPublishedAt() == null || e.getStatus() == EventStatus.DRAFT
                || e.getStatus() == EventStatus.CANCELLED) {
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

    // ---- validation (400 INVALID_REQUEST with a fields map, as the other public endpoints) ----

    private static void rejectUnknown(SurveyResponseRequest body) {
        Map<String, Object> unknown = body.unknownFields();
        if (unknown == null || unknown.isEmpty()) return;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String name : unknown.keySet()) fields.put(name, "is not a survey question");
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid request body", fields);
    }

    private static String validCommune(String raw) {
        String c = raw == null ? "" : Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (c.isEmpty()) return null;
        if (c.length() > COMMUNE_MAX) throw invalid("homeCommune", "must be at most " + COMMUNE_MAX + " characters");
        if (!COMMUNE.matcher(c).matches()) throw invalid("homeCommune", "must be a place name");
        return c;
    }

    private List<String> validGenres(List<String> raw) {
        if (raw == null) return List.of();
        List<String> whitelist = logic.genres().whitelist();
        Set<String> out = new LinkedHashSet<>();
        for (String g : raw) {
            if (g == null || !whitelist.contains(g)) throw invalid("otherGenres", "must be genre bucket keys");
            out.add(g);
        }
        return new ArrayList<>(out);
    }

    private static String oneOf(String field, String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) return null;
        if (!allowed.contains(raw)) throw invalid(field, "is not one of the offered answers");
        return raw;
    }

    private String validNotice(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (!logic.logic().legal().surveyNoticeVersions().contains(v)) {
            throw invalid("noticeVersion", "is not a known survey notice version");
        }
        return v;
    }

    private static String validEmail(String rawEmail) {
        String raw = rawEmail == null ? "" : rawEmail.trim();
        if (raw.isEmpty()) throw invalid("email", "is required when the box is ticked");
        if (raw.length() > EMAIL_MAX) throw invalid("email", "must be at most " + EMAIL_MAX + " characters");
        String email = EmailNormalizer.normalize(raw);
        if (!VALIDATOR.validate(new EmailHolder(email)).isEmpty()) throw invalid("email", "must be a valid email address");
        return email;
    }

    private String validVersion(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (!logic.logic().legal().surveyTextVersions().contains(v)) {
            throw invalid("consentTextVersion", "is not a known survey consent text version");
        }
        return v;
    }

    private static String validText(String raw, Organization org) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) throw invalid("consentText", "is required");
        if (t.length() > CONSENT_TEXT_MAX) throw invalid("consentText", "must be at most " + CONSENT_TEXT_MAX + " characters");
        String name = org.getName() == null ? "" : org.getName().trim();
        if (name.isEmpty() || !fold(t).contains(fold(name))) throw invalid("consentText", "must name the organizer");
        return t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String json(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("genre list not serializable", ex);
        }
    }

    private static ApiException invalid(String field, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid request body", fields);
    }

    private record EmailHolder(@Email String email) {}
}
