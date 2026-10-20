-- V191__campaign_recipients_ticket_clicked_at.sql
-- Latest click on the campaign event's ticket page link; written only by the Resend webhook projector.
ALTER TABLE campaign_recipients ADD COLUMN ticket_clicked_at TIMESTAMP WITH TIME ZONE;
