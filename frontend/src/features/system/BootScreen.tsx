import { RotateCw } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { LogoMark } from '@/components/ui/Logo';
import { Spinner } from '@/components/ui/Spinner';

/** Shown while the session boots (csrf → refresh → /users/me) so no screen renders for the wrong user. */
export function BootScreen({ failed = false, onRetry, message }: { failed?: boolean; onRetry?: () => void; message?: string }) {
  return (
    <div data-scope="app" className="grid min-h-dvh place-items-center bg-paper px-md">
      <div className="grid justify-items-center gap-md text-center">
        <LogoMark size={40} className="text-ink" />
        {failed ? (
          <div className="grid justify-items-center gap-sm" role="alert">
            <h1 className="text-xl">Cannot reach Archyra</h1>
            <p className="max-w-[40ch] text-muted">{message ?? 'The server did not answer. Check your connection, then try again.'}</p>
            {onRetry ? (
              <Button variant="primary" icon={<RotateCw size={16} aria-hidden="true" />} onClick={onRetry}>
                Try again
              </Button>
            ) : null}
          </div>
        ) : (
          <p className="flex items-center gap-xs text-muted" role="status">
            <Spinner size={16} />
            Opening Archyra
          </p>
        )}
      </div>
    </div>
  );
}
