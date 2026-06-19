-- Audience Tier C: platform-wide consumer identity.
-- normalized_email is the dedup key (lower+trim); UNIQUE enforces one Consumer per email.
CREATE TABLE consumers (
    consumer_id    UUID         PRIMARY KEY,
    normalized_email VARCHAR(254) NOT NULL,
    display_name   VARCHAR(255),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_consumers_normalized_email ON consumers (normalized_email);
