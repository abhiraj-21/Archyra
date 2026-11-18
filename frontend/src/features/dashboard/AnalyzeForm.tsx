import { useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { ArrowRight } from 'lucide-react';
import { hasCode } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { useCountdown } from '@/components/ui/useCountdown';
import { useAnalysisUsage } from '@/hooks/useAccount';
import { useCreateAnalysis } from '@/hooks/useAnalyses';
import { useUpgrade } from '@/hooks/upgradeContext';
import { describeError } from '@/lib/errorCopy';
import { validateRepoUrl } from '@/lib/url';

/** The dashboard's one job: paste a GitHub URL, press Enter. The server stays the authority on the URL. */
export function AnalyzeForm() {
  const navigate = useNavigate();
  const create = useCreateAnalysis();
  const { entry } = useAnalysisUsage();
  const { openUpgrade } = useUpgrade();
  const [value, setValue] = useState('');
  const [touched, setTouched] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const id = useId();

  const atLimit = entry?.limitReached === true || hasCode(create.error, 'USAGE_LIMIT_EXCEEDED');
  const check = validateRepoUrl(value);
  const showHint = touched && value.trim() !== '' && !check.ok;
  const retryLeft = useCountdown(hasCode(create.error, 'RATE_LIMITED') ? create.error.retryAfter : undefined);
  const rateLimited = hasCode(create.error, 'RATE_LIMITED') && retryLeft > 0;
  const serverError = create.error && !hasCode(create.error, 'USAGE_LIMIT_EXCEEDED') ? describeError(create.error) : null;

  function submit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    const result = validateRepoUrl(value);
    if (!result.ok) {
      inputRef.current?.focus();
      return;
    }
    // A URL that is already running comes back as that analysis: the progress view picks it up either way.
    create.mutate(result.url, { onSuccess: (res) => navigate(`/analyses/${res.analysisId}`) });
  }

  return (
    <form onSubmit={submit} noValidate className="grid gap-sm">
      <label htmlFor={id} className="text-lg font-semibold text-ink">
        Paste a GitHub URL
      </label>
      <div className="flex flex-col gap-sm sm:flex-row">
        <input
          ref={inputRef}
          id={id}
          name="repositoryUrl"
          type="url"
          inputMode="url"
          autoComplete="url"
          spellCheck={false}
          autoCapitalize="none"
          placeholder="https://github.com/owner/repository"
          className="input input-lg min-w-0 flex-1"
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            if (create.error) create.reset();
          }}
          onBlur={() => value && setTouched(true)}
          disabled={atLimit}
          aria-invalid={showHint || undefined}
          aria-describedby={`${id}-hint`}
          maxLength={300}
        />
        <Button type="submit" variant="primary" size="lg" loading={create.isPending} disabled={atLimit || rateLimited} icon={<ArrowRight size={18} aria-hidden="true" />}>
          Analyze
        </Button>
      </div>
      <div id={`${id}-hint`} className="min-h-[1lh] text-sm" aria-live="polite">
        {atLimit ? null : showHint && !check.ok ? (
          <p className="text-error">
            {check.hint}{' '}
            {check.suggestion ? (
              <button type="button" className="link" onClick={() => { setValue(check.suggestion as string); inputRef.current?.focus(); }}>
                Use {check.suggestion}
              </button>
            ) : null}
          </p>
        ) : (
          <p className="text-muted">Public repositories on github.com. Archyra reads the code; it never runs it.</p>
        )}
      </div>
      {atLimit ? (
        <Notice
          tone="warn"
          title="You have used every free analysis"
          action={
            <Button size="sm" onClick={() => openUpgrade('More analyses')}>
              See Premium
            </Button>
          }
        >
          The free plan is a lifetime allowance. Delete an analysis you no longer need to get its credit back.
        </Notice>
      ) : null}
      {serverError ? (
        <Notice tone="error" title={serverError.title}>
          {serverError.message}
          {rateLimited ? (
            <>
              {' '}
              Try again in <span className="num">{retryLeft}</span>&nbsp;s.
            </>
          ) : null}
        </Notice>
      ) : null}
    </form>
  );
}
