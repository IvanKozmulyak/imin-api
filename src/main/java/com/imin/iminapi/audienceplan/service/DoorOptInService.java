package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.repository.ConsentRecordRepository;
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
import com.imin.iminapi.security.RoleGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Door QR opt-in: a guest at the event ticks an unticked box naming the organizer and becomes an explicit
 * member (source {@code door_qr}). Never soft opt-in; erased or unsubscribed addresses are accepted and not stored.
 */
@Service
public class DoorOptInService {

    public static final String SOURCE = "door_qr";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final ConsentRecordRepository consentRecords;
    private final AudiencePlanAccess access;
    private final AudiencePlanLogic logic;
    private final EmailProperties emailProps;
    private final PublicOptInGrant grant;

    public DoorOptInService(EventRepository events, OrganizationRepository orgs, ConsentRecordRepository consentRecords,
                            AudiencePlanAccess access, AudiencePlanLogic logic, EmailProperties emailProps,
                            PublicOptInGrant grant) {
        this.events = events;
        this.orgs = orgs;
        this.consentRecords = consentRecords;
        this.access = access;
        this.logic = logic;
        this.emailProps = emailProps;
        this.grant = grant;
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
        String email = PublicOptInGrant.validEmail(body == null ? null : body.email());
        PublicOptInGrant.requireTicked(body.consentGiven());
        String version = PublicOptInGrant.validVersion(body.consentTextVersion(),
                logic.logic().legal().doorQrTextVersions(), "door");
        String text = PublicOptInGrant.validText(body.consentText(), org);
        String locale = locale(body.locale());

        grant.grant(e.getOrgId(), email, SOURCE,
                "Ticked the door QR sign-up at event " + e.getId() + " (locale " + locale + ") next to: \""
                        + text + "\"",
                version, e.getId(), locale);
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

    private static boolean tokenMatches(String expected, String given) {
        if (expected == null || given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String locale(String raw) {
        return PublicOptInGrant.locale(raw);
    }
}
