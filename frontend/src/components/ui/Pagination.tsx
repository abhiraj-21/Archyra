import { ChevronLeft, ChevronRight } from 'lucide-react';
import { formatNumber } from '@/lib/format';

/** "Showing 1–50 of 143" with previous/next. Pages are zero-based like the API. */
export function Pagination({ page, size, totalElements, totalPages, onPage, label }: { page: number; size: number; totalElements: number; totalPages: number; onPage: (page: number) => void; label: string }) {
  if (totalElements === 0) return null;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  return (
    <nav aria-label={label} className="flex flex-wrap items-center justify-between gap-sm text-sm text-muted">
      <p>
        Showing <span className="num text-ink-2">{formatNumber(from)}</span> to <span className="num text-ink-2">{formatNumber(to)}</span> of{' '}
        <span className="num text-ink-2">{formatNumber(totalElements)}</span>
      </p>
      <div className="flex gap-xs">
        <button type="button" className="btn btn-secondary btn-sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          <ChevronLeft size={16} aria-hidden="true" />
          Previous
        </button>
        <button type="button" className="btn btn-secondary btn-sm" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>
          Next
          <ChevronRight size={16} aria-hidden="true" />
        </button>
      </div>
    </nav>
  );
}
