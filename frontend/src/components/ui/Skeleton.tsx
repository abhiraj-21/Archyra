/** A placeholder in the shape of what is loading. */
export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden="true" className={`skeleton block ${className ?? ''}`} />;
}

export function SkeletonRows({ rows = 5, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div role="status" aria-label={label} className="grid gap-sm">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9" />
      ))}
    </div>
  );
}
