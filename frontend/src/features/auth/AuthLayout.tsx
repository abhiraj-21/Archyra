import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Wordmark } from '@/components/ui/Logo';
import { ThemeToggle } from '@/components/ThemeToggle';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';

/** Auth screens: one cotton sheet on the felt desk (Coral's material), the form on the sheet. */
export function AuthLayout({ title, intro, children, footer, noReferrer = false }: { title: string; intro?: ReactNode; children: ReactNode; footer?: ReactNode; noReferrer?: boolean }) {
  useDocumentTitle(title);
  return (
    <div data-scope="app" className="desk min-h-dvh">
      {noReferrer ? <meta name="referrer" content="no-referrer" /> : null}
      <div className="mx-auto flex min-h-dvh w-full max-w-[30rem] flex-col px-md py-xl">
        <header className="relative mb-xl">
          <Link to="/" className="link-plain inline-flex" aria-label="Archyra home">
            <Wordmark />
          </Link>
          <ThemeToggle className="absolute right-0 top-1/2 -translate-y-1/2" />
        </header>
        <main className="stock grid gap-lg p-lg sm:p-xl">
          <div className="grid gap-xs">
            <h1 className="text-2xl">{title}</h1>
            {intro ? <div className="text-muted">{intro}</div> : null}
          </div>
          {children}
        </main>
        {footer ? <div className="mt-lg text-sm text-muted">{footer}</div> : null}
      </div>
    </div>
  );
}
