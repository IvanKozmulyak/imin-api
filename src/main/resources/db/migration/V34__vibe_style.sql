-- Per-vibe trained image-provider styles.
-- Persists the reusable style id returned by a provider's style-training endpoint
-- (e.g. Recraft POST /styles) so the orchestrator can resolve a vibe's style_id at
-- generation time. One row per (vibe_id, provider). vibe_id is the key from vibes.yaml.
CREATE TABLE vibe_style (
    vibe_id     VARCHAR(64)  NOT NULL,
    provider    VARCHAR(32)  NOT NULL,
    style_id    VARCHAR(128) NOT NULL,
    trained_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_vibe_style PRIMARY KEY (vibe_id, provider)
);
