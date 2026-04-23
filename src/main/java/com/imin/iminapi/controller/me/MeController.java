package com.imin.iminapi.controller.me;

import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.dto.me.NotificationPrefsPatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.me.NotificationPrefsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final NotificationPrefsService prefs;

    public MeController(NotificationPrefsService prefs) { this.prefs = prefs; }

    @GetMapping("/notifications")
    public NotificationPreferencesDto get(@CurrentUser AuthPrincipal p) { return prefs.get(p); }

    @PatchMapping("/notifications")
    public NotificationPreferencesDto patch(@CurrentUser AuthPrincipal p, @RequestBody NotificationPrefsPatchRequest body) {
        return prefs.patch(p, body);
    }
}
