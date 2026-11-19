import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ThemeToggle } from '@/components/ThemeToggle';
import { Appearance } from '@/features/settings/Appearance';
import { useTheme } from './useTheme';

let dark = false;
const listeners = new Set<(e: MediaQueryListEvent) => void>();
function setDevice(next: boolean) {
  dark = next;
  act(() => listeners.forEach((fn) => fn({ matches: next } as MediaQueryListEvent)));
}

function Probe() {
  const { preference, resolved } = useTheme();
  return <output data-testid="state">{`${preference}/${resolved}`}</output>;
}
const html = () => document.documentElement.getAttribute('data-theme');
const state = () => screen.getByTestId('state').textContent;

beforeEach(() => {
  dark = false;
  listeners.clear();
  localStorage.clear();
  window.matchMedia = (() => ({
    get matches() {
      return dark;
    },
    addEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.delete(fn),
  })) as unknown as typeof window.matchMedia;
});
afterEach(() => {
  cleanup();
  document.documentElement.removeAttribute('data-theme');
});

describe('useTheme', () => {
  it('is light by default, with nothing stored, and says so on <html>', () => {
    render(<Probe />);
    expect(state()).toBe('light/light');
    expect(html()).toBe('light');
    expect(localStorage.getItem('archyra.theme')).toBeNull();
  });

  it('starts from the stored choice', () => {
    localStorage.setItem('archyra.theme', 'dark');
    render(<Probe />);
    expect(state()).toBe('dark/dark');
    expect(html()).toBe('dark');
  });

  it('follows the device live when the choice is system, and stops following when it is not', () => {
    localStorage.setItem('archyra.theme', 'system');
    render(<Probe />);
    expect(state()).toBe('system/light');
    setDevice(true);
    expect(state()).toBe('system/dark');
    expect(html()).toBe('dark');
    setDevice(false);
    expect(html()).toBe('light');
    expect(listeners.size).toBe(1); // exactly one live subscription while mounted
  });

  it('ignores the device when the choice is explicit', () => {
    localStorage.setItem('archyra.theme', 'light');
    render(<Probe />);
    setDevice(true);
    expect(html()).toBe('light');
  });
});

describe('ThemeToggle', () => {
  it('is a labelled toggle button that switches theme and remembers it', () => {
    render(<><Probe /><ThemeToggle /></>);
    const button = screen.getByRole('button', { name: 'Dark theme' });
    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(button.getAttribute('title')).toBe('Switch to the dark theme');
    fireEvent.click(button);
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.getAttribute('title')).toBe('Switch to the light theme');
    expect(html()).toBe('dark');
    expect(localStorage.getItem('archyra.theme')).toBe('dark');
    fireEvent.click(button);
    expect(html()).toBe('light');
    expect(localStorage.getItem('archyra.theme')).toBe('light');
  });

  it('still switches for this page when storage throws', () => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = () => {
      throw new Error('QuotaExceeded');
    };
    try {
      render(<ThemeToggle />);
      fireEvent.click(screen.getByRole('button', { name: 'Dark theme' }));
      expect(html()).toBe('dark');
    } finally {
      Storage.prototype.setItem = original;
    }
  });

  it('is a real button, so Enter and Space work (type=button, not a div)', () => {
    render(<ThemeToggle />);
    const button = screen.getByRole('button', { name: 'Dark theme' }) as HTMLButtonElement;
    expect(button.tagName).toBe('BUTTON');
    expect(button.type).toBe('button');
  });
});

describe('Appearance (Settings)', () => {
  it('shows the stored choice as the checked radio and changes it', () => {
    localStorage.setItem('archyra.theme', 'system');
    render(<Appearance />);
    expect(screen.getByRole('radio', { name: 'System' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('radio', { name: 'Light' }).getAttribute('aria-checked')).toBe('false');
    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(screen.getByRole('radio', { name: 'Dark' }).getAttribute('aria-checked')).toBe('true');
    expect(localStorage.getItem('archyra.theme')).toBe('dark');
    expect(html()).toBe('dark');
  });

  it('is operable by keyboard: arrows move and select, only the chosen radio is tabbable', () => {
    render(<Appearance />);
    const group = screen.getByRole('radiogroup', { name: 'Theme' });
    expect(screen.getByRole('radio', { name: 'Light' }).getAttribute('tabindex')).toBe('0');
    expect(screen.getByRole('radio', { name: 'Dark' }).getAttribute('tabindex')).toBe('-1');
    fireEvent.keyDown(group, { key: 'ArrowRight' });
    expect(localStorage.getItem('archyra.theme')).toBe('dark');
    fireEvent.keyDown(group, { key: 'ArrowRight' });
    expect(localStorage.getItem('archyra.theme')).toBe('system');
    fireEvent.keyDown(group, { key: 'ArrowRight' });
    expect(localStorage.getItem('archyra.theme')).toBe('light');
    fireEvent.keyDown(group, { key: 'End' });
    expect(localStorage.getItem('archyra.theme')).toBe('system');
  });
});
