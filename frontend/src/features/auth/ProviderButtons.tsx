import { ButtonAnchor } from '@/components/ui/Button';
import { stashReturnTo } from '@/lib/returnTo';
import { GitHubMark, GoogleMark } from './ProviderMarks';

/** Google and GitHub sign-in are full-page navigations through the proxy, never fetch. */
export function ProviderButtons({ next }: { next: string }) {
  return (
    <div className="grid gap-sm">
      <ButtonAnchor href="/oauth2/authorization/google" variant="secondary" size="lg" icon={<GoogleMark />} onClick={() => stashReturnTo(next)}>
        Continue with Google
      </ButtonAnchor>
      <ButtonAnchor href="/oauth2/authorization/github" variant="secondary" size="lg" icon={<GitHubMark />} onClick={() => stashReturnTo(next)}>
        Continue with GitHub
      </ButtonAnchor>
    </div>
  );
}
