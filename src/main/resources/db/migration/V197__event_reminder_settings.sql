-- Per-event reminder switches and door note, and what happened to each order's reminder.
ALTER TABLE events ADD COLUMN reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE events ADD COLUMN reminder_share_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE events ADD COLUMN reminder_door_note VARCHAR(280);

ALTER TABLE orders ADD COLUMN reminder_24h_outcome VARCHAR(16);
ALTER TABLE orders ADD COLUMN reminder_3h_outcome VARCHAR(16);
ALTER TABLE orders ADD CONSTRAINT ck_orders_reminder_24h_outcome
    CHECK (reminder_24h_outcome IS NULL OR reminder_24h_outcome IN ('sent','opted_out','bounced','no_ticket','no_event'));
ALTER TABLE orders ADD CONSTRAINT ck_orders_reminder_3h_outcome
    CHECK (reminder_3h_outcome IS NULL OR reminder_3h_outcome IN ('sent','opted_out','bounced','no_ticket','no_event'));
