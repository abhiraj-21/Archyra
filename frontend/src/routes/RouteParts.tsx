import { Suspense, type ReactNode } from 'react';
import { Outlet, ScrollRestoration } from 'react-router';
import { BootScreen } from '@/features/system/BootScreen';

/** A lazily loaded page, with the boot screen as its fallback. */
export function Page({ children }: { children: ReactNode }) {
  return <Suspense fallback={<BootScreen />}>{children}</Suspense>;
}

export function Root() {
  return (
    <>
      <ScrollRestoration />
      <Outlet />
    </>
  );
}
