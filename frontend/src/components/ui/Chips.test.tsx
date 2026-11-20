import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ANALYSIS_STATUSES } from '@/api/types';
import { StatusChip } from './Chips';

describe('StatusChip', () => {
  afterEach(cleanup);

  it('shows a restored repository as "Not analysed" with an icon, not colour alone', () => {
    const { container } = render(<StatusChip status="NOT_ANALYZED" progress={0} />);
    expect(screen.getByText('Not analysed')).toBeTruthy();
    expect(container.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    expect(container.textContent).not.toMatch(/%/); // no progress for something that is not running
  });

  it('has text and an icon for every status', () => {
    for (const status of ANALYSIS_STATUSES) {
      const { container, unmount } = render(<StatusChip status={status} progress={40} />);
      expect(container.textContent?.trim().length).toBeGreaterThan(0);
      expect(container.querySelector('svg')).not.toBeNull();
      unmount();
    }
  });
});
