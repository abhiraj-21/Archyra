import { forwardRef, useId, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { Eye, EyeOff } from 'lucide-react';

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label: string;
  /** Shown under the field when there is no error. */
  help?: ReactNode;
  error?: string | null;
  size?: 'md' | 'lg';
  trailing?: ReactNode;
}

/** A labelled input with a reserved helper line; the error replaces the help and is tied by aria-describedby. */
export const TextField = forwardRef<HTMLInputElement, FieldProps>(function TextField(
  { label, help, error, size = 'md', trailing, id, className, required, ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const helpId = `${inputId}-help`;
  return (
    <div className={`field ${className ?? ''}`}>
      <label htmlFor={inputId} className="field-label">
        {label}
        {required ? <span className="text-muted"> (required)</span> : null}
      </label>
      <div className="relative">
        <input
          ref={ref}
          id={inputId}
          className={`input ${size === 'lg' ? 'input-lg' : ''} ${trailing ? 'pr-2xl' : ''}`}
          aria-invalid={error ? true : undefined}
          aria-describedby={helpId}
          aria-required={required || undefined}
          required={required}
          {...rest}
        />
        {trailing ? <div className="absolute inset-y-0 right-0 flex items-center pr-2xs">{trailing}</div> : null}
      </div>
      <p id={helpId} className="field-help" data-tone={error ? 'error' : undefined} aria-live={error ? 'polite' : undefined}>
        {error ?? help ?? ''}
      </p>
    </div>
  );
});

export const PasswordField = forwardRef<HTMLInputElement, Omit<FieldProps, 'type' | 'trailing'>>(function PasswordField(props, ref) {
  const [visible, setVisible] = useState(false);
  return (
    <TextField
      ref={ref}
      type={visible ? 'text' : 'password'}
      spellCheck={false}
      autoCapitalize="none"
      trailing={
        <button
          type="button"
          className="btn btn-quiet btn-icon"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? 'Hide password' : 'Show password'}
          aria-pressed={visible}
        >
          {visible ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
        </button>
      }
      {...props}
    />
  );
});
