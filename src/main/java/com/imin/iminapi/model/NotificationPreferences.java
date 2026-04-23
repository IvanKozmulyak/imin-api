package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
public class NotificationPreferences {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "ticket_sold", nullable = false) private boolean ticketSold = true;
    @Column(name = "squad_formed", nullable = false) private boolean squadFormed = true;
    @Column(name = "predictor_shift", nullable = false) private boolean predictorShift = true;
    @Column(name = "fill_milestone", nullable = false) private boolean fillMilestone = true;
    @Column(name = "post_event_report", nullable = false) private boolean postEventReport = true;
    @Column(name = "campaign_ended", nullable = false) private boolean campaignEnded = true;
    @Column(name = "payout_arrived", nullable = false) private boolean payoutArrived = true;
}
