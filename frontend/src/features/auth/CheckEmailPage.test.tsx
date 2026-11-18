import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { CheckEmailPage } from './CheckEmailPage';

function renderPage(state?: { email: string }) {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[{ pathname: '/register/check-email', state }]}>
        <CheckEmailPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('the check-your-inbox screen', () => {
  afterEach(cleanup);

  it('links to sign in and to forgot-password, whoever the address belongs to', () => {
    renderPage({ email: 'someone@example.com' });
    expect(screen.getByRole('link', { name: 'Sign in' }).getAttribute('href')).toBe('/login');
    expect(screen.getByRole('link', { name: 'reset your password' }).getAttribute('href')).toBe('/forgot-password');
  });

  it('is conditional and never says whether the address is registered', () => {
    const { container } = renderPage({ email: 'someone@example.com' });
    const text = container.textContent ?? '';
    expect(text).toMatch(/If this address can be registered/);
    expect(text).not.toMatch(/already registered/i);
    expect(text).not.toMatch(/already (exists|in use|taken)/i);
    expect(text).not.toMatch(/(is|are) (not )?registered/i);
    expect(text).not.toMatch(/no account/i);
  });

  it('still offers the resend action', () => {
    renderPage({ email: 'someone@example.com' });
    expect(screen.getByRole('button', { name: /send|resend/i })).toBeTruthy();
  });
});
