# Archyra brag plan

**What:** Paste a public GitHub repo URL, Archyra builds a code graph and shows it as an explorable architecture diagram.
**For:** developers dropped into a codebase they didn't write.
**Angle:** "A repo you didn't write. Where does it all connect?" → paste a URL → the hairball turns into layers.
**Tone:** default (punchy, clean, soft coral wipes on the beat). Visual identity: Archyra's own Coral tokens (warm paper, ink, coral accent), General Sans + Geist Mono, the real logo SVG and real captured screenshots of spring-petclinic.
**Share line:** The architecture behind your code.

Format: landscape 1920×1080, 30 fps, 20.0 s (600 frames). Music: 120 BPM, 1 beat = 0.5 s, every cut on a beat, 40 beats / 10 bars. Key A minor (Am – F – C – G, one chord per bar).

## Storyboard

| # | Time | Beat | Scene | On screen |
|---|---|---|---|---|
| 1 | 0.0–3.0 | 0–6 | **Hook** | The real class graph of spring-petclinic as a hairball, drifting. "A repo you didn't write." (0.3 s) then "Where does it all connect?" (1.5 s). Wipe out on beat 6. |
| 2 | 3.0–8.0 | 6–16 | **Paste, press, progress** | Heading "Paste a public GitHub repository." (real landing copy). `github.com/spring-projects/spring-petclinic` types on the 16th-note grid (3.5–5.0, ticks are melodic), cursor clicks *Analyze* at 5.0. Progress card with the real step names (Queued, Cloning, Parsing, Analysing, Building the graph), one step per beat, ends on "Completed". |
| 3 | 8.0–13.0 | 16–26 | **Reveal: modules** | Real module graph (model → owner, vet → petclinic) revealed top-down, one rank per beat ("foundations on top"). Left: "Modules, classes, methods, and how they depend on each other." |
| 4 | 13.0–17.0 | 26–34 | **Explore** | Real workspace screenshot, camera moves from the graph to the inspector. Caption: "Click any class. See what it depends on, and what depends on it." Tabs Overview / Architecture / Files / Relationships / Ask AI visible in the shot. |
| 5 | 17.0–20.0 | 34–40 | **Punchline** | Logo mark + "Archyra" + "The architecture behind your code." + "Open source · MIT · Run it yourself". Settled from 18.0 s to the end. |

Transitions: a coral panel sweeps across (0.33 s) and clears on beats 6, 16, 26, 34 (cuts happen under the panel).

## Sound

Pad + sparse plucks over the hook, kick on every beat from 3.0 s, soft offbeat hats, bass root per bar, an A-minor pentatonic arpeggio from 8.0 s. Typing ticks are plucks on the 16th grid in key; the click, wipes and the final chime are in the same key, low in the mix. Everything is computed from one beat grid in `work/make_audio.py`. Final hit on beat 34 (17.0 s), chime chord on the outro, 0.6 s fade out. Frame 0 is the poster (the module-graph frame at 10.5 s).

## Privacy

The screenshots are from the project's own captured assets (spring-petclinic, demo account "Archyra Demo"). The progress screen is rebuilt in HTML because the captured one shows a personal display name in its sidebar; it shows no user data.
