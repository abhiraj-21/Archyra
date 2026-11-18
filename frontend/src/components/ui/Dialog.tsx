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
