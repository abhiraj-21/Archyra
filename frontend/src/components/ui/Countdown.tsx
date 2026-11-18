import { useCountdown } from './useCountdown';

export function Countdown({ seconds }: { seconds: number }) {
  const left = useCountdown(seconds);
  return left > 0 ? <span>Try again in <span className="num">{left}</span>&nbsp;s.</span> : <span>You can try again now.</span>;
}
