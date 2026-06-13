-- V40__concept_captions.sql
-- Three social captions per concept (Instagram / TikTok / X) for AI Studio.
ALTER TABLE concept ADD COLUMN instagram_caption TEXT;
ALTER TABLE concept ADD COLUMN tiktok_caption    TEXT;
ALTER TABLE concept ADD COLUMN x_caption         TEXT;
