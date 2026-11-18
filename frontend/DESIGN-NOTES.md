# Archyra frontend: design notes

## Theme

- **Coral** (Hallmark), chosen by the project owner on 2026-11-18 from three dealt candidates. Seed `precise-trustworthy-developer-tool-cf7d·90d8fbd1·2026-11-18·r1`, genre modern-minimal.
- **Mode:** light only. Coral defines no dark colourway, so there is no half-built dark mode.
- **Candidates offered:** Console (dark graphite, Host Grotesk + Atkinson Hyperlegible Mono), Almanac (slate stock, Hanken Grotesk + Newsreader + IBM Plex Mono), Coral (warm grey paper, General Sans + Geist Mono).
- **Discarded before the offer** under the ban: Blueprint (dealt first), Cobalt (ASCII field on a character cell, dot-grid fallback), Vantage (blue-black ground, corner-tick frames), Furrow (survey-map contours).
- **`Blueprint` and blueprint/engineering-grid aesthetics are banned.** No cyan or blue drafting paper, no grid, cross or dot pattern on any page, canvas or image (React Flow's `<Background>` is never rendered), no rulers, compasses, dimension callouts or white-on-blue line art.

## Tokens

- **The only token file is `src/styles/tokens.css`**: Coral's tokens from `theme.mjs scaffold coral`, the self-hosted `@font-face` rules, and the graph semantic colours. No other file in `src/` may contain a colour value or a font name; `npm run check:tokens` enforces it (hex, `rgb()`/`hsl()`/`oklch()` and kin, `font-family` literals, stock Tailwind palette classes, arbitrary colour utilities).
- **Tailwind** reads the tokens through `@theme inline reference` in `src/styles/index.css`: the stock palette, fonts, radii and shadows are reset, so `bg-paper`, `text-muted`, `p-lg`, `rounded-card` and so on are the only colour, spacing and radius utilities. `reference` stops Tailwind from re-emitting the variables, so the token file stays their single definition.
- **Fonts** are self-hosted in `src/styles/fonts/`: General Sans variable (ITF Free Font License) and Geist Mono 400/500 (SIL OFL). No third-party font request at runtime.
- **Two type scales:** the page scale (fluid, the landing page) and Hallmark's fixed app scale (ratio 1.2) under `[data-scope="app"]` for the signed-in screens and auth.

## Deviations from the Coral theme file, with reasons

- **Controls in the app are 36 to 40px**, not Coral's 48px marketing cards: Hallmark's app-screen rules (dense tool UI, 44px hit target). They keep the letterpress edge (`--edge-stock`, `--cast-stock`) without the cotton texture.
- **Selection uses the deep coral (`--color-accent-2`)** as an outline. Coral reserves coral for the settled state, links and focus; Hallmark's app rules let the accent mark the current selection, and a selected node needs a signal that is not one of the graph's semantic inks.
- **Graph semantic inks** are added to the token file: plum (class), moss (interface), ochre (method), slate-teal at low chroma (function), umber (module), warm grey (file). Each is 4.5:1 or better on cotton. Coral forbids a blue *signal*; the function ink is a muted teal used as an identifying ink, never as a signal. Colour is never the only cue: node types also differ by glyph shape, edges by dash pattern and end marker.
- **Textures** (`src/styles/textures/cotton.svg`, `felt.svg`) are generated SVG filters tinted by a colour matrix, used only on the landing hero desk and the auth sheet. App screens stay plain.

## Graph layout

- **dagre** (`@dagrejs/dagre`), run in a Web Worker. It is a classic layered (Sugiyama) layout with `rankdir: TB`, which is exactly the controller → service → repository reading the product needs; it is small and synchronous, so a worker keeps big graphs (methods included) from freezing the page. ELK lays out better with ports and compound nodes, but it is several times larger and asynchronous for no gain on this graph shape.
- React Flow renders with `onlyRenderVisibleElements`; the canvas is a plain `--color-canvas` surface.
