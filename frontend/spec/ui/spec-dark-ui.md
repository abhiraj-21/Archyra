# Spec: Dark theme (the light theme stays exactly as it is)

Location: `frontend/spec/ui/spec-dark-ui.md`. **Frontend only. No backend change.**

Read first: `CLAUDE.md`, `frontend/CLAUDE.md` (hooks only, tokens only, Hallmark Coral, `Blueprint` banned, no mocks, `npm run verify`), `frontend/DESIGN-NOTES.md` (it currently says "light only"; this spec changes that line), `src/styles/{tokens,index,base,components}.css`, `src/features/landing/landing.css`, `scripts/check-tokens.mjs`, `scripts/capture`, `scripts/e2e`.

## 1. Goal and the one hard rule
Add a dark colourway of the existing Coral theme with a Light / Dark / System switch.
**Rule: the light theme must not change at all.** Not a colour, a font, a size, a radius, a shadow, a texture or a layout. Dark is the same design in different colours, plus the few tweaks that dark surfaces need. Fonts, spacing, type scales, radii, motion, layout, copy, glyph shapes and dash patterns are shared and identical in both themes.

## 2. Decisions (binding)
| Area | Decision |
| --- | --- |
| Mechanism | `data-theme="light"` or `"dark"` on `<html>`. Dark values are an override block `:root[data-theme="dark"] { … }` **in `src/styles/tokens.css`**, the only file allowed to hold colours. Existing `:root` values are not edited. |
| Default | **Light**, so nobody sees a change unless they choose it. Choices: Light, Dark, System (follows `prefers-color-scheme` live). |
| Persistence | `localStorage` key `archyra.theme` (`light` / `dark` / `system`), a harmless preference wrapped in try/catch. Not tied to the account; no backend field. |
| No flash | A tiny **external** script file loaded in `<head>` before the bundle (not inline, so a future CSP stays strict) sets `data-theme` from storage and `matchMedia` before first paint. It contains no colours. |
| Switch UI | (1) **Settings, new "Appearance" section** with a three-option segmented control (reuse `.segmented`, radio semantics). (2) A compact icon button in the app shell header and on the landing and auth headers that toggles Light/Dark (labelled, `aria-pressed` style, tooltip). The icons are inline SVG (sun, moon) using `currentColor`. |
| Palette direction | Warm neutral, dark, hue about 35 to 55 like the light paper and ink. **Not blue-black, not pure black, no grid, dot or cross pattern** (Blueprint ban applies). Coral stays the single accent. |
| Colour scheme | `color-scheme: light` in light and `color-scheme: dark` in dark, so native controls, scrollbars and autofill follow. Update `<meta name="theme-color">` per theme. |
| Images | The seven landing screenshots (`hero.png`, `workspace-preview.png`, …) are light-mode product shots. In dark they stay as they are, shown inside their existing sheet frame. Do **not** invert or filter them. (Dark screenshots can be captured later with `scripts/capture`; out of scope.) |
| Textures | `cotton.svg` and `felt.svg` are tinted by a colour matrix and used only on the landing desk and the auth sheet. In dark, use plain token surfaces for those two (texture off) rather than a muddy tinted texture. Light keeps the textures untouched. |

## 3. Token mapping
Only **semantic roles** are redefined; component CSS keeps using `var(--…)`. Start from this mapping and tune by eye and by the contrast check below; record final values in `DESIGN-NOTES.md`.

| Role | Light (unchanged) | Dark intent |
| --- | --- | --- |
| `--color-paper`, `-2`, `-3` | warm off-white steps | warm near-black steps, about L 15 to 22%, chroma ≤ 0.01 |
| `--color-cotton` (panels, cards) | lightest | one step **lighter** than paper (raised surfaces are lighter in dark) |
| `--color-desk`, `--color-canvas` | slightly darker than paper | slightly darker than paper; graph canvas distinct from cards |
| `--color-ink`, `-2`, `--color-print` | near-black text | near-white warm text (about L 93 to 96%), never pure white |
| `--color-muted` | mid grey text | mid-light grey, at least 4.5:1 on paper and cotton |
| `--color-rule`, `-2` | light hairlines | low-contrast light hairlines on dark (visible but quiet) |
| `--color-accent`, `-accent-2`, `-focus` | coral family | coral tuned for dark: accent keeps its hue; accent-2 and focus lightened so links, outlines and focus rings reach 4.5:1 (3:1 for the focus ring) |
| `--color-accent-ink` | text on accent | stays dark text on coral fills |
| `--color-board`, `-board-type`, `-muted`, `-rule` | **dark** board with light text (primary buttons, selected segments, landing close band) | **inverted** so primary actions stay the strongest element: light board, dark type. The landing close band may stay a dark board with a slightly raised tone. |
| `--color-error`, `-success`, `-warn` and `*-soft` | dark inks on pale fills | lighter inks on deep, low-chroma fills; status is still an outline, never colour alone |
| `--color-scrim` | dark at 38% | darker, about 60% |
| `--shade`, `--lit`, `--deep` and the `--edge-*`, `--cast-*`, `--press-groove`, `--shadow-card` shadows | warm shadows | rebuilt for dark: no bright top highlight, a subtler 1px light edge, shadows made from the `--deep` black at lower alpha. Same geometry (offsets, blurs), only colours change. |
| Graph: `--color-node-*`, `-soft`, `--color-edge-*`, `--color-minimap-mask` | printing inks on warm paper | the same **hues** (plum, moss, ochre, slate-teal, umber, warm grey), lightened to at least 4.5:1 on the dark card; the soft fills become deep tints. Glyph shapes, dash patterns and markers are untouched. Keep every node type distinguishable from every other in dark, including for colour-blind viewers (the glyph/dash cues already guarantee it). |

Rules:
- Components must not branch on the theme. If a component needs a different value in dark, introduce a **new semantic token** (defined in both themes) instead of a `[data-theme]` selector in component CSS. The only `[data-theme]` selectors allowed outside `tokens.css` are the texture switch on the landing desk and auth sheet, the theme-icon swap, and the `meta theme-color` logic.
- No literal colours anywhere else. `check:tokens` keeps passing. Extend it to also fail if a `[data-theme="dark"]` block defines fewer variables than the light `:root` colour block (parity check), listing the missing ones.
- React Flow: minimap and controls via the existing CSS variables; the `<Background>` is still never rendered. Check the Inspector, Legend, ScopeControl, edge labels, selected and hover states, dragging handles.
- Markdown renderer, code excerpts in Ask AI sources, chips, notices, toasts, dialogs, menus, tables, empty states, skeletons and the progress bar are covered by tokens; verify each in dark.

## 4. Implementation
1. **Baseline first.** Before any change: capture the light theme with `scripts/capture` for every screen (landing, sign in, register, verify/reset pages, Dashboard, Repositories, Overview, Architecture with module and class views, Files, Relationships, Ask AI, Usage, Settings, Admin, 404) at 1440 and 390 widths and store them under `scripts/e2e/__baseline__/` (committed). Also save a JSON snapshot of the computed value of **every token** in `:root` as `src/styles/tokens.light.snapshot.json`.
2. Add the dark override block, the new semantic tokens (defined in both themes with the light value equal to what the component used before, so light does not change), and the `theme-color` values.
3. `lib/theme.ts` (pure: read, write, resolve, subscribe to `matchMedia`), `hooks/useTheme.ts`, `components/ThemeToggle.tsx` and `features/settings/Appearance.tsx`. Components use the hook only. The external pre-paint script lives in `public/theme-init.js` (or the Vite equivalent) and shares its tiny logic with `lib/theme.ts` by test, not by import.
4. Wire the toggle into the app shell, landing and auth headers, and the Appearance section in Settings.
5. Texture switch for the landing desk and auth sheet; confirm light is byte-identical in appearance.
6. Contrast check script `scripts/check-contrast.mjs` (run in `npm run verify`): converts the OKLCH tokens, checks the pairs below in **both** themes and prints a table; fails under the thresholds. Pairs: ink, ink-2, muted on paper, paper-2, cotton; accent-2 (links and selection outline) on paper and cotton; board-type on board; accent-ink on accent; error, success, warn ink on their soft fills; every node ink on its soft fill and on cotton; edge inks on canvas (3:1 for lines); focus on paper and cotton (3:1). Thresholds 4.5:1 text, 3:1 non-text. **Light thresholds must pass as they do today; if a light pair currently fails, record it as an existing exception and do not change light colours.**
7. Docs: `DESIGN-NOTES.md` (replace "light only" with the dual-colourway decision and the final dark palette table), `frontend/CLAUDE.md` (theme rules, parity check, "no component branches on the theme", how to add a token in both themes), `Architecture.md` (frontend section), and the Theme line in root `CLAUDE.md` if it says light only.

## 5. Tests
- **Light unchanged (the key test):** (a) a unit test compares the current computed light tokens with `tokens.light.snapshot.json` (fails on any difference); (b) Playwright `toHaveScreenshot` against the committed baselines in light, with a zero-pixel tolerance on the whole page. Run these at the end and show the result.
- Unit: `lib/theme.ts` (stored value, invalid value falls back to light, `system` resolves from `matchMedia` and reacts to changes, storage unavailable), error-free when `localStorage` throws.
- E2E (`scripts/e2e/theme.spec.ts`): default is light with no storage; the header toggle switches to dark and the choice survives reload **with no flash** (assert `data-theme` is already set at `DOMContentLoaded`); Settings Appearance selects Light, Dark, System and reflects the stored value; System follows an emulated `prefers-color-scheme` change; the toggle is keyboard operable and labelled; every main screen renders in dark without console errors, with no element using a light-only literal (computed `background-color` of `body`, `.panel` and the primary button differs from light), and `color-scheme` is `dark`.
- Visual review artefact: generate dark screenshots for the same screens into `scripts/e2e/__dark-preview__/` (not asserted, not committed) and list the paths in the final message so the owner can review them.
- `npm run verify` (including `check:tokens` and the new contrast and parity checks) passes.

## 6. Acceptance criteria
1. Light looks pixel-identical to before: the snapshot and screenshot tests pass with zero difference.
2. Dark is available through the header toggle and Settings > Appearance, defaults to Light, remembers the choice and follows the system when set to System, with no flash on load.
3. Dark is a warm neutral dark with the coral accent: no blue-black, no pure black or white, no patterns; primary buttons remain the strongest element; focus rings are visible.
4. Every screen, including the graph (nodes, edges, minimap, inspector, legend, scope control), Ask AI (chat, sources, guide), dialogs, toasts, forms and the admin area, is legible in dark and meets the contrast thresholds.
5. Graph node types remain distinguishable (colour plus glyph, edges by dash and marker) in dark.
6. Colours exist only in `tokens.css`; no component branches on the theme; parity check passes.
7. No backend change, no new dependency unless unavoidable (state why in the commit body).
8. Docs updated as in section 4.

## 7. Workflow
Do not commit unless I explicitly prompt you to.