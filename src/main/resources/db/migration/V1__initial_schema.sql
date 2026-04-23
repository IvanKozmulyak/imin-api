CREATE TABLE generated_event (
    id                    UUID           PRIMARY KEY,
    vibe                  TEXT,
    tone                  VARCHAR(255),
    genre                 VARCHAR(255),
    city                  VARCHAR(255),
    event_date            DATE,
    platforms             TEXT,
    accent_colors         TEXT,
    poster_urls           TEXT,
    suggested_min_price   NUMERIC(10, 2),
    suggested_max_price   NUMERIC(10, 2),
    recommended_dow       VARCHAR(20),
    pricing_notes         TEXT,
    status                VARCHAR(20)    NOT NULL,
    created_at            TIMESTAMP      NOT NULL
);

CREATE TABLE concept (
    id                    UUID           PRIMARY KEY,
    generated_event_id    UUID           NOT NULL,
    title                 VARCHAR(255),
    description           TEXT,
    tagline               VARCHAR(255),
    sort_order            INTEGER,
    CONSTRAINT fk_concept_event FOREIGN KEY (generated_event_id) REFERENCES generated_event (id)
);

CREATE TABLE social_copy (
    id                    UUID           PRIMARY KEY,
    generated_event_id    UUID           NOT NULL,
    platform              VARCHAR(50),
    copy_text             TEXT,
    CONSTRAINT fk_social_copy_event FOREIGN KEY (generated_event_id) REFERENCES generated_event (id)
);
