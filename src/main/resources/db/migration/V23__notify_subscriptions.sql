-- Notify-me subscriptions for the public event page.
--
-- A buyer can subscribe their email to be notified when tickets release / re-release
-- for a public event. Subscriptions are captured for later — there is no email-sending
-- pipeline yet; the spec keeps that as a separate concern.
--
-- The (event_id, email) pair is unique per subscription so duplicate submissions are
-- swallowed at the database level. Emails are lowercased server-side before insert so
-- case-variants of the same address don't double-subscribe.
CREATE TABLE notify_subscriptions (
    id          UUID         PRIMARY KEY,
    event_id    UUID         NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    email       VARCHAR(254) NOT NULL,
    created_at  TIMESTAMP    WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_notify_event_email UNIQUE (event_id, email)
);

CREATE INDEX idx_notify_subscriptions_event ON notify_subscriptions(event_id);
