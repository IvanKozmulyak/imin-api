# ADR-0005: Machine-readable AI marker on AI email text, subject lines and images

Status: Accepted
Date: 2026-09-27

## Context

AI Act Art. 50(2) applies from 02.08.2026, and for systems already on the market it applies from
**02.12.2026**. Hard rule 28 in the audience-tool data-sources (§7) says: *"Machine-readable AI
marker on every AI-generated email text, subject line and image by 02.12.2026; visible AI label on
every AI text in the organizer UI."* Data-sources §6 adds: *"Ordinary marketing emails need no
visible label."* This ADR picks the marker for each medium. It does not add any legal wording.

These are the AI outputs in imin-api (origin/master 1b90d117):

| Generator | Output | Leaves the system as | Treatment |
|---|---|---|---|
| `PosterOrchestrator` → `PosterImageStorage.writePng` | Ideogram poster PNG, and the brand-logo composite | R2 / local image; poster hero in campaign emails; buyer page | XMP in the PNG |
| `MediaUploadService` (Poster Studio upload with `aiGenerated=true`) | PNG/JPEG poster | R2 image | XMP in the file |
| `EmailComposeVariantsService` | subject + preheader + body variants | campaign email once applied | campaign flags → headers + HTML meta |
| `SubjectVariantsService` | subject variants | campaign subject once applied | campaign flag → headers + HTML meta |
| `MomentumCopyGenerator` | subject, preheader, body, `why` | Momentum campaign email; `why` in the in-app notification | flags set on approve; `why` UI label (webapp) |
| `CampaignTemplateService` (generate) | colour/header tokens + name | template styling, no text | none (not text or image output) |
| `ConceptSetService`, `AiEventDescriptionService`, `ConceptOverviewLlm` | concept names, descriptions, captions | organizer UI; may become the event description | UI label (webapp) |
| `EventContentService` (`/events/ai-content`) | names, taglines, copy | organizer API only (no webapp caller) | none today |
| `AiSegmentService` | segment rules + explanation | organizer UI | UI label (webapp) |
| `ReforecastNarrator`, `Stage0Scorer` | narration, factors, claims | organizer UI | existing ledger disclosure; UI label (webapp) |

## Decision

**Images: embedded XMP with the IPTC `DigitalSourceType`.** `ImageAiMarker` writes an XMP packet
into PNG (an `iTXt` chunk with keyword `XML:com.adobe.xmp`, placed straight after `IHDR`) and into
JPEG (an `APP1` segment after JFIF/Exif). It uses
`http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia` for a render and
`compositeWithTrainedAlgorithmicMedia` for the logo composite. This is the IPTC vocabulary that
photo tools and platforms read. The marker is written by byte surgery, with no re-encode, so
pixels and any upstream metadata (for example a provider's C2PA chunk) are kept. An older XMP packet
is replaced, not duplicated.

- `PosterImageStorage.writePng` marks every image it stores. That class is already the only writer of
  `ai-posters/` keys (see `isAiGeneratedPosterUrl`). What reaches it: the Ideogram V3 download
  (generate and remix; PNG in practice, but the client does not assert a format) and the logo
  composite (always re-encoded as PNG by ImageIO). Recraft is used only for style training and never
  renders a stored poster. PNG and JPEG are marked in place. Any other format ImageIO can decode is
  re-encoded as PNG and then marked. An image nothing can decode (for example WebP, as no WebP
  ImageIO plugin is on the classpath) is **stored unmarked with a WARN log** rather than failing the
  variant: a lost poster is a worse outcome than a missing marker on a format no renderer returns
  today. The WARN is the signal to add a decoder if it ever fires.
- `MediaUploadService` marks a poster upload flagged `aiGenerated=true` and answers 400 when the file
  cannot be marked. An organizer's own upload is stored byte for byte.

**Email: MIME headers plus HTML meta.** When any part of a campaign is AI-generated, the message
carries:
- `AI-Disclosure: mode=ai-originated`. This follows the IETF individual draft
  *draft-abaris-aicdh* (AI Content Disclosure header). It is a draft, not a standard, and is chosen
  because it is the only published proposal for a per-message disclosure header.
- `X-IMIN-AI-Generated: subject, body`, naming the parts, because the draft header is per message.
- The same two facts as `<meta name="ai-disclosure">` and `<meta name="imin-ai-generated">` in the HTML
  `<head>`.

No visible text is added to the email (data-sources §6). The test send carries the same marker.

**Provenance of campaign copy is decided server-side where possible.** `campaigns.subject_ai_generated`
and `body_ai_generated` (V145; "body" covers the preheader) are set in three ways:
1. On Momentum approve.
2. When a PATCH saves, verbatim after whitespace normalisation, a subject/preheader/body that
   `compose-variants` or `subject-variants` offered for that campaign. SHA-256 fingerprints are kept in
   `campaign_ai_suggestions`, and deterministic fallbacks are not fingerprinted.
3. When the client sends `subjectAiGenerated` / `bodyAiGenerated` = true. This covers text the
   organizer edited after applying it.

The flags are **sticky**. False or absent never clears them, because editing model output does not
make it human-written. The cost is over-marking a fully rewritten draft, which is the safe side.
Duplicate copies them.

## Not covered (open, for Ivan)

- Images stored before this ships carry no embedded marker, and there is no backfill. They keep the
  existing `events.poster_ai_generated` / public-page flag.
- The Poster Studio canvas fallback (a template render, not a model render) is uploaded with
  `aiGenerated=true` and so is marked as AI. That is a pre-existing labelling choice, kept as it is.
- Web text on the public event page (a concept description promoted into the event) is outside rule 28,
  which names email text, subject lines and images. Whether Art. 50 needs a marker there is a legal
  question, not settled here.
- C2PA content credentials (signed manifests) are not produced. They need a signing certificate.
  IPTC XMP is unsigned and can be stripped by re-encoding tools.
- SMS campaigns are off, and they carry no marker.
