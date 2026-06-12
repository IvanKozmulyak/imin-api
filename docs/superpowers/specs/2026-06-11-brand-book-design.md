    # Brand Book — Design Spec

**Status:** Approved 2026-06-12 · **Date:** 2026-06-11 · **Repos:** `imin-api`, `imin-webapp`

Synthesized from a multi-agent design workflow: 6 recon agents (poster pipeline, org data model, webapp, prototype, Ideogram API, prior specs), 3 independent designs (MVP / product / architecture lenses), 2-judge panel (MVP won 66 vs 63 vs 58), feasibility-skeptic and completeness-critic passes. All file/line references below were verified against the code by the recon or critique agents.

## 0. Product requirements

1. Before creating an AI poster, the organizer is **proposed** (nudged, optional, non-blocking) to set up a brand book.
2. The brand book holds: collective/venue name, an uploaded logo, up to 3 **ordered** accent colours ("the AI leads with the first one").
3. Brand data feeds poster generation: accent colours influence the render; the logo is added to the poster; logo inclusion is **configurable**.

## 1. Scope

### In
- Brand book settings tab in `imin-webapp` (name, logo, ≤3 ordered colours) matching the approved screenshot.
- Persistence as columns on `organizations` (one Flyway migration).
- Nudge before AI poster generation (both entry points), dismissable.
- Accent colours injected into the Ideogram prompt; logo composited server-side onto accepted posters; toggle at org level + per-generation override.
- ADR-0002 documenting the logo-composite carve-out from the no-overlay decision.

### Out (non-goals, each safe to defer)
- **Captions / campaigns / email footers / event pages consuming brand data.** Data model is generic org-level columns; future consumers read the same columns with zero migration. Microcopy is softened accordingly (§6) so we don't promise surfaces we don't ship.
- **`brand_name` rendered on posters.** Phase 1 stores and displays it in the brand book only. Injecting it into the model-native text layer adds text-gate surface area; deferred until we decide it's wanted. (Open question for PO — see §10.)
- **SVG logos.** Phase 1 is **PNG-only** (decision and rationale in §4). Screenshot microcopy changes from "PNG / SVG" to "PNG with transparency works best".
- **Brand fonts, per-org style training, logo placement editor.** One fixed corner placement; a `logo_placement` enum can come later.
- **Style-gate awareness of the brand palette.** The style gate is SOFT (best-effort, no remix — `PosterOrchestrator.java:258-266`), so a brand/vibe palette mismatch degrades to acceptance, never a failure. Left untouched deliberately.
- **If-Match concurrency on brand writes.** Single-editor org config; last-write-wins + refetch-on-focus (§6) is acceptable.

## 2. Data model

Brand data is always-present org-level config → **columns on `organizations`**, consistent with every prior org extension (slug V15, Stripe mirror V18/V33). `Organization.java` currently has no brand fields. V37 is the migration high-water mark.

```sql
-- V38__org_brand_book.sql
ALTER TABLE organizations ADD COLUMN brand_name VARCHAR(120);
ALTER TABLE organizations ADD COLUMN brand_logo_url TEXT;
ALTER TABLE organizations ADD COLUMN brand_accent_colors TEXT NOT NULL DEFAULT '[]';  -- JSON list, ordered
ALTER TABLE organizations ADD COLUMN brand_logo_on_posters BOOLEAN NOT NULL DEFAULT TRUE;
```

- `brand_accent_colors` maps via the existing `StringListJsonConverter` (`@Convert`, proven by the V33 Stripe requirement lists). Order **is** priority: index 0 = primary ("AI leads with the first one"). No role field.
- `brand_name` is distinct from `organizations.name` (legal/account name): it's the public display name ("Tortuga Collective").

**Generation provenance (same migration):** stamp the resolved brand onto the generation row at creation:

```sql
ALTER TABLE poster_generations ADD COLUMN brand_snapshot TEXT;              -- JSON: {colors, logoUrl, logoOn} or NULL when brandless
ALTER TABLE poster_variants    ADD COLUMN logo_composite_status VARCHAR(16); -- NULL | 'APPLIED' | 'SKIPPED' | 'FAILED', sits beside validation_verdict
```

Why: (a) corrective remix re-reads the **snapshot**, not live org state, so an old poster remixed after a rebrand doesn't silently mix old prompt colours with a new logo; (b) "why does this poster have no logo despite toggle on" is answerable; `failure_reason` stays reserved for real generation failures.

### Validation (service layer, surfaced via the existing `ApiError {code,message,fields}` envelope)
- Hex: `^#[0-9a-fA-F]{6}$` (reject 3-digit, named, `rgb()`); normalize to lowercase.
- Count ≤ 3; empty list allowed. Case-insensitive dedupe (duplicate wastes a slot).
- **Per-index error keys** built manually in `OrgBrandService` — `fields: {"accentColors[1]": "..."}` — so the FE can highlight the offending swatch. (Bean validation can't index list elements; use the manual map + `ApiException`.)
- `brand_name`: trimmed, max 120, blank → NULL.

## 3. API

All under `/api/v1`, JWT bearer, org-scoped via `p.orgId()`. New `OrgBrandController` + `OrgBrandService`; DTOs are records; no `@Operation` annotations (house style — OpenAPI inferred from signatures).

### 3.1 `GET /api/v1/org/brand` → 200
```jsonc
{ "brandName": "Tortuga Collective",
  "logoUrl": "https://cdn.../orgs/{orgId}/brand/logo-ab12cd34.png",   // null if none
  "accentColors": ["#ec4899", "#f6c04a", "#a78bfa"],                  // [] if none, ordered
  "logoOnPosters": true }
```

### 3.2 `PUT /api/v1/org/brand` — full replace of the three scalar fields
Request: `{ "brandName": string|null, "accentColors": string[], "logoOnPosters": boolean }` — all fields **required**; the FE always sends its full controlled state. Response: 200 with the GET shape.

PUT-full-replace instead of PATCH: a plain Java record can't distinguish omitted from explicit-null, and a debounced autosave that sends partial diffs creates ambiguous clear-vs-keep semantics (flagged P0 by the completeness critic). Full replace makes "deselect last swatch ⇒ `accentColors: []` ⇒ cleared" unambiguous. Stale-tab clobber risk is mitigated by TanStack refetch-on-focus (§6); last-write-wins is accepted (§1).

Errors: `400 VALIDATION` with per-index field keys. `404` never (authenticated principal always has an org).

### 3.3 `POST /api/v1/org/brand/logo` — multipart, part name `file` (matches `EventMediaController`)
New `OrgMediaService.uploadLogo(orgId, file)`, parallel to `MediaUploadService`, same R2 `MediaStorage` seam and URL-precompute-then-put retry-safe pattern (`MediaUploadService.java:57-81`):
- **PNG only** (Phase 1): magic bytes `0x89PNG` via the existing `verifyMagicBytes` (`MediaUploadService.java:130-135`).
- ≤ 2 MB; min short side **128 px**; aspect ratio between 1:4 and 4:1 (a favicon scaled to 18% poster width looks broken; a banner composites as a hairline).
- Key: `orgs/{orgId}/brand/logo-{sha256prefix8}.png` (content-addressed; collides with neither `events/...` nor `ai-posters/...`). Old object deleted best-effort after the new put succeeds (existing pattern, `MediaUploadService.java:79`). Orphans accepted as known debt; log orphan events.
- Response: `200 { "logoUrl": "..." }`. Errors: `400 VALIDATION` (type/size/dimensions).
- **Add a `MaxUploadSizeExceededException` handler to `GlobalExceptionHandler`** mapping to `413` — today an oversize body falls through to the `Throwable` handler and returns `500 INTERNAL` (verified; there is no such handler).

### 3.4 `DELETE /api/v1/org/brand/logo` → 204
Clears `brand_logo_url`, best-effort R2 delete. `brand_logo_on_posters` is left untouched; the studio toggle is disabled while no logo exists, and the FE shows the persisted default when a new logo is uploaded (§6).

### 3.5 Generation wiring — no new endpoint
`ConceptRequest` gains **one optional field**: `logoOnPosters: Boolean` (a per-call render directive, not brand identity). Resolution: `request.logoOnPosters() ?? org.brandLogoOnPosters() ?? true`. Colours are **not** in the request — the org is the source of truth.

## 4. Logo on poster

### Relationship to the no-overlay decision (→ ADR-0002, a required deliverable)
The locked decision (`2026-06-08-ideogram-v3-native-render-design.md`) removed the QR / address band / Satori **text** compositors: its scope is *typography stays model-native*. A logo is not text, and a diffusion model provably cannot reproduce an exact mark (no logo-placement feature exists in the Ideogram v3 API; style references do style transfer only). Deterministic compositing is the only faithful option and restores nothing the decision removed. ADR-0002 records this carve-out explicitly so the boundary is documented, not implied.

### Mechanism — pure Java2D, no new dependency
- `BufferedImage` + `Graphics2D` + `AlphaComposite.SrcOver` — the exact toolkit `QrImageRenderer` already uses; pom.xml needs nothing new (Thumbnailator rejected by the judge grafts as an unnecessary dependency).
- **Seam: inside `accept()` in `PosterOrchestrator.renderWithValidation`** — the sole funnel that sets `finalUrl`, so all three acceptance paths (text-gate best-effort :251, accepted :262, style soft-fail :266) get the logo. ("After the style gate, before persist" is not a real single location — verified.)
- **Storage:** the composited PNG is a **second** storage write. `PosterImageStorage.writePng(byte[])` mints its own UUID key and returns the URL, so the composite step calls it again (or an overload) → new object → `final_url` = composited URL, `raw_url` keeps the un-composited one. The columns exist and are equal today; **audit every reader of `raw_url`/`final_url`** (gallery, downloads, any public surface) and document: `final_url` is canonical for display/download, `raw_url` for re-edit/remix inputs.
- **Placement:** bottom-right; margin 4% of poster width; logo scaled to max 18% of poster width, aspect preserved.
- **Legibility scrim (required, not optional):** sample mean luminance of the destination corner region; draw a subtle contrasting rounded scrim/pill behind the logo. Without it, a white mark on a light corner (or dark-on-dark) ships invisible — Ideogram backgrounds are unpredictable per generation.
- **Performance:** cache the decoded logo `BufferedImage` per org (invalidate on upload/delete); composite is CPU-only, no extra API call, bounded by the 2 MB upload cap and the 18%-width scale.

### Failure isolation (hard rule: generation never fails over a decoration)
Composite wrapped in try/catch: logo URL 404, decode error, OOM → log + Sentry warning, `logo_composite_status = 'FAILED'`, ship the un-composited poster (`final_url = raw_url`). Toggle off or no logo → `'SKIPPED'` / NULL.

### Configurability
- Org default: `brand_logo_on_posters` column, edited in the brand book.
- Per-generation: studio switch, defaults to the org value, sent as `ConceptRequest.logoOnPosters`, wins over the default.

## 5. Accent colours → generation

### Prompt injection only — `color_palette` is not used
The vibe system's curated `style_reference_images` are the primary style control (`IdeogramV3Client.applyStyleControl`, refs-XOR-preset, `IdeogramV3Client.java:94-104`). Secondary sources report `color_palette` is incompatible with reference images (not confirmed on the generate-v3 reference page — evidence softened per the feasibility review), and Ideogram's docs frame palette control as creative direction, not precise matching. Prompt injection avoids the question entirely, requires zero Ideogram client change, and preserves vibe fidelity.

### Wiring — the smallest correct change
- `ConceptStudioService.toLegacyRequest()` (`ConceptStudioService.java:195`, currently `/* accentColor */ null`) populates the existing `EventCreatorRequest.accentColor` (single `String`, field 10) from the org brand. All colours pack into that one string: `#ec4899 (lead); supporting: #f6c04a, #a78bfa`. Honest framing: this is one free-text line in the Event brief (`AiEventDescriptionService.buildPrompt`, line 430) — influence is directional, not structured per-variant control. (Note for the pipeline owner, out of scope here: `buildPrompt` still says "Recraft" at lines 366/394 though the renderer is Ideogram.)
- **Failure-isolated:** the org-brand lookup is wrapped in try/catch — malformed `brand_accent_colors` JSON or a DB hiccup logs and proceeds **brandless**. Brand integration must never break the existing generation path. Regression test required.
- Empty/partial brand book: no colours → `accentColor` stays null (today's behavior, fully backward compatible); colours without logo and logo without colours each work independently.

### Gates and remix
- Text gate (HARD): unaffected.
- Style gate (SOFT): may flag `paletteMatches=false` on a brand/vibe mismatch; accepts best-effort, no remix — by design.
- Corrective remix preserves "composition, hero, colors, layout" (`PosterOrchestrator.java:293`), so brand colours baked into the initial prompt survive remixes. Remix reads `brand_snapshot` (§2), not live org state.

## 6. Frontend (`imin-webapp`)

### Brand book page — a settings tab
- Add `'brand'` to the `TABS` tuple (`SettingsPage.tsx:14-23` — currently 8 tabs), `copy.settings.tabs.brand = "Brand book"`, and `BrandTab.tsx` under `features/settings/tabs/`. Route `/settings/brand` works via the existing `settings/:tab` pattern; zero router work.
- **IDENTITY card** (`Card variant='dark'`): `shared/ui/Upload` square dropzone (`accept` PNG, 2 MB, posts multipart to `/org/brand/logo`), name `Input` with the mono-uppercase eyebrow idiom, helper copy **"Shows on your posters."** (event pages / email footers dropped from copy until those surfaces ship), microcopy **"PNG with transparency works best"**.
- **ACCENT COLOURS card:** "n/3 PICKED" counter; "Pick up to three. The AI leads with the first one."; preset swatches + "+ Custom" (`<input type="color">`); selected swatches show order badges 1/2/3; "PALETTE ·" hex row. Confirmed presets from the design: `#ec4899`, `#f6c04a`, `#a78bfa`; remaining presets (blue, green `~#22c55e`, cyan `~#06b6d4`, orange `~#f97316`, light `~#f3f4f6`) to be finalized against theme tokens at build time (prototype JSX is no longer recoverable).
- **Expectation copy (required):** "Brand colours guide the look — results won't match your hex exactly." in the colours card. Prevents "the colour is wrong" tickets; prompt-hint colour is directional.
- **Save model:** controlled state + debounced (~600 ms) autosave via `PUT /org/brand` sending **full state**, `AutosaveIndicator` + toast patterns from `NotificationsTab`/`AccountTab`; small zod schema mirroring the hex/count rules; logo saves immediately on upload. `useBrandBook()` TanStack query (`GET /org/brand`) with refetch-on-focus enabled so a stale tab can't silently clobber. New `BrandBook` type in hand-written `shared/api/types.ts`.
- **A11y acceptance criteria:** swatches are buttons with `aria-pressed` and labels ("Accent 1, #ec4899, selected"); keyboard select/deselect; visible focus rings; order-badge contrast over arbitrary swatch colours; `Card variant='dark'` hardcodes white text — use rgba-white tints, not `--text2/3`.
- **i18n/copy:** every new string goes through `copy.ts`; no inline literals.

### The nudge
- Mounted at **both** entry points: `PosterStudioDialog` step 1 (vibe picker, above the card, before `handleGenerate()` :297) and `AiStudioPage` (`/events/new/ai`).
- Shown only when the brand book is empty (no colours AND no logo). Non-blocking banner: "Teach the AI your look — set up your brand book (optional, 60s)" → "Set up →" navigates to `/settings/brand`; "Not now" dismisses via `localStorage` (`imin.brandNudgeDismissed`) — a per-device UX preference, not org state.

### Poster studio
- "Add logo to posters" switch on step 1: default = `brandBook.logoOnPosters`, disabled with hint when no logo uploaded, sent as `logoOnPosters` on the concept request.
- On-brand indication when colours are set: small palette-dot row ("On-brand ●●●") near the Generate CTA.
- After logo re-upload following a delete, surface the persisted toggle state ("Logo will appear on posters — your default is ON") so it doesn't silently re-enable.

## 7. Rollout — four PRs (api:sync pulls **prod**)

`npm run api:sync` regenerates FE types from the production OpenAPI, so each FE step lands only after the prior BE deploy.

1. **BE PR 1 — data + contract:** V38 migration, `Organization` fields, `OrgBrandController`/`OrgBrandService`, `OrgMediaService.uploadLogo`, `MaxUploadSizeExceededException` handler. Purely additive; generation untouched. Deploy.
2. **FE PR 1 — brand surface:** `api:sync` + reconcile `types.ts` (additive fields won't trip `api:check` — reviewer diffs deployed `/v3/api-docs.yaml` by hand; a field-name typo passes silently, so this review step is named in the PR checklist). Brand tab, nudge, `useBrandBook`. Verify/extend `vercel.json` CSP: `img-src` (logo preview renders from the R2 CDN origin) and `connect-src`.
3. **BE PR 2 — generation:** `ConceptRequest.logoOnPosters`, `ConceptStudioService` brand lookup (failure-isolated), prompt colour packing, Java2D composite in `accept()` + second storage write, `brand_snapshot`/`logo_composite_status` stamping, **ADR-0002**. Deploy.
4. **FE PR 2 — studio:** `api:sync`, logo toggle, on-brand dots, expectation copy.

Between any two steps the system is fully consistent: empty brand columns ⇒ generation behaves exactly as today.

## 8. Edge cases

| # | Case | Handling |
|---|---|---|
| 1 | 4th colour selected | FE caps at 3; backend rejects >3 (`400`, defense-in-depth) |
| 2 | Invalid hex / duplicates via API | `400 VALIDATION`, per-index field keys; FE zod prevents most |
| 3 | SVG uploaded | Rejected at upload: "PNG only for poster logos (SVG support later)" — honest, no dead state, no XXE/SSRF/XSS surface from stored SVG |
| 4 | Oversize body (> multipart limit) | New handler → `413` (was `500 INTERNAL`) |
| 5 | Upload put succeeds, DB write fails | URL precomputed before put (existing retry-safe pattern); orphan logged |
| 6 | Logo 404/decode-fail/OOM at composite | try/catch → un-composited poster ships, `logo_composite_status='FAILED'`, Sentry warning |
| 7 | Opaque logo on same-tone background | Luminance-sampled scrim — always legible |
| 8 | Brand colour clashes with vibe palette | Style gate soft-flags, accepts best-effort; intentional |
| 9 | Malformed `brand_accent_colors` JSON / DB hiccup at generation | try/catch → brandless generation; regression-tested |
| 10 | Stale FE without `logoOnPosters` | Optional field; falls back org default → `true`; no NPE |
| 11 | Remix after rebrand | Remix reads `brand_snapshot`, not live brand |
| 12 | Tiny/extreme-aspect logo | Upload rejected: min short side 128 px, aspect within 1:4–4:1 |
| 13 | Two tabs editing | Full-state PUT + refetch-on-focus; last-write-wins accepted |
| 14 | Logo deleted, toggle still TRUE | Studio switch disabled while no logo; state surfaced on re-upload |
| 15 | Rate limits / cost | No extra API calls (prompt-only colours, in-process composite); generation budget unchanged |

## 9. Testing

**Backend (JUnit + H2, externals mocked):**
- `OrgBrandService`: hex regex matrix, max-3, dedupe, order preserved, clear-via-`[]`, per-index error keys; converter round-trip (mind the H2/PG JSON-as-TEXT note from V33).
- `OrgMediaService`: PNG magic bytes, size/dimension/aspect rejects, key shape, delete-old-after-put.
- `ConceptStudioService`: colours → packed `accentColor` string (lead + supporting); empty → null (regression guard for :195); `logoOnPosters` precedence; malformed-JSON → brandless generation.
- Composite: golden-image test — dimensions unchanged, bottom-right pixels differ from raw, scrim present for opaque logo, fallback path sets `final_url = raw_url` + `FAILED` status.
- Flyway V38 applies; defaults correct on existing rows. Controller tests: auth, org-scoping (no cross-org leak), error envelope.

**Frontend (Vitest + RTL):**
- `BrandTab`: select/deselect/cap/order badges, autosave sends one full-state PUT, per-index error highlighting, a11y roles/labels/keyboard.
- Upload: client validation, preview, error states. Nudge: empty-brand-only display, both mount points, localStorage dismissal, navigation.
- Studio: toggle default/disabled/sent; on-brand dots; `useBrandBook` invalidation after PUT/upload.

**Contract:** post-deploy `api:check` green; manual `types.ts` ↔ `generated-types.ts` reconciliation in each FE PR.

**Smoke:** brand fully set → generate with toggle on → logo bottom-right with scrim + colour-led palette; toggle off → no logo; empty brand → byte-identical behavior to baseline.

## 10. Open questions for the product owner

1. **SVG:** Phase 1 rejects SVG (screenshot microcopy said "PNG / SVG"). Accepting-but-ignoring SVG creates a dead state, and rasterizing needs a new dependency (Batik) plus XML security hardening. OK to ship PNG-only and revisit?
2. **`brand_name` on posters:** stored and shown in the brand book, but not injected into poster text in Phase 1 (text is model-native; more text = more text-gate surface). The original helper copy promised posters/event pages/email footers — copy softened to "Shows on your posters." only once name injection ships, or should name injection be in scope now?
3. **Remaining preset swatch hexes:** 3 confirmed from the design; finalize the other 4–5 against theme tokens at build time, or do you have exact values from the prototype?
