-- When the email left for the provider; the time axis of the Channels "sent in 30 days" count.
-- Historic values are approximate: the delivery time, else the last event time.
ALTER TABLE campaign_recipients ADD COLUMN sent_at TIMESTAMPTZ;

UPDATE campaign_recipients
   SET sent_at = COALESCE(delivered_at, last_event_at)
 WHERE status IN ('sent', 'delivered', 'opened', 'clicked', 'bounced', 'complained', 'unsubscribed');
