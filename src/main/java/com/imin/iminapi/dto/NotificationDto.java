package com.imin.iminapi.dto;

import com.imin.iminapi.model.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String kind,
        String title,
        String body,
        String link,
        Instant createdAt,
        Instant readAt) {

    public static NotificationDto from(Notification n) {
        return new NotificationDto(
                n.getId(), n.getKind(), n.getTitle(), n.getBody(),
                n.getLink(), n.getCreatedAt(), n.getReadAt());
    }
}
