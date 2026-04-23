CREATE TABLE notifications (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        VARCHAR(64)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    link        TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at     TIMESTAMP
);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, read_at);
