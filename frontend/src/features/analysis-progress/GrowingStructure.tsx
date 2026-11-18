/**
 * Decorative loader: blank cards settling into three tiers, joined by stems. Abstract on purpose: no names, no
 * numbers, nothing that could be read as the repository's graph. Static under reduced motion.
 */
export function GrowingStructure() {
  const tiers = [[0], [0, 1], [0, 1, 2]];
  let i = 0;
  return (
    <div className="growing" aria-hidden="true">
      {tiers.map((tier, t) => (
        <div key={t} className="growing-tier">
          {tier.map((k) => (
            <span key={k} className="growing-card" style={{ '--i': i++ } as React.CSSProperties} />
          ))}
        </div>
      ))}
    </div>
  );
}
