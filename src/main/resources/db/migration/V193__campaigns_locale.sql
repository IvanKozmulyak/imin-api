-- V193: language of the fan email shell and unsubscribe page, chosen per campaign.
ALTER TABLE campaigns ADD COLUMN locale VARCHAR(8) NOT NULL DEFAULT 'en';
ALTER TABLE campaigns ADD CONSTRAINT campaigns_locale_check CHECK (locale IN ('en', 'es', 'fr', 'uk'));

-- Drafts follow the organization's country; every other status and Momentum drafts (written in English) keep 'en'.
UPDATE campaigns c
   SET locale = CASE o.country WHEN 'FR' THEN 'fr' WHEN 'MC' THEN 'fr' WHEN 'UA' THEN 'uk' WHEN 'ES' THEN 'es' ELSE 'en' END
  FROM organizations o
 WHERE o.id = c.org_id
   AND c.status = 'draft'
   AND c.origin IS DISTINCT FROM 'momentum';
