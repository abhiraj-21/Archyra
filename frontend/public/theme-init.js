/* Sets <html data-theme> before the first paint so a stored dark choice never flashes light.
 * A separate file, not an inline script, so a future Content-Security-Policy can stay strict (no 'unsafe-inline').
 * It holds no colours. Its three rules are the same as src/lib/theme.ts (readPreference, parsePreference, resolveTheme);
 * src/lib/theme.test.ts runs this file and checks the two agree, since it cannot import from the bundle. */
(function () {
  var theme = 'light';
  try {
    var stored = window.localStorage.getItem('archyra.theme');
    if (stored === 'dark') theme = 'dark';
    else if (stored === 'system' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) theme = 'dark';
  } catch (e) {
    /* storage blocked: the default, light */
  }
  document.documentElement.setAttribute('data-theme', theme);
})();
