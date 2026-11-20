import { Suspense, type ReactNode } from 'react';
import { Outlet, ScrollRestoration } from 'react-router';
import { BootScreen } from '@/features/system/BootScreen';
import { useTheme } from '@/hooks/useTheme';
import { UpgradeProvider } from '@/features/upgrade/UpgradeProvider';

/** A lazily loaded page, with the boot screen as its fallback. */
export function Page({ children }: { children: ReactNode }) {
  return <Suspense fallback={<BootScreen />}>{children}</Suspense>;
}

export function Root() {
  useTheme(); // keeps <html data-theme> in step with the choice, and with the device when the choice is System
  return (
    <UpgradeProvider>
      <ScrollRestoration />
      <Outlet />
    </UpgradeProvider>
  );
}
