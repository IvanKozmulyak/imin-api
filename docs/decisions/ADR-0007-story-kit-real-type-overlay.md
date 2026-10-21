# ADR-0007: Real-type overlay on derived social images

Status: Accepted
Date: 2026-10-21

## Context

ADR-0001 bans a post-render text overlay on posters, and `imin-api/CLAUDE.md` repeats it: every word of a
poster is baked in by the image model, because a Java2D overlay looked pasted on. That rule is about the
poster itself.

The Instagram kit needs images the poster pipeline cannot give. A story is 1080x1920, a feed post
1080x1350, and the countdown stories (1 week, 2 days, day of) carry the event's own date, time and venue.
Re-asking the model for each one would cost a render per image and could misspell a date. A date or a
venue wrong on a story sends fans to the wrong night.

## Decision

The kit (`marketing/kit`) draws derived images with Java2D and bundled OFL fonts (Inter, Latin and
Cyrillic, licence in `src/main/resources/fonts/story-kit/OFL.txt`). The files are Inter 3.019
(`Version 3.019;git-0a5106e0b` in their name table), SemiBold, Bold and ExtraBold, from the Inter
project at https://github.com/rsms/inter (copyright 2020 The Inter Project Authors):

- The poster is drawn whole, contained and never cropped, over a blurred, darkened copy of itself. Its
  own baked text is never touched.
- Only the date, time, venue and the countdown headline are typeset by the server, in the event's own time
  zone, in en / fr / es / uk. They stay between y 250 and y 1580 of a 1920-high story, clear of
  Instagram's profile and reply bars. A line the font cannot draw is left out.
- Posters themselves stay model-baked and un-overlaid. This ADR widens the rule for derivatives only.
- Fonts load at startup. A host without the native font stack (libfreetype, fontconfig) logs ERROR and the
  kit answers `available:false` and 503 instead of failing the whole API.

Files are written under `story-kits/{eventId}/`, never `ai-posters/`, because `isAiGeneratedPosterUrl`
treats every `ai-posters/` key as a real AI render.

## AI marker (amends the table in ADR-0005)

| Generator | Output | Leaves the system as | Treatment |
|---|---|---|---|
| `StoryKitService` (images) | JPEG story, feed post, countdown stories | R2 `story-kits/`, downloaded or shared by the organizer | XMP `compositeWithTrainedAlgorithmicMedia` when `Event.posterAiGenerated` is true or the poster URL is an AI render; no marker on a derivative of an organizer upload (unknown is not human, as in ADR-0005) |
| `StoryKitCaptionService` | Instagram caption (mood sentence, hashtags) | organizer UI, then pasted into Instagram by the organizer | visible "Written by AI" label in the dashboard. A caption that states a number the event data does not hold, or a link, is rejected; the date, venue and ticket line are built by the server |

## Consequences

- Kit images carry a different look from the poster only in the band of type and the blurred fill.
- A new language needs its overlay strings in `StoryKitText`, a native review, and glyph coverage in the
  bundled fonts (`StoryKitFontsTest`).
- Deleting an event leaves its `story-kits/` objects until a sweep exists.
