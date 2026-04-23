package com.imin.iminapi.controller.notification;

import com.imin.iminapi.dto.NotificationCountResponse;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) { this.repo = repo; }

    @GetMapping("/unread-count")
    public NotificationCountResponse unreadCount(@CurrentUser AuthPrincipal p) {
        return new NotificationCountResponse(repo.countByUserIdAndReadAtIsNull(p.userId()));
    }
}
