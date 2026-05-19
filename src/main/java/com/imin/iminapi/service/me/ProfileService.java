package com.imin.iminapi.service.me;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.me.ProfilePatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ProfileService {

    /** Mirrors the DB column width of users.first_name / users.last_name (V14). */
    public static final int MAX_NAME_LENGTH = 255;

    private final UserRepository users;
    private final OrganizationRepository orgs;

    public ProfileService(UserRepository users, OrganizationRepository orgs) {
        this.users = users;
        this.orgs = orgs;
    }

    @Transactional
    public MeResponse patch(AuthPrincipal p, ProfilePatchRequest body) {
        User user = users.findById(p.userId())
                .orElseThrow(() -> ApiException.notFound("User"));

        boolean changed = false;
        if (body.firstName() != null) {
            String trimmed = body.firstName().trim();
            requireNonEmpty(trimmed, "firstName");
            requireWithinLimit(trimmed, "firstName");
            if (!trimmed.equals(user.getFirstName())) {
                user.setFirstName(trimmed);
                changed = true;
            }
        }
        if (body.lastName() != null) {
            String trimmed = body.lastName().trim();
            requireNonEmpty(trimmed, "lastName");
            requireWithinLimit(trimmed, "lastName");
            if (!trimmed.equals(user.getLastName())) {
                user.setLastName(trimmed);
                changed = true;
            }
        }

        if (changed) {
            user.setAvatarInitials(deriveInitials(user.getFirstName(), user.getLastName()));
            users.save(user);
        }

        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> ApiException.notFound("Organization"));
        return new MeResponse(UserDto.from(user), OrganizationDto.from(org));
    }

    private static void requireNonEmpty(String value, String field) {
        if (value.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Validation failed", Map.of(field, "must not be blank"));
        }
    }

    private static void requireWithinLimit(String value, String field) {
        if (value.length() > MAX_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Validation failed", Map.of(field, "must be at most " + MAX_NAME_LENGTH + " characters"));
        }
    }

    /** Two-letter initials from first + last name. Mirrors AuthService.deriveInitials. */
    static String deriveInitials(String firstName, String lastName) {
        StringBuilder sb = new StringBuilder(2);
        appendInitial(sb, firstName);
        appendInitial(sb, lastName);
        return sb.toString().toUpperCase();
    }

    private static void appendInitial(StringBuilder sb, String s) {
        if (sb.length() >= 2 || s == null) return;
        String t = s.trim();
        if (!t.isEmpty()) sb.append(t.charAt(0));
    }
}
