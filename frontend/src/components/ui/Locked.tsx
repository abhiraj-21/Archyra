import type { ReactNode } from 'react';
import { useUpgrade } from '@/hooks/upgradeContext';
import { ComingSoonBadge, ProBadge } from './Chips';

/**
 * The three states of a feature (spec 9), each with one shared component:
 * - available: render the real control yourself;
 * - pro: the backend serves it, the plan does not → <ProLocked>, which opens the upgrade dialog;
 * - soon: the backend does not have it, for any plan → <ComingSoon>, which never calls anything.
 */
export function ProLocked({ feature, children, icon }: { feature: string; children: ReactNode; icon?: ReactNode }) {
  const { openUpgrade } = useUpgrade();
  return (
    <button type="button" className="btn btn-quiet btn-sm" onClick={() => openUpgrade(feature)} aria-label={`${feature}: Premium feature`}>
      {icon}
      {children}
      <ProBadge />
    </button>
  );
}

export function ComingSoon({ children, icon, description }: { children: ReactNode; icon?: ReactNode; description?: string }) {
  return (
    <span
      className="btn btn-quiet btn-sm"
      role="button"
      aria-disabled="true"
      tabIndex={0}
      title={description ?? 'Not built yet'}
      aria-description={description ?? 'Not built yet'}
    >
      {icon}
      {children}
      <ComingSoonBadge />
    </span>
  );
}
