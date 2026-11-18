import { createContext, useContext } from 'react';

export type ToastTone = 'success' | 'info';

export interface ToastValue {
  /** Shows a short polite message that dismisses itself (and can be dismissed). */
  showToast: (message: string, tone?: ToastTone) => void;
}

export const ToastContext = createContext<ToastValue>({ showToast: () => {} });
export const useToast = () => useContext(ToastContext);
