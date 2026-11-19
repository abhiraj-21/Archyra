import { useId, useRef } from 'react';
import { useTheme } from '@/hooks/useTheme';
import { THEME_LABELS, THEME_PREFERENCES, type ThemePreference } from '@/lib/theme';

const HELP: Record<ThemePreference, string> = {
  light: 'Always the light theme.',
  dark: 'Always the dark theme.',
  system: 'Follows your device, and changes when it does.',
};

/** Settings, Appearance: Light | Dark | System as a radio group (arrow keys move and select, only the chosen option is in the tab order). */
export function Appearance() {
  const { preference, resolved, setPreference } = useTheme();
  const helpId = useId();
  const refs = useRef<Partial<Record<ThemePreference, HTMLButtonElement | null>>>({});

  function onKeyDown(event: React.KeyboardEvent) {
    const i = THEME_PREFERENCES.indexOf(preference);
    let next: ThemePreference | undefined;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = THEME_PREFERENCES[(i + 1) % THEME_PREFERENCES.length];
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = THEME_PREFERENCES[(i + THEME_PREFERENCES.length - 1) % THEME_PREFERENCES.length];
    else if (event.key === 'Home') next = THEME_PREFERENCES[0];
    else if (event.key === 'End') next = THEME_PREFERENCES[THEME_PREFERENCES.length - 1];
    if (!next) return;
    event.preventDefault();
    setPreference(next);
    refs.current[next]?.focus();
  }

  return (
    <div className="grid gap-xs">
      <div role="radiogroup" aria-label="Theme" aria-describedby={helpId} className="segmented w-fit" onKeyDown={onKeyDown}>
        {THEME_PREFERENCES.map((option) => (
          <button
            key={option}
            ref={(el) => {
              refs.current[option] = el;
            }}
            type="button"
            role="radio"
            aria-checked={preference === option}
            tabIndex={preference === option ? 0 : -1}
            data-theme-option={option}
            onClick={() => setPreference(option)}
          >
            {THEME_LABELS[option]}
          </button>
        ))}
      </div>
      <p id={helpId} className="text-sm text-muted">
        {HELP[preference]}
        {preference === 'system' ? ` Right now: ${resolved}.` : ''} Saved on this device only.
      </p>
    </div>
  );
}
