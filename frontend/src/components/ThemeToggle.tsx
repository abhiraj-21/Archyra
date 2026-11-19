import { Moon, Sun } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';

/**
 * The header button: Light <-> Dark. A toggle button (`aria-pressed` is true in dark) with a fixed accessible name and a
 * tooltip that says what a press does. Both icons are in the button and the theme picks the one that shows (components.css),
 * so the right icon is there from the first paint. For System, use Settings, Appearance.
 */
export function ThemeToggle({ className }: { className?: string }) {
  const { resolved, toggle } = useTheme();
  const dark = resolved === 'dark';
  return (
    <button
      type="button"
      className={`btn btn-quiet btn-icon theme-toggle ${className ?? ''}`}
      data-theme-ui
      aria-pressed={dark}
      aria-label="Dark theme"
      title={dark ? 'Switch to the light theme' : 'Switch to the dark theme'}
      onClick={toggle}
    >
      <Moon className="theme-icon theme-icon--moon" size={18} aria-hidden="true" />
      <Sun className="theme-icon theme-icon--sun" size={18} aria-hidden="true" />
    </button>
  );
}
