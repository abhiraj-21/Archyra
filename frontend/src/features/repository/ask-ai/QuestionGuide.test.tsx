import { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { buildExamples } from '@/lib/askGuide';
import { GuideToggle, QuestionGuide } from './QuestionGuide';

const examples = buildExamples({
  nodes: [
    { id: 'module:app.web', type: 'MODULE', name: 'app.web', test: false },
    { id: 'file:web/A.java', type: 'FILE', name: 'A.java', file: 'web/A.java', test: false },
    { id: 'class:Service', type: 'CLASS', name: 'Service', file: 'web/A.java', test: false },
    { id: 'class:Web', type: 'CLASS', name: 'Web', file: 'web/A.java', test: false },
  ],
  edges: [
    { source: 'module:app.web', target: 'file:web/A.java', type: 'CONTAINS' },
    { source: 'class:Web', target: 'class:Service', type: 'DEPENDS_ON' },
  ],
});

describe('the question guide', () => {
  afterEach(cleanup);

  it('shows the groups, the real names, the things that will not work and the honest counting note', () => {
    render(<QuestionGuide examples={examples} usage={{ text: '13 of 15 questions left this week', tone: 'normal', allowance: 'The free plan includes 15 free questions per week, and they reset every Monday.' }} onExample={() => undefined} />);

    expect(screen.getByRole('heading', { name: 'Questions that work well' })).toBeTruthy();
    for (const group of ['The project', 'How things work', 'Relationships', 'Explain code']) expect(screen.getByRole('heading', { name: group })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'What classes depend on `Service`?' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'What is the role of the `app.web` module?' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: /Questions that won.t work, or will waste one of your questions/ })).toBeTruthy();
    const note = screen.getByText(/A question that reaches the AI uses one of your questions/);
    expect(note.textContent).toContain('isn\'t counted');
    expect(note.textContent).toContain('13 of 15 questions left this week'); // the server's number, never a literal in the guide
    expect(note.textContent).toContain('15 free questions per week');
  });

  it('fills the composer on a click and never sends', () => {
    const onExample = vi.fn();
    render(<QuestionGuide examples={examples} usage={null} onExample={onExample} />);

    fireEvent.click(screen.getByRole('button', { name: 'Explain `Service`' }));

    expect(onExample).toHaveBeenCalledExactlyOnceWith('Explain `Service`');
  });

  it('marks placeholder names as such', () => {
    render(<QuestionGuide examples={buildExamples(undefined)} usage={null} onExample={() => undefined} />);
    expect(screen.getByText(/placeholders until this repository/)).toBeTruthy();
    cleanup();
    render(<QuestionGuide examples={examples} usage={null} onExample={() => undefined} />);
    expect(screen.queryByText(/placeholders until this repository/)).toBeNull();
  });

  it('has nothing in the counting note when the usage has not loaded', () => {
    render(<QuestionGuide examples={examples} usage={null} onExample={() => undefined} />);
    expect(screen.getByText(/A question that reaches the AI/).textContent).not.toMatch(/\d+ of \d+/);
  });
});

function Panel() {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <GuideToggle open={open} controls="guide" onToggle={() => setOpen((v) => !v)} />
      {open ? (
        <div id="guide">
          <QuestionGuide examples={examples} usage={null} onExample={() => undefined} />
        </div>
      ) : null}
    </div>
  );
}

describe('the "What to ask" panel', () => {
  afterEach(cleanup);

  it('is a disclosure button that opens and closes the guide, by mouse or keyboard', () => {
    render(<Panel />);
    const toggle = screen.getByRole('button', { name: 'What to ask' });

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.getAttribute('aria-controls')).toBe('guide');
    expect(screen.queryByRole('heading', { name: 'Questions that work well' })).toBeNull();

    toggle.focus();
    expect(document.activeElement).toBe(toggle); // reachable by Tab: a real <button>
    fireEvent.click(toggle); // Enter and Space on a button are clicks
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(within(document.getElementById('guide') as HTMLElement).getByRole('heading', { name: 'Questions that work well' })).toBeTruthy();

    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document.getElementById('guide')).toBeNull();
  });

  it('is a native button, so Enter and Space operate it', () => {
    render(<Panel />);
    const toggle = screen.getByRole('button', { name: 'What to ask' });
    expect(toggle.tagName).toBe('BUTTON');
    expect(toggle.getAttribute('type')).toBe('button');
    expect((toggle as HTMLButtonElement).disabled).toBe(false);
  });
});
