import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'normal' | 'wide';
  /** Optional id of the element that describes the dialog. */
  describedBy?: string;
}

const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';

/**
 * A native modal dialog makes the page behind it inert, but Tab off its last control still leaves the page (to the browser's own
 * interface), so the dialog wraps Tab and Shift+Tab itself: focus never leaves it while it is open.
 */
function trapTab(event: React.KeyboardEvent<HTMLDialogElement>) {
  if (event.key !== 'Tab') return;
  const items = [...event.currentTarget.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.getClientRects().length > 0);
  const first = items[0];
  const last = items[items.length - 1];
  if (!first || !last) return;
  const active = document.activeElement;
  if (event.shiftKey && (active === first || !event.currentTarget.contains(active))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (active === last || !event.currentTarget.contains(active))) {
    event.preventDefault();
    first.focus();
  }
}

/** A native modal <dialog>: focus trap, Escape and backdrop come with it. Backdrop click and the close button close it. */
export function Dialog({ open, onClose, title, children, footer, size = 'normal', describedBy }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="dialog"
      data-size={size === 'wide' ? 'wide' : undefined}
      aria-labelledby={titleId}
      aria-describedby={describedBy}
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onKeyDown={trapTab}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      {open ? (
        <div className="grid gap-md p-lg">
          <div className="flex items-start justify-between gap-md">
            <h2 id={titleId} className="text-xl">
              {title}
            </h2>
            <button type="button" className="btn btn-quiet btn-icon -mr-xs -mt-2xs" onClick={onClose} aria-label="Close">
              <X size={18} aria-hidden="true" />
            </button>
          </div>
          <div className="text-ink-2">{children}</div>
          {footer ? <div className="flex flex-wrap justify-end gap-sm pt-xs">{footer}</div> : null}
        </div>
      ) : null}
    </dialog>
  );
}
