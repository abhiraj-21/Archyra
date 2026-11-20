import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DeletionConsequences } from './DeleteAccount';

describe('DeletionConsequences', () => {
  afterEach(cleanup);

  it('says honestly what is kept: counts and repository links, never code, graphs or conversations', () => {
    const { container } = render(<DeletionConsequences />);
    const text = container.textContent?.replace(/\s+/g, ' ') ?? '';
    expect(text).toContain('Your usage is not reset');
    expect(text).toContain('the links of the repositories you had analysed');
    expect(text).toContain('return as “Not analysed” entries that you can analyse or delete');
    expect(text).toContain('No code, graph or conversation is kept');
    expect(text).not.toContain('holds no name, email address or repository');
  });
});
