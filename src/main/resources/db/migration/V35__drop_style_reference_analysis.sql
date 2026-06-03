-- Remove the legacy vision-descriptor cache. The startup ReferenceImageAnalyzer subsystem was
-- deleted: the hand-authored vibe presets (vibes.yaml) now drive the prompt, and reference images
-- condition the model directly (Ideogram style_reference_images / Recraft trained style), so the
-- auto-generated per-tag style descriptors are obsolete.
DROP TABLE IF EXISTS style_reference_analysis;
