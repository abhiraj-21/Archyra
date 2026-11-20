import { useEffect, useRef, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { ChevronsLeft, ChevronsRight, LayoutDashboard, LogOut, Menu as MenuIcon, Settings, ShieldCheck, Sparkles, X } from 'lucide-react';
import { PlanBadge } from '@/components/ui/Chips';
import { ThemeToggle } from '@/components/ThemeToggle';
import { LogoMark, Wordmark } from '@/components/ui/Logo';
import { Menu } from '@/components/ui/Menu';
import { useSession } from '@/hooks/sessionContext';
import { UsageMeter } from '@/features/usage/UsageMeter';
import { ExpiryBanner } from '@/features/billing/ExpiryBanner';
import { SignInExpiryDialog } from '@/features/billing/SignInExpiryDialog';

function NavItems({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) {
  const { isAdmin } = useSession();
  const items: { to: string; label: string; icon: ReactNode }[] = [
    { to: '/dashboard', label: 'Dashboard', icon: <LayoutDashboard size={18} aria-hidden="true" /> },
    { to: '/settings', label: 'Settings', icon: <Settings size={18} aria-hidden="true" /> },
  ];
  // Absent, not disabled, for everyone whose /users/me role is not ADMIN.
  if (isAdmin) items.push({ to: '/admin', label: 'Admin', icon: <ShieldCheck size={18} aria-hidden="true" /> });
  return (
    <ul className="grid gap-3xs">
      {items.map((item) => (
        <li key={item.to}>
          <NavLink to={item.to} className="nav-item" aria-label={collapsed ? item.label : undefined} title={collapsed ? item.label : undefined} onClick={onNavigate}>
            {item.icon}
            {collapsed ? null : item.label}
          </NavLink>
        </li>
      ))}
    </ul>
  );
}

function UserMenu({ collapsed }: { collapsed: boolean }) {
  const { me, signOut } = useSession();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  if (!me) return null;
  const doSignOut = async () => {
    setBusy(true);
    await signOut();
    navigate('/login', { replace: true });
  };
  return (
    <Menu
      align="start"
      placement="above"
      trigger={({ ref, ...props }) => (
        <button ref={ref} type="button" className="nav-item w-full" aria-label={collapsed ? `Account menu for ${me.name}` : 'Account menu'} {...props}>
          <span aria-hidden="true" className="grid size-7 shrink-0 place-items-center rounded-pill bg-paper-3 text-xs font-semibold text-ink">
            {me.name.trim().charAt(0).toUpperCase() || '?'}
          </span>
          {collapsed ? null : (
            <span className="flex min-w-0 flex-1 items-center justify-between gap-xs">
              <span className="truncate">{me.name}</span>
              <PlanBadge plan={me.plan} />
            </span>
          )}
        </button>
      )}
    >
      {(close) => (
        <>
          <div className="px-sm py-xs text-xs text-muted">
            <p className="truncate font-medium text-ink">{me.name}</p>
            <p className="truncate">{me.email}</p>
          </div>
          <button type="button" role="menuitem" className="menu-item" onClick={() => { close(); navigate('/upgrade'); }}>
            <Sparkles size={16} aria-hidden="true" /> Premium
          </button>
          <button type="button" role="menuitem" className="menu-item" onClick={() => { close(); navigate('/settings'); }}>
            <Settings size={16} aria-hidden="true" /> Settings
          </button>
          <button type="button" role="menuitem" className="menu-item" disabled={busy} onClick={doSignOut}>
            <LogOut size={16} aria-hidden="true" /> Sign out
          </button>
        </>
      )}
    </Menu>
  );
}

function SidebarBody({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) {
  return (
    <>
      <nav aria-label="Main" className="grid gap-md">
        <NavItems collapsed={collapsed} onNavigate={onNavigate} />
      </nav>
      <div className="mt-auto grid gap-md">
        {collapsed ? null : (
          <div className="border-t border-rule pt-md">
            <UsageMeter />
          </div>
        )}
        <UserMenu collapsed={collapsed} />
      </div>
    </>
  );
}

/** Signed-in shell: a slim sidebar (collapsible to icons) on wide screens, a top bar with a drawer under 60rem. */
export function AppShell() {
  const [collapsed, setCollapsed] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawerRef = useRef<HTMLDialogElement>(null);
  const location = useLocation();

  useEffect(() => {
    const d = drawerRef.current;
    if (!d) return;
    if (drawerOpen && !d.open) d.showModal();
    if (!drawerOpen && d.open) d.close();
  }, [drawerOpen]);
  useEffect(() => setDrawerOpen(false), [location.pathname]);

  return (
    <div data-scope="app" className="min-h-dvh bg-paper lg:flex">
      <a href="#main" className="btn btn-secondary sr-only focus:not-sr-only focus:fixed focus:left-md focus:top-md focus:z-(--z-tooltip)">
        Skip to content
      </a>

      {/* Wide screens */}
      <aside
        className={`sticky top-0 hidden h-dvh shrink-0 flex-col gap-lg border-r border-rule bg-paper-2 px-sm py-md lg:flex ${collapsed ? 'w-[4.25rem]' : 'w-[15.5rem]'}`}
        aria-label="Sidebar"
      >
        <div className={`flex items-center ${collapsed ? 'justify-center' : 'justify-between'} gap-xs px-2xs`}>
          {collapsed ? (
            <NavLink to="/dashboard" className="link-plain text-ink" aria-label="Archyra dashboard">
              <LogoMark size={24} />
            </NavLink>
          ) : (
            <NavLink to="/dashboard" className="link-plain" aria-label="Archyra dashboard">
              <Wordmark />
            </NavLink>
          )}
          {collapsed ? null : <ThemeToggle />}
        </div>
        <SidebarBody collapsed={collapsed} />
        <button
          type="button"
          className={`btn btn-quiet btn-sm ${collapsed ? 'self-center' : 'self-start'}`}
          onClick={() => setCollapsed((c) => !c)}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-expanded={!collapsed}
        >
          {collapsed ? <ChevronsRight size={16} aria-hidden="true" /> : <ChevronsLeft size={16} aria-hidden="true" />}
          {collapsed ? null : 'Collapse'}
        </button>
        {collapsed ? <ThemeToggle className="self-center" /> : null}
      </aside>

      {/* Narrow screens */}
      <header className="sticky top-0 z-(--z-sticky-nav) flex h-14 items-center justify-between border-b border-rule bg-paper-2 px-md lg:hidden">
        <NavLink to="/dashboard" className="link-plain" aria-label="Archyra dashboard">
          <Wordmark />
        </NavLink>
        <div className="flex items-center gap-2xs">
          <ThemeToggle />
          <button type="button" className="btn btn-quiet btn-icon" aria-label="Open menu" aria-expanded={drawerOpen} onClick={() => setDrawerOpen(true)}>
            <MenuIcon size={20} aria-hidden="true" />
          </button>
        </div>
      </header>
      <dialog
        ref={drawerRef}
        className="drawer"
        aria-label="Menu"
        onClose={() => setDrawerOpen(false)}
        onClick={(e) => e.target === drawerRef.current && setDrawerOpen(false)}
      >
        {drawerOpen ? (
          <div className="flex h-full flex-col gap-lg px-sm py-md">
            <div className="flex items-center justify-between px-2xs">
              <Wordmark />
              <button type="button" className="btn btn-quiet btn-icon" aria-label="Close menu" onClick={() => setDrawerOpen(false)}>
                <X size={20} aria-hidden="true" />
              </button>
            </div>
            <SidebarBody collapsed={false} onNavigate={() => setDrawerOpen(false)} />
          </div>
        ) : null}
      </dialog>

      <main id="main" tabIndex={-1} className="min-w-0 flex-1 focus:outline-none">
        <ExpiryBanner />
        <Outlet />
      </main>
      <SignInExpiryDialog />
    </div>
  );
}
