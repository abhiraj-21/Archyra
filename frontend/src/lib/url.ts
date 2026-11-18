/**
 * Client-side check of a GitHub repository URL. A convenience only: the server is the authority and applies
 * `^(?!.*\.\.)https://github\.com/[A-Za-z0-9-]+/(?!\.{1,2}$)[A-Za-z0-9_.-]+$` (max 200 characters).
 *
 * The server rejects a trailing slash, and would treat `repo.git` as a different URL from `repo` when it looks
 * for a running duplicate, so both are normalised away before sending.
 */
export type RepoUrlResult =
  | { ok: true; url: string; owner: string; repo: string }
  | { ok: false; reason: RepoUrlProblem; hint: string; suggestion?: string };

export type RepoUrlProblem =
  | 'EMPTY'
  | 'TOO_LONG'
  | 'NOT_A_URL'
  | 'SSH'
  | 'NO_SCHEME'
  | 'INSECURE'
  | 'CREDENTIALS'
  | 'OTHER_HOST'
  | 'QUERY_OR_FRAGMENT'
  | 'MISSING_REPO'
  | 'EXTRA_PATH'
  | 'BAD_CHARACTERS';

const MAX_LENGTH = 200;
const OWNER = /^[A-Za-z0-9-]+$/;
const REPO = /^[A-Za-z0-9_.-]+$/;
const SERVER_PATTERN = /^(?!.*\.\.)https:\/\/github\.com\/[A-Za-z0-9-]+\/(?!\.{1,2}$)[A-Za-z0-9_.-]+$/;

function canonical(owner: string, repo: string): string {
  return `https://github.com/${owner}/${repo}`;
}

function stripRepoSuffix(repo: string): string {
  return repo.replace(/\.git$/i, '');
}

export function validateRepoUrl(input: string): RepoUrlResult {
  const raw = input.trim();
  if (!raw) return { ok: false, reason: 'EMPTY', hint: 'Paste a GitHub repository URL, like https://github.com/owner/repo.' };

  const ssh = /^git@github\.com:([A-Za-z0-9-]+)\/([A-Za-z0-9_.-]+?)(?:\.git)?\/?$/i.exec(raw);
  if (ssh?.[1] && ssh[2]) {
    return {
      ok: false,
      reason: 'SSH',
      hint: 'That is an SSH clone address. Archyra needs the https:// address of the repository.',
      suggestion: canonical(ssh[1], ssh[2]),
    };
  }

  if (raw.includes('..')) {
    return { ok: false, reason: 'BAD_CHARACTERS', hint: 'Owner and repository names cannot contain "..".' };
  }

  let candidate = raw;
  let noScheme = false;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(candidate)) {
    noScheme = true;
    candidate = `https://${candidate}`;
  }

  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return { ok: false, reason: 'NOT_A_URL', hint: 'That does not look like a URL. Use https://github.com/owner/repo.' };
  }

  if (url.username || url.password) {
    return { ok: false, reason: 'CREDENTIALS', hint: 'Remove the user name or token from the URL. Archyra only reads public repositories.' };
  }

  const host = url.hostname.toLowerCase();
  if (host !== 'github.com' && host !== 'www.github.com') {
    return { ok: false, reason: 'OTHER_HOST', hint: 'Only repositories on github.com can be analysed.' };
  }

  const segments = url.pathname.split('/').filter(Boolean);
  const [owner, repoWithSuffix] = segments;
  if (!owner || !repoWithSuffix) {
    return { ok: false, reason: 'MISSING_REPO', hint: 'Add the repository name after the owner: https://github.com/owner/repo.' };
  }
  const repo = stripRepoSuffix(repoWithSuffix);
  if (!OWNER.test(owner) || !REPO.test(repo) || repo === '.') {
    return { ok: false, reason: 'BAD_CHARACTERS', hint: 'Owner and repository names use letters, numbers, dots, dashes and underscores only.' };
  }

  const fixed = canonical(owner, repo);
  if (segments.length > 2) {
    return { ok: false, reason: 'EXTRA_PATH', hint: 'Use the repository’s main address, not a branch, folder or file inside it.', suggestion: fixed };
  }
  if (url.search || url.hash) {
    return { ok: false, reason: 'QUERY_OR_FRAGMENT', hint: 'Remove everything after the repository name.', suggestion: fixed };
  }
  if (url.protocol === 'http:') {
    return { ok: false, reason: 'INSECURE', hint: 'Use https:// rather than http://.', suggestion: fixed };
  }
  if (url.protocol !== 'https:') {
    return { ok: false, reason: 'NOT_A_URL', hint: 'Use an https:// address on github.com.' };
  }
  if (noScheme) {
    return { ok: false, reason: 'NO_SCHEME', hint: 'Start the address with https://.', suggestion: fixed };
  }
  if (fixed.length > MAX_LENGTH) {
    return { ok: false, reason: 'TOO_LONG', hint: `Repository URLs are limited to ${MAX_LENGTH} characters.` };
  }
  if (!SERVER_PATTERN.test(fixed)) {
    return { ok: false, reason: 'BAD_CHARACTERS', hint: 'Owner and repository names use letters, numbers, dots, dashes and underscores only.' };
  }
  return { ok: true, url: fixed, owner, repo };
}

/** `https://github.com/owner/repo` → `owner/repo`, for display. Returns the input when it is not a GitHub URL. */
export function repoLabel(repositoryUrl: string): string {
  const m = /^https:\/\/github\.com\/([^/]+)\/([^/]+)$/.exec(repositoryUrl);
  return m ? `${m[1]}/${m[2]}` : repositoryUrl;
}

/** Only a validated https://github.com/... URL may become a link. */
export function safeGithubHref(repositoryUrl: string): string | null {
  return SERVER_PATTERN.test(repositoryUrl) ? repositoryUrl : null;
}
