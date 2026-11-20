import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { groupTechnologies } from '@/lib/technologies';
import { TechnologyGroups } from './TechnologyGroups';

const many = (n: number) => Array.from({ length: n }, (_, i) => ({ name: `lib-${String(i + 1).padStart(2, '0')}`, category: 'LIBRARY' }));

describe('TechnologyGroups', () => {
  afterEach(cleanup);

  it('shows a label and the names as text, with no button for a short group', () => {
    render(<TechnologyGroups groups={groupTechnologies([{ name: 'Spring Boot', category: 'BACKEND_FRAMEWORK' }, { name: 'Maven', category: 'BUILD_TOOL' }])} />);
    expect(screen.getByRole('heading', { name: 'Backend frameworks' })).toBeTruthy();
    expect(screen.getByText('Spring Boot')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Build tools' })).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows 12 of 15 with a "Show 3 more" button that opens and closes the rest', () => {
    render(<TechnologyGroups groups={groupTechnologies(many(15))} />);
    const list = screen.getByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(12);

    const button = screen.getByRole('button', { name: 'Show 3 more' });
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-controls')).toBe(list.id);

    fireEvent.click(button);
    expect(within(list).getAllByRole('listitem')).toHaveLength(15);
    const open = screen.getByRole('button', { name: 'Show fewer' });
    expect(open.getAttribute('aria-expanded')).toBe('true');

    fireEvent.click(open);
    expect(within(list).getAllByRole('listitem')).toHaveLength(12);
  });

  it('has no button at exactly 12', () => {
    render(<TechnologyGroups groups={groupTechnologies(many(12))} />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('keeps each group on its own cap', () => {
    render(<TechnologyGroups groups={groupTechnologies([...many(13), { name: 'Vitest', category: 'TESTING' }])} />);
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });
});
