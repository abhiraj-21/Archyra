import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import { createQueryClient } from '@/hooks/queryClient';
import { SessionProvider } from '@/hooks/session';
import { ToastProvider } from '@/components/ui/ToastProvider';
import { UpgradeProvider } from '@/features/upgrade/UpgradeProvider';
import { router } from '@/routes/router';
import '@/styles/index.css';

const root = document.getElementById('root');
if (!root) throw new Error('Missing #root');

const queryClient = createQueryClient();

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <ToastProvider>
          <UpgradeProvider>
            <RouterProvider router={router} />
          </UpgradeProvider>
        </ToastProvider>
      </SessionProvider>
    </QueryClientProvider>
  </StrictMode>,
);
