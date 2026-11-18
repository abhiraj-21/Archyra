import { LoaderCircle } from 'lucide-react';

/** A loader: the one loop allowed to keep moving, and it stops under reduced motion. */
export function Spinner({ size = 16, label }: { size?: number; label?: string }) {
  return (
    <span className="inline-flex items-center" role={label ? 'status' : undefined}>
      <LoaderCircle size={size} className="spinner" aria-hidden="true" />
      {label ? <span className="sr-only">{label}</span> : null}
    </span>
  );
}
