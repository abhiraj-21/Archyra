# Archyra frontend: design notes

## Theme

- **Coral** (Hallmark), chosen by the project owner on 2026-11-18 from three dealt candidates. Seed `precise-trustworthy-developer-tool-cf7d·90d8fbd1·2026-11-18·r1`, genre modern-minimal.
- **Mode:** two colourways of the same Coral design, **Light** (the original, the default) and **Dark** (added 2026-11-19, see "Dark colourway" below), chosen with Light / Dark / System. Coral itself defines no dark colourway: the dark one is derived from its tokens (same hues, same fonts, same geometry), and the light one is guarded so it cannot drift.
- **Candidates offered:** Console (dark graphite, Host Grotesk + Atkinson Hyperlegible Mono), Almanac (slate stock, Hanken Grotesk + Newsreader + IBM Plex Mono), Coral (warm grey paper, General Sans + Geist Mono).
- **Discarded before the offer** under the ban: Blueprint (dealt first), Cobalt (ASCII field on a character cell, dot-grid fallback), Vantage (blue-black ground, corner-tick frames), Furrow (survey-map contours).
- **`Blueprint` and blueprint/engineering-grid aesthetics are banned.** No cyan or blue drafting paper, no grid, cross or dot pattern on any page, canvas or image (React Flow's `<Background>` is never rendered), no rulers, compasses, dimension callouts or white-on-blue line art.

## Tokens

- **The only token file is `src/styles/tokens.css`**: Coral's tokens from `theme.mjs scaffold coral` (the light `:root` block), the self-hosted `@font-face` rules, the graph semantic colours, and the `:root[data-theme="dark"]` override block. No other file in `src/` may contain a colour value or a font name; `npm run check:tokens` enforces it (hex, `rgb()`/`hsl()`/`oklch()` and kin, `font-family` literals, stock Tailwind palette classes, arbitrary colour utilities).
- **Tailwind** reads the tokens through `@theme inline reference` in `src/styles/index.css`: the stock palette, fonts, radii and shadows are reset, so `bg-paper`, `text-muted`, `p-lg`, `rounded-card` and so on are the only colour, spacing and radius utilities. `reference` stops Tailwind from re-emitting the variables, so the token file stays their single definition.
- **Fonts** are self-hosted in `src/styles/fonts/`: General Sans variable (ITF Free Font License) and Geist Mono 400/500 (SIL OFL). No third-party font request at runtime.
- **Two type scales:** the page scale (fluid, the landing page) and Hallmark's fixed app scale (ratio 1.2) under `[data-scope="app"]` for the signed-in screens and auth.

## Deviations from the Coral theme file, with reasons

- **Controls in the app are 36 to 40px**, not Coral's 48px marketing cards: Hallmark's app-screen rules (dense tool UI, 44px hit target). They keep the letterpress edge (`--edge-stock`, `--cast-stock`) without the cotton texture.
- **Selection uses the deep coral (`--color-accent-2`)** as an outline. Coral reserves coral for the settled state, links and focus; Hallmark's app rules let the accent mark the current selection, and a selected node needs a signal that is not one of the graph's semantic inks.
- **Graph semantic inks** are added to the token file: plum (class), moss (interface), ochre (method), slate-teal at low chroma (function), umber (module), warm grey (file). Each is 4.5:1 or better on cotton. Coral forbids a blue *signal*; the function ink is a muted teal used as an identifying ink, never as a signal. Colour is never the only cue: node types also differ by glyph shape, edges by dash pattern and end marker.
- **Textures** (`src/styles/textures/cotton.svg`, `felt.svg`) are generated SVG filters tinted by a colour matrix, used only on the landing hero desk, the landing sheets and the auth sheet. App screens stay plain. In dark both are off (`--tex-cotton: none; --tex-desk: none`): the tinted noise turns muddy on a dark ground, so those surfaces are plain token colours.

## Graph layout

- **dagre** (`@dagrejs/dagre`), run in a Web Worker. It is a classic layered (Sugiyama) layout with `rankdir: TB`, which is exactly the controller → service → repository reading the product needs; it is small and synchronous, so a worker keeps big graphs (methods included) from freezing the page. ELK lays out better with ports and compound nodes, but it is several times larger and asynchronous for no gain on this graph shape.
- React Flow renders with `onlyRenderVisibleElements`; the canvas is a plain `--color-canvas` surface.

## Dark colourway

Decided in `spec/ui/spec-dark-ui.md`. **The light theme does not change at all**: not a colour, font, size, radius, shadow, texture or layout. Dark is the same design in different colours, plus the few role changes a dark surface needs.

- **Mechanism.** `data-theme="light"` or `"dark"` on `<html>`. The dark values are an override block `:root[data-theme="dark"]` in `src/styles/tokens.css`, below the untouched light `:root`; it also sets `color-scheme: dark` (native controls, scrollbars, autofill) and the light block sets `color-scheme: light`. Fonts, spacing, type scales, radii, motion, layout, copy, glyph shapes and dash patterns are shared and identical.
- **Choice.** Light (the default, so nobody sees a change unless they choose it), Dark, or System (follows `prefers-color-scheme` live). Kept in `localStorage` under `archyra.theme`, wrapped in try/catch; not tied to the account, no backend field. `public/theme-init.js`, a separate classic script loaded in `<head>` before the bundle (not inline, so a future CSP can stay strict), sets `data-theme` before first paint, so a stored dark choice never flashes light; it holds no colours. `lib/theme.ts` has the same three rules, and `lib/theme.test.ts` runs the script against them. The switch is the header button (sun / moon, `aria-pressed`, tooltip) on the landing page, the auth pages and the app shell, and Settings, Appearance (a three-option radio group on `.segmented`). `<meta name="theme-color">` is set from the page-colour token by `lib/theme.ts`.
- **Palette direction.** A warm neutral dark, hue 35 to 65, chroma at most 0.012, never blue-black, never pure black or white, no grid, dot or cross pattern (the Blueprint ban applies to dark too). Coral stays the single accent. Raised surfaces are lighter than the page (cotton above paper), the way dark UI reads depth.
- **Primary actions invert.** The board (`--color-board`: primary buttons, the chosen segment) is a light warm board with dark type in dark, so the primary action stays the strongest element on the page. The landing close band is the exception: it stays a dark band a step above the page (`--color-band`, `--color-band-type`).
- **Shadows.** Same geometry (offsets, blurs) as light. No bright top highlight: a quiet 1px light edge (`--lit` at 7 to 13%) and shadows made from `--deep` at lower alpha.
- **Graph.** The same hues in dark (plum, moss, ochre, slate-teal, umber, warm grey), lightened to at least 4.5:1 on the dark card; the soft fills are deep tints; edge inks are at least 3:1 on the canvas. Glyph shapes, dash patterns and markers are untouched, so every node and edge type stays distinguishable without colour. The seven landing screenshots are light-mode product shots and stay as they are inside their sheet frame (never inverted or filtered); dark screenshots can be captured later with `scripts/capture`.
- **Rules that keep it honest.** Components never branch on the theme: a role a component needs in both colourways is a new semantic token defined in both blocks (this work added `--color-board-etch`, `--color-band`, `--color-band-type`, each equal in light to what the component read before). The only `[data-theme]` selector outside `tokens.css` is the toggle's sun / moon swap in `components.css`. `npm run check:tokens` also fails when the dark block lacks a colour token the light block has (parity), or defines one it does not. `src/styles/tokens.test.ts` compares every light token with `src/styles/tokens.light.snapshot.json` (recorded before the dark theme existed) and also encodes the palette rules above. `npm run check:contrast` checks 53 pairs in both themes. `scripts/e2e/visual.spec.ts` compares 42 full-page light screenshots (`scripts/e2e/__baseline__/`) with zero tolerance; the theme controls, which did not exist when the baseline was recorded, are marked `data-theme-ui` and removed from those screenshots, so every other pixel is held to the baseline.

### Contrast

`npm run check:contrast` converts the OKLCH tokens to sRGB and checks the pairs the UI draws in **both** themes: 4.5:1 for text, 3:1 for lines and rings. Every pair passes in both, so the list of existing light exceptions is empty. Lowest dark ratios: `edge-contains` on canvas 3.32 (line, needs 3), `edge-references` 4.64, `edge-imports` 5.91, `node-file` on its fill 6.44; ink on paper 16.5, muted on cotton 7.2, links (`accent-2`) on paper 10.0, focus ring on paper 7.9.

### Final dark palette

**Surfaces**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-paper` | `oklch(97% 0.006 55)` | `oklch(16% 0.006 50)` |
| `--color-paper-2` | `oklch(94.5% 0.007 55)` | `oklch(18.5% 0.007 50)` |
| `--color-paper-3` | `oklch(91.5% 0.009 55)` | `oklch(22.5% 0.008 50)` |
| `--color-cotton` | `oklch(98.8% 0.0045 80)` | `oklch(20.5% 0.007 50)` |
| `--color-desk` | `oklch(94.3% 0.008 64)` | `oklch(13.5% 0.006 50)` |
| `--color-canvas` | `oklch(95.6% 0.007 60)` | `oklch(13.8% 0.006 50)` |

**Text and lines**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-ink` | `oklch(21% 0.012 38)` | `oklch(94.5% 0.008 60)` |
| `--color-ink-2` | `oklch(29% 0.013 40)` | `oklch(87% 0.009 60)` |
| `--color-print` | `oklch(23% 0.012 38)` | `oklch(93% 0.008 60)` |
| `--color-muted` | `oklch(47% 0.013 45)` | `oklch(72% 0.011 55)` |
| `--color-rule` | `oklch(87% 0.009 55)` | `oklch(28% 0.008 50)` |
| `--color-rule-2` | `oklch(81% 0.011 55)` | `oklch(36% 0.01 50)` |

**Accent**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-accent` | `oklch(74% 0.125 30)` | `oklch(74% 0.125 30)` |
| `--color-accent-ink` | `oklch(21% 0.012 38)` | `oklch(21% 0.012 38)` |
| `--color-accent-2` | `oklch(54% 0.165 29)` | `oklch(80% 0.11 29)` |
| `--color-focus` | `oklch(54% 0.17 30)` | `oklch(74% 0.15 30)` |
| `--color-paid` | `oklch(55.5% 0.15 33)` | `oklch(74% 0.135 33)` |

**Board (primary button, chosen segment) and band**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-board` | `oklch(22% 0.011 40)` | `oklch(92% 0.008 65)` |
| `--color-board-type` | `oklch(96.5% 0.006 70)` | `oklch(20% 0.012 40)` |
| `--color-board-muted` | `oklch(78% 0.01 60)` | `oklch(40% 0.012 50)` |
| `--color-board-rule` | `oklch(34% 0.012 40)` | `oklch(80% 0.01 60)` |
| `--color-board-etch` | `oklch(21% 0.012 38)` | `oklch(80% 0.01 60)` |
| `--color-band` | `oklch(22% 0.011 40)` | `oklch(19% 0.007 50)` |
| `--color-band-type` | `oklch(96.5% 0.006 70)` | `oklch(94.5% 0.008 60)` |

**Status**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-error` | `oklch(52% 0.19 27)` | `oklch(76% 0.13 27)` |
| `--color-error-soft` | `oklch(95.5% 0.025 27)` | `oklch(26% 0.045 27)` |
| `--color-success` | `oklch(50% 0.12 150)` | `oklch(76% 0.12 150)` |
| `--color-success-soft` | `oklch(95.5% 0.03 150)` | `oklch(25.5% 0.04 150)` |
| `--color-warn` | `oklch(52% 0.12 70)` | `oklch(80% 0.12 75)` |
| `--color-warn-soft` | `oklch(95% 0.035 80)` | `oklch(26% 0.04 80)` |

**Graph nodes**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-node-class` | `oklch(45% 0.1 350)` | `oklch(76% 0.11 350)` |
| `--color-node-class-soft` | `oklch(95% 0.022 350)` | `oklch(28% 0.05 350)` |
| `--color-node-interface` | `oklch(46% 0.09 140)` | `oklch(76% 0.1 140)` |
| `--color-node-interface-soft` | `oklch(95% 0.03 140)` | `oklch(27% 0.045 140)` |
| `--color-node-method` | `oklch(50% 0.1 70)` | `oklch(78% 0.11 70)` |
| `--color-node-method-soft` | `oklch(95.5% 0.035 80)` | `oklch(28% 0.045 75)` |
| `--color-node-function` | `oklch(46% 0.06 195)` | `oklch(76% 0.07 195)` |
| `--color-node-function-soft` | `oklch(95% 0.02 195)` | `oklch(27% 0.03 195)` |
| `--color-node-module` | `oklch(40% 0.05 55)` | `oklch(74% 0.06 55)` |
| `--color-node-module-soft` | `oklch(93.5% 0.014 55)` | `oklch(27% 0.025 55)` |
| `--color-node-file` | `oklch(47% 0.013 45)` | `oklch(72% 0.012 50)` |
| `--color-node-file-soft` | `oklch(94.5% 0.007 55)` | `oklch(25% 0.008 55)` |
| `--color-node-repository` | `oklch(29% 0.013 40)` | `oklch(88% 0.01 60)` |
| `--color-node-repository-soft` | `oklch(91.5% 0.009 55)` | `oklch(28% 0.01 55)` |

**Graph edges**

| Token | Light (unchanged) | Dark |
| --- | --- | --- |
| `--color-edge-depends` | `oklch(33% 0.013 40)` | `oklch(82% 0.012 55)` |
| `--color-edge-calls` | `oklch(50% 0.1 70)` | `oklch(74% 0.1 70)` |
| `--color-edge-imports` | `oklch(52% 0.013 45)` | `oklch(64% 0.012 50)` |
| `--color-edge-extends` | `oklch(45% 0.1 350)` | `oklch(70% 0.1 350)` |
| `--color-edge-implements` | `oklch(46% 0.09 140)` | `oklch(70% 0.09 140)` |
| `--color-edge-references` | `oklch(58% 0.012 45)` | `oklch(58% 0.012 50)` |
| `--color-edge-contains` | `oklch(60% 0.011 55)` | `oklch(50% 0.011 55)` |
| `--color-minimap-mask` | `oklch(91.5% 0.009 55 / 0.55)` | `oklch(8% 0.008 50 / 0.55)` |

**Overlays, shadows, textures**

| Token | Dark |
| --- | --- |
| `--color-scrim` | `oklch(9% 0.01 40 / 0.6)` |
| `--shade` | `10% 0.01 45` |
| `--lit` | `95% 0.008 60` |
| `--deep` | `7% 0.01 40` |
| `--shadow-card` | `0 1px 2px oklch(7% 0.01 40 / 0.4)` |
| `--edge-stock` | `inset 0 1px 0 oklch(95% 0.008 60 / 0.07), inset 0 -1px 0 oklch(7% 0.01 40 / 0.35), 0 0 0 0.5px oklch(95% 0.008 60 / 0.13), 0 1px 0 oklch(7% 0.01 40 / 0.45)` |
| `--cast-stock` | `6px 5px 10px -6px oklch(7% 0.01 40 / 0.55)` |
| `--cast-stock-lift` | `12px 10px 18px -8px oklch(7% 0.01 40 / 0.6)` |
| `--press-groove` | `inset 1px 1.5px 2px oklch(7% 0.01 40 / 0.5), inset -1px -1px 0 oklch(95% 0.008 60 / 0.08), 0 0 0 0.5px oklch(95% 0.008 60 / 0.12)` |
| `--tex-cotton` | `none` |
| `--tex-desk` | `none` |

