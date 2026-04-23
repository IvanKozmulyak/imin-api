CREATE TABLE notification_preferences (
    user_id           UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    ticket_sold       BOOLEAN      NOT NULL DEFAULT TRUE,
    squad_formed      BOOLEAN      NOT NULL DEFAULT TRUE,
    predictor_shift   BOOLEAN      NOT NULL DEFAULT TRUE,
    fill_milestone    BOOLEAN      NOT NULL DEFAULT TRUE,
    post_event_report BOOLEAN      NOT NULL DEFAULT TRUE,
    campaign_ended    BOOLEAN      NOT NULL DEFAULT TRUE,
    payout_arrived    BOOLEAN      NOT NULL DEFAULT TRUE
);
