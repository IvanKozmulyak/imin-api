CREATE TABLE style_reference_analysis (
    sub_style_tag    VARCHAR(64)  PRIMARY KEY,
    descriptor       TEXT         NOT NULL,
    image_signature  VARCHAR(64)  NOT NULL,
    model_id         VARCHAR(128) NOT NULL,
    analyzed_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
