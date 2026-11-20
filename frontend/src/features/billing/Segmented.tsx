import { useRef, type ReactNode } from 'react';

export interface SegmentOption<T extends string> {
  value: T;
  label: ReactNode;
  disabled?: boolean;
}

/** A radio group on `.segmented`: arrow keys move and select, only the chosen option is in the tab order (as Appearance). */
export function Segmented<T extends string>({
  label,
  value,
  options,
  onChange,
  describedBy,
}: {
  label: string;
  value: T;
  options: SegmentOption<T>[];
  onChange: (value: T) => void;
  describedBy?: string;
}) {
  const refs = useRef<Partial<Record<T, HTMLButtonElement | null>>>({});
  const enabled = options.filter((option) => !option.disabled);

  function onKeyDown(event: React.KeyboardEvent) {
    const i = enabled.findIndex((option) => option.value === value);
    let next: SegmentOption<T> | undefined;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = enabled[(i + 1) % enabled.length];
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = enabled[(i + enabled.length - 1) % enabled.length];
    else if (event.key === 'Home') next = enabled[0];
    else if (event.key === 'End') next = enabled[enabled.length - 1];
    if (!next) return;
    event.preventDefault();
    onChange(next.value);
    refs.current[next.value]?.focus();
  }

  return (
    <div role="radiogroup" aria-label={label} aria-describedby={describedBy} className="segmented w-fit max-w-full flex-wrap" onKeyDown={onKeyDown}>
      {options.map((option) => (
        <button
          key={option.value}
          ref={(el) => {
            refs.current[option.value] = el;
          }}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          tabIndex={value === option.value ? 0 : -1}
          disabled={option.disabled}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
