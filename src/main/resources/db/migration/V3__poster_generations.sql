CREATE TABLE poster_generations (
    id                  UUID            PRIMARY KEY,
    generated_event_id  UUID            NOT NULL,
    organizer_id        UUID,
    status              VARCHAR(20)     NOT NULL,
    sub_style_tag       VARCHAR(64),
    created_at          TIMESTAMP       NOT NULL,
    raw_ready_at        TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT fk_poster_gen_event FOREIGN KEY (generated_event_id) REFERENCES generated_event (id)
);

CREATE INDEX idx_poster_gen_event ON poster_generations (generated_event_id);
CREATE INDEX idx_poster_gen_status ON poster_generations (status);

CREATE TABLE poster_variants (
    id                      UUID            PRIMARY KEY,
    generation_id           UUID            NOT NULL,
    variant_style           VARCHAR(32)     NOT NULL,
    ideogram_prompt         TEXT            NOT NULL,
    reference_images_used   TEXT,
    seed                    BIGINT,
    raw_url                 TEXT,
    final_url               TEXT,
    status                  VARCHAR(20)     NOT NULL,
    ideogram_cost_eur       NUMERIC(10, 4),
    failure_reason          TEXT,
    CONSTRAINT fk_variant_generation FOREIGN KEY (generation_id) REFERENCES poster_generations (id) ON DELETE CASCADE
);

CREATE INDEX idx_poster_variant_generation ON poster_variants (generation_id);
