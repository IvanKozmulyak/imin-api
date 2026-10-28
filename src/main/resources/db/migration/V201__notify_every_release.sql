-- Notify-me as a waitlist: tell everyone each time tickets come back.
--
-- every_release: the signup form showed "we email everyone each time tickets come back"
-- (client sends everyRelease=true). NotifyReleaseSender re-arms such a row after a sell-out
-- so the next release mails it again. Existing rows signed up under "One email when tickets
-- go on sale. Nothing else." and default to false: they stay one-shot.
ALTER TABLE notify_subscriptions ADD COLUMN every_release BOOLEAN NOT NULL DEFAULT FALSE;

-- The re-arm pass's scan: events that still have notified, re-armable rows.
CREATE INDEX idx_notify_subscriptions_rearm ON notify_subscriptions (event_id)
    WHERE notified_at IS NOT NULL AND every_release;

-- One row per release of an event: the emails the provider accepted while the event stayed
-- purchasable. The open row (closed_at NULL) collects retries; a sweep that finds the event
-- with nothing purchasable closes it, so the next release starts a new row. Counts only.
CREATE TABLE notify_releases (
    id            UUID                     PRIMARY KEY,
    event_id      UUID                     NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    first_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_count    INTEGER                  NOT NULL CHECK (sent_count > 0),
    closed_at     TIMESTAMP WITH TIME ZONE NULL
);

CREATE UNIQUE INDEX uq_notify_releases_open ON notify_releases (event_id) WHERE closed_at IS NULL;
CREATE INDEX idx_notify_releases_event ON notify_releases (event_id, first_sent_at DESC);
