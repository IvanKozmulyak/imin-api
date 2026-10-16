package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.org.OrgSetupRequest;
import com.imin.iminapi.email.TeamAlertService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserIdentityRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.auth.OrganizerTerms;
import com.imin.iminapi.service.me.ProfileService;
import com.imin.iminapi.util.CountryTimeZones;
import com.imin.iminapi.util.SanctionedCountries;
import com.imin.iminapi.util.Slugger;
import com.imin.iminapi.util.StripeSupportedCountries;
import com.imin.iminapi.util.Times;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;

/** POST /api/v1/org/setup: the owner of a social sign-up gives, once, what email sign-up asks for. */
@Service
public class OrgSetupService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final EventRepository events;
    private final TeamAlertService teamAlerts;

    public OrgSetupService(OrganizationRepository orgs, UserRepository users, UserIdentityRepository identities,
                           EventRepository events, TeamAlertService teamAlerts) {
        this.orgs = orgs;
        this.users = users;
        this.identities = identities;
        this.events = events;
        this.teamAlerts = teamAlerts;
    }

    @Transactional
    public MeResponse complete(AuthPrincipal p, OrgSetupRequest req) {
        if (p.role() != UserRole.OWNER) {
            throw ApiException.forbidden("Only the organization's owner can finish setting it up");
        }
        String country = req.country().trim().toUpperCase();
        SanctionedCountries.requireAllowed(country);
        StripeSupportedCountries.requireSupported(country);
        String termsVersion = OrganizerTerms.accepted(req.termsVersion());
        String timezone = timezone(req.timezone(), country);

        Organization current = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        String orgName = req.orgName().trim();
        // A slug that has been on a published event may sit in public links, so it is kept.
        String slug = events.existsByOrgIdAndPublishedAtIsNotNull(current.getId())
                ? current.getSlug() : freeSlug(current, orgName);
        int completed;
        try {
            completed = orgs.completeSetup(current.getId(), orgName, slug, country, timezone, Times.nowMicros());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Organization slug conflict, please retry");
        }
        // The conditional write is the exactly-once decision: nothing changed means another setup got there first.
        if (completed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "This organization is already set up");
        }

        User user = users.findById(p.userId()).orElseThrow(() -> ApiException.notFound("User"));
        String firstName = req.firstName().trim();
        String lastName = req.lastName().trim();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setAvatarInitials(ProfileService.deriveInitials(firstName, lastName));
        // Recorded exactly as email sign-up records it: the matched server id, never the client's text.
        user.setTermsAcceptedAt(Instant.now());
        user.setTermsVersion(termsVersion);
        users.save(user);
        Organization org = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));

        // The one new-organizer team alert for a social sign-up; email sign-ups alert on verification instead.
        teamAlerts.newOrganizer(user, org, signUpMethod(user));
        return new MeResponse(UserDto.from(user), OrganizationDto.from(org));
    }

    /** An explicit IANA zone id wins (a fixed offset such as +02:00 is refused); else the country's primary zone. */
    private static String timezone(String requested, String country) {
        if (requested == null || requested.isBlank()) {
            return CountryTimeZones.zoneFor(country).orElse("UTC");
        }
        String zone = requested.trim();
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            throw fieldInvalid("timezone", "must be an IANA time zone, e.g. Europe/Paris");
        }
        return zone;
    }

    /** Same scheme as sign-up (base, base-2..base-10, then a random suffix); the org's own slug counts as free. */
    private String freeSlug(Organization org, String orgName) {
        String base = Slugger.slugify(orgName);
        if (base.equals(org.getSlug()) || !orgs.existsBySlug(base)) return base;
        for (int n = 2; n <= 10; n++) {
            String candidate = base + "-" + n;
            if (candidate.equals(org.getSlug()) || !orgs.existsBySlug(candidate)) return candidate;
        }
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return base + "-" + HexFormat.of().formatHex(bytes);
    }

    private String signUpMethod(User user) {
        return identities.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .map(i -> switch (i.getProvider()) {
                    case "google" -> "Google";
                    case "apple" -> "Apple";
                    default -> i.getProvider();
                })
                .orElse("Social sign-in");
    }

    private static ApiException fieldInvalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                Map.of(field, message));
    }
}
