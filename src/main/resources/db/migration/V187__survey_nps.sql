-- V187__survey_nps.sql
-- Optional 0-10 recommendation score on a survey answer; the event NPS and the linked membership score read it.
ALTER TABLE survey_responses ADD COLUMN nps_score SMALLINT
    CONSTRAINT survey_responses_nps_score_range CHECK (nps_score BETWEEN 0 AND 10);

-- The score a survey sign-up asked to keep with the email, held until its confirmation link is clicked.
ALTER TABLE consent_confirmation_tokens ADD COLUMN nps_score SMALLINT
    CONSTRAINT consent_confirmation_tokens_nps_score_range CHECK (nps_score BETWEEN 0 AND 10);
