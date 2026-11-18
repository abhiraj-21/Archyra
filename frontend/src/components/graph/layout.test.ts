import { describe, expect, it } from 'vitest';
import { computeLayout } from './layoutCore';

describe('layout', () => {
  it('places a dependency source above its target and packs isolated nodes below', () => {
    const { positions } = computeLayout({
      nodes: [{ id: 'a', width: 100, height: 40 }, { id: 'b', width: 100, height: 40 }, { id: 'c', width: 100, height: 40 }, { id: 'lonely', width: 100, height: 40 }],
      edges: [{ id: 'e1', source: 'a', target: 'b' }, { id: 'e2', source: 'b', target: 'c' }, { id: 'e3', source: 'a', target: 'c' }],
    });
    expect(positions.a!.y).toBeLessThan(positions.b!.y);
    expect(positions.b!.y).toBeLessThan(positions.c!.y);
    expect(positions.lonely!.y).toBeGreaterThan(positions.c!.y);
  });

});
