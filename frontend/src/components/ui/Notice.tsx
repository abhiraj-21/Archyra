import type { ReactNode } from 'react';
import { CircleAlert, CircleCheck, Info, TriangleAlert } from 'lucide-react';

type Tone = 'info' | 'error' | 'warn' | 'success';
const ICONS = { info: Info, error: CircleAlert, warn: TriangleAlert, success: CircleCheck };

/** An inline message with an icon (never colour alone). Errors announce assertively, the rest politely. */
export function Notice({ tone = 'info', title, children, action, className }: { tone?: Tone; title?: ReactNode; children?: ReactNode; action?: ReactNode; className?: string }) {
  const Icon = ICONS[tone];
  return (
    <div className={`notice ${className ?? ''}`} data-tone={tone === 'info' ? undefined : tone} role={tone === 'error' ? 'alert' : 'status'}>
      <Icon size={18} aria-hidden="true" />
      <div className="min-w-0 flex-1">
        {title ? <p className="font-medium text-ink">{title}</p> : null}
        {children ? <div className={title ? 'mt-3xs' : ''}>{children}</div> : null}
        {action ? <div className="mt-xs">{action}</div> : null}
      </div>
    </div>
  );
}
