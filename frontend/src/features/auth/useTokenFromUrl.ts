import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';

/**
 * Reads `?token=` once, then removes it from the address bar (history.replaceState via the router) so the
 * single-use secret does not linger in history, screenshots or a Referer header.
 */
export function useTokenFromUrl(): string | null {
  const location = useLocation();
  const navigate = useNavigate();
  const [token] = useState(() => new URLSearchParams(location.search).get('token'));
  useEffect(() => {
    if (new URLSearchParams(location.search).has('token')) navigate(location.pathname, { replace: true });
  }, [location.pathname, location.search, navigate]);
  return token && token.length <= 200 ? token : null;
}
