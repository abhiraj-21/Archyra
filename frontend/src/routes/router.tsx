import { lazy } from 'react';
import { createBrowserRouter, Navigate } from 'react-router';
import { AppShell } from '@/components/layout/AppShell';
import { CheckEmailPage } from '@/features/auth/CheckEmailPage';
import { ForgotPasswordPage } from '@/features/auth/ForgotPasswordPage';
import { LoginPage } from '@/features/auth/LoginPage';
import { OAuthCallbackPage } from '@/features/auth/OAuthCallbackPage';
import { RegisterPage } from '@/features/auth/RegisterPage';
import { ResetPasswordPage } from '@/features/auth/ResetPasswordPage';
import { VerifyEmailPage } from '@/features/auth/VerifyEmailPage';
import { NotFoundPage } from '@/features/system/NotFoundPage';
import { BootGate, RedirectIfAuthed, RequireAdmin, RequireAuth } from './guards';
import { Page, Root } from './RouteParts';

const LandingPage = lazy(() => import('@/features/landing/LandingPage'));
const DashboardPage = lazy(() => import('@/features/dashboard/DashboardPage'));
const AnalysisPage = lazy(() => import('@/features/analysis-progress/AnalysisPage'));
const WorkspaceLayout = lazy(() => import('@/features/repository/WorkspaceLayout'));
const OverviewPage = lazy(() => import('@/features/repository/overview/OverviewPage'));
const ArchitecturePage = lazy(() => import('@/features/repository/architecture/ArchitecturePage'));
const FilesPage = lazy(() => import('@/features/repository/files/FilesPage'));
const RelationshipsPage = lazy(() => import('@/features/repository/relationships/RelationshipsPage'));
const AskAiPage = lazy(() => import('@/features/repository/ask-ai/AskAiPage'));
const SettingsPage = lazy(() => import('@/features/settings/SettingsPage'));
const UpgradePage = lazy(() => import('@/features/billing/UpgradePage'));
const DeleteConfirmPage = lazy(() => import('@/features/settings/DeleteConfirmPage'));
const AdminStatsPage = lazy(() => import('@/features/admin/AdminStatsPage'));
const AdminUsersPage = lazy(() => import('@/features/admin/AdminUsersPage'));
const AdminUserPage = lazy(() => import('@/features/admin/AdminUserPage'));

export const router = createBrowserRouter([
  {
    element: <Root />,
    children: [
      {
        element: <BootGate />,
        children: [
          { path: '/', element: <Page><LandingPage /></Page> },
          {
            element: <RedirectIfAuthed />,
            children: [
              { path: '/login', element: <LoginPage /> },
              { path: '/register', element: <RegisterPage /> },
            ],
          },
          { path: '/register/check-email', element: <CheckEmailPage /> },
          { path: '/verify-email', element: <VerifyEmailPage /> },
          { path: '/forgot-password', element: <ForgotPasswordPage /> },
          { path: '/reset-password', element: <ResetPasswordPage /> },
          { path: '/auth/callback', element: <OAuthCallbackPage /> },
          {
            element: <RequireAuth />,
            children: [
              {
                element: <AppShell />,
                children: [
                  { path: '/dashboard', element: <Page><DashboardPage /></Page> },
                  { path: '/analyses/:analysisId', element: <Page><AnalysisPage /></Page> },
                  {
                    path: '/analyses/:analysisId',
                    element: <Page><WorkspaceLayout /></Page>,
                    children: [
                      { path: 'overview', element: <Page><OverviewPage /></Page> },
                      { path: 'architecture', element: <Page><ArchitecturePage /></Page> },
                      { path: 'files', element: <Page><FilesPage /></Page> },
                      { path: 'relationships', element: <Page><RelationshipsPage /></Page> },
                      { path: 'ask-ai', element: <Page><AskAiPage /></Page> },
                    ],
                  },
                  { path: '/settings', element: <Page><SettingsPage /></Page> },
                  { path: '/upgrade', element: <Page><UpgradePage /></Page> },
                  { path: '/settings/delete-confirm', element: <Page><DeleteConfirmPage /></Page> },
                ],
              },
            ],
          },
          {
            path: '/admin',
            element: <RequireAdmin />,
            children: [
              {
                element: <AppShell />,
                children: [
                  { index: true, element: <Navigate to="/admin/stats" replace /> },
                  { path: 'stats', element: <Page><AdminStatsPage /></Page> },
                  { path: 'users', element: <Page><AdminUsersPage /></Page> },
                  { path: 'users/:userId', element: <Page><AdminUserPage /></Page> },
                ],
              },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);
