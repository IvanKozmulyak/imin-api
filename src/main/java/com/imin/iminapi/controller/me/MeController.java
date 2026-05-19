package com.imin.iminapi.controller.me;

import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.me.NotificationPrefsPatchRequest;
import com.imin.iminapi.dto.me.ProfilePatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.me.NotificationPrefsService;
import com.imin.iminapi.service.me.ProfileService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final NotificationPrefsService prefs;
    private final ProfileService profile;

    public MeController(NotificationPrefsService prefs, ProfileService profile) {
        this.prefs = prefs;
        this.profile = profile;
    }

    @GetMapping("/notifications")
    public NotificationPreferencesDto get(@CurrentUser AuthPrincipal p) { return prefs.get(p); }

    @PatchMapping("/notifications")
    public NotificationPreferencesDto patch(@CurrentUser AuthPrincipal p, @RequestBody NotificationPrefsPatchRequest body) {
        return prefs.patch(p, body);
    }

    @PatchMapping("/profile")
    public MeResponse patchProfile(@CurrentUser AuthPrincipal p, @RequestBody ProfilePatchRequest body) {
        return profile.patch(p, body);
    }
}
