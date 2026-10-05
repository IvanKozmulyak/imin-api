-- What the payout sweep pulled back from the connected account for a LOST dispute, and what it has transferred back since.
ALTER TABLE disputes ADD COLUMN recovered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE disputes ADD COLUMN recovered_minor BIGINT;
ALTER TABLE disputes ADD COLUMN recovery_reversal_id VARCHAR(64);
ALTER TABLE disputes ADD COLUMN returned_minor BIGINT;
ALTER TABLE disputes ADD COLUMN returned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE disputes ADD COLUMN return_transfer_id VARCHAR(64);
