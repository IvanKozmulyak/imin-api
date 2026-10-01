-- V168: at most one predictor alert (band crossing or radar) per event per event-local day; the first claim wins.
CREATE TABLE predictor_alert (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    alert_day DATE NOT NULL,
    kind VARCHAR(8) NOT NULL,
    date_check_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_predictor_alert_event_day UNIQUE (event_id, alert_day),
    CONSTRAINT fk_predictor_alert_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_predictor_alert_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE SET NULL,
    CONSTRAINT ck_predictor_alert_kind CHECK (kind IN ('band','radar')),
    CONSTRAINT ck_predictor_alert_band_shape CHECK (kind = 'radar' OR date_check_id IS NULL)
);
