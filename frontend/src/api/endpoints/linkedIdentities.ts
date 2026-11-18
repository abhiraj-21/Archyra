import { http } from '../http';
import type { LinkedIdentitiesResponse, LinkIntentResponse, LinkProvider } from '../types';

export const getLinkedIdentities = (signal?: AbortSignal) => http<LinkedIdentitiesResponse>('/users/me/linked-identities', { signal });

/** Asks for permission to link; the response sets the HttpOnly binding cookie that the authorization start requires. */
export const startLink = (provider: LinkProvider) =>
  http<LinkIntentResponse>(`/users/me/linked-identities/${provider}/link-intent`, { method: 'POST' });

export const unlinkIdentity = (provider: LinkProvider) => http<void>(`/users/me/linked-identities/${provider}`, { method: 'DELETE' });
