package com.imin.iminapi.controller.notification;

import com.imin.iminapi.dto.NotificationCountResponse;
import com.imin.iminapi.dto.NotificationDto;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.util.Times;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) { this.repo = repo; }

    @GetMapping
    public List<NotificationDto> list(@CurrentUser AuthPrincipal p) {
        return repo.findTop50ByUserIdOrderByCreatedAtDesc(p.userId())
                .stream().map(NotificationDto::from).toList();
    }

    @GetMapping("/unread-count")
    public NotificationCountResponse unreadCount(@CurrentUser AuthPrincipal p) {
        return new NotificationCountResponse(repo.countByUserIdAndReadAtIsNull(p.userId()));
    }

    /** Marks every unread notification read. The FE calls this when the panel opens. */
    @PostMapping("/read-all")
    public NotificationCountResponse readAll(@CurrentUser AuthPrincipal p) {
        List<Notification> unread = repo.findByUserIdAndReadAtIsNull(p.userId());
        Instant now = Times.nowMicros();
        unread.forEach(n -> n.setReadAt(now));
        repo.saveAll(unread);
        return new NotificationCountResponse(0);
    }
}
