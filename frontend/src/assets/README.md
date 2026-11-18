# Assets

Every PNG here is captured from the real running app and backend by `npm run assets:capture`
(`scripts/capture/capture.mjs`, Playwright Chromium at deviceScaleFactor 2). Nothing is drawn, mocked or hotlinked.
To replace one, overwrite the file with the same name: pages import them statically and size them with CSS
(`aspect-ratio` + `object-fit`), so other dimensions do not break the layout.

Regenerate: start the backend and `npm run dev`, put a test account in `.env.local`
(`ARCHYRA_TEST_EMAIL`, `ARCHYRA_TEST_PASSWORD`; never the admin), then `npm run assets:capture`
(or `--only hero,files-table`). It analyses `ARCHYRA_CAPTURE_REPO` (spring-petclinic) as that user and re-runs it
once for the progress shot. Settings, menus and admin screens are never captured.

| File | Pixels | Shows | Used on |
| --- | --- | --- | --- |
| `hero.png` | 1640 x 1016 | spring-petclinic's module graph (DEPENDS_ON), cropped to the connected modules | Landing hero |
| `workspace-preview.png` | 2880 x 1800 | Full workspace: sidebar, header, tabs, class graph, inspector on OwnerController | Landing, workspace showcase |
| `graph-layered.png` | 2384 x 1230 | The Architecture canvas alone, classes joined by Depends on and Implements | Landing tour |
| `inspector-panel.png` | 704 x 1230 | Node inspector for OwnerController with relationship counts | Landing tour |
| `files-table.png` | 2880 x 1800 | Files tab with OwnerController.java open in the drawer | Landing tour |
| `relationships-browser.png` | 2880 x 1800 | Class browser with OwnerController selected | Landing tour |
| `analysis-progress.png` | 2880 x 1800 | Progress view mid-run (a real re-run) | Landing, how it works |
| `favicon.png` | 64 x 64 | The Archyra mark, rendered by the app | `index.html` (`<link rel="icon">`) |

Note: the sidebar shows the test account's display name. Rename the test account in Settings and recapture if needed.
