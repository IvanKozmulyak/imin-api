-- V207: the paid-checkout marketing notice the buyer read (verbatim, with its version) and whether they ticked
-- the refuse box. Schema only: every earlier order gets NULL / NULL / FALSE, so none of them backs a soft opt-in.
ALTER TABLE orders ADD COLUMN marketing_notice_proof VARCHAR(500) NULL;
ALTER TABLE orders ADD COLUMN marketing_notice_text_version VARCHAR(32) NULL;
ALTER TABLE orders ADD COLUMN marketing_refused BOOLEAN NOT NULL DEFAULT FALSE;
