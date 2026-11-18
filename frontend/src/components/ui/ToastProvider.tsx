import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { CircleCheck, Info, X } from 'lucide-react';
import { ToastContext, type ToastTone, type ToastValue } from './toastContext';

interface ToastItem {
  id: number;
  message: string;
  tone: ToastTone;
}

const TOAST_MS = 7_000;

/** Short confirmations in a polite live region at the bottom of the screen. Never the only place an error appears. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id);
    if (timer) clearTimeout(timer);
    timers.current.delete(id);
    setToasts((list) => list.filter((t) => t.id !== id));
  }, []);

  const showToast = useCallback<ToastValue['showToast']>(
    (message, tone = 'success') => {
      const id = nextId.current++;
      setToasts((list) => [...list.slice(-2), { id, message, tone }]);
      timers.current.set(id, setTimeout(() => dismiss(id), TOAST_MS));
    },
    [dismiss],
  );

  useEffect(() => {
    const pending = timers.current;
    return () => pending.forEach((timer) => clearTimeout(timer));
  }, []);

  const value = useMemo(() => ({ showToast }), [showToast]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast-region" role="region" aria-label="Notifications">
        {toasts.map((t) => {
          const Icon = t.tone === 'success' ? CircleCheck : Info;
          return (
            <div key={t.id} className="toast" data-tone={t.tone} role="status">
              <Icon size={18} aria-hidden="true" />
              <p className="min-w-0 flex-1">{t.message}</p>
              <button type="button" className="btn btn-quiet btn-icon" onClick={() => dismiss(t.id)} aria-label="Dismiss notification">
                <X size={16} aria-hidden="true" />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}
