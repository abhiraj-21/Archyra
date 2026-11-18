import { useState } from 'react';

/** Validate on blur once touched, then on every input; a failed submit marks every field touched. */
export function useField(validate: (value: string) => string | null, initial = '') {
  const [value, setValue] = useState(initial);
  const [touched, setTouched] = useState(false);
  const error = touched ? validate(value) : null;
  return {
    value,
    setValue,
    error,
    touch: () => setTouched(true),
    isValid: () => validate(value) === null,
    inputProps: {
      value,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => setValue(e.target.value),
      onBlur: () => setTouched(true),
    },
  };
}
