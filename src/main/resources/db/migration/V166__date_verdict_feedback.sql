-- V166: the organizer's after-event answer to "did the date verdict match?" — one row per event.
-- verdict/for_date/date_check_id snapshot the rated row at the first answer; answer/comment are replaced on re-answer.
CREATE TABLE date_verdict_feedback (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    date_check_id UUID,
    for_date DATE NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    answer VARCHAR(8) NOT NULL,
    comment VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_date_verdict_feedback_event UNIQUE (event_id),
    CONSTRAINT fk_date_verdict_feedback_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_date_verdict_feedback_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE SET NULL,
    CONSTRAINT ck_date_verdict_feedback_verdict CHECK (verdict IN ('good','adjust','move')),
    CONSTRAINT ck_date_verdict_feedback_answer CHECK (answer IN ('yes','partly','no'))
);
