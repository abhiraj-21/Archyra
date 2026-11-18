import { ButtonLink } from '@/components/ui/Button';
import { LogoMark } from '@/components/ui/Logo';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useSession } from '@/hooks/sessionContext';

/**
 * The one not-found screen: unknown routes, analyses that are not yours, and /admin for anyone who is not an
 * administrator all look exactly like this, so nothing hints that something exists.
 */
export function NotFoundPage({ title = 'Page not found', message = 'There is nothing at this address. It may have moved, or the link is wrong.', inShell = false }: { title?: string; message?: string; inShell?: boolean }) {
  useDocumentTitle(title);
  const { status } = useSession();
  const home = status === 'authenticated' ? '/dashboard' : '/';
  return (
    <div data-scope="app" className={`grid place-items-center px-md ${inShell ? 'min-h-[60dvh]' : 'min-h-dvh bg-paper'}`}>
      <div className="grid max-w-[34rem] justify-items-start gap-md">
        <LogoMark size={32} className="text-muted" />
        <p className="num text-3xl text-muted" aria-hidden="true">
          404
        </p>
        <h1 className="text-2xl">{title}</h1>
        <p className="text-muted">{message}</p>
        <ButtonLink to={home} variant="primary">
          {status === 'authenticated' ? 'Go to your dashboard' : 'Go to the home page'}
        </ButtonLink>
      </div>
    </div>
  );
}
