import { describe, expect, it } from 'vitest';
import { applyMoves, movedCount, overlayPositions } from './manualPositions';

// Small inline inputs for the pure overlay logic.
const auto = { a: { x: 0, y: 0 }, b: { x: 100, y: 0 }, c: { x: 200, y: 0 } };

describe('overlayPositions', () => {
  it('a manual position overrides the automatic one', () => {
    expect(overlayPositions(auto, { b: { x: 5, y: 7 } }, ['a', 'b', 'c'])).toEqual({ a: { x: 0, y: 0 }, b: { x: 5, y: 7 }, c: { x: 200, y: 0 } });
  });

  it('nodes without a manual position use the new automatic position', () => {
    const relaid = { a: { x: 10, y: 10 }, b: { x: 20, y: 20 }, c: { x: 30, y: 30 }, d: { x: 40, y: 40 } };
    expect(overlayPositions(relaid, { b: { x: 5, y: 7 } }, ['a', 'b', 'd'])).toEqual({ a: { x: 10, y: 10 }, b: { x: 5, y: 7 }, d: { x: 40, y: 40 } });
  });

  it('hidden nodes are not drawn but keep their manual position for when they return', () => {
    const manual = { b: { x: 5, y: 7 } };
    expect(overlayPositions(auto, manual, ['a', 'c'])).toEqual({ a: { x: 0, y: 0 }, c: { x: 200, y: 0 } });
    expect(overlayPositions(auto, manual, ['a', 'b', 'c']).b).toEqual({ x: 5, y: 7 });
    expect(manual).toEqual({ b: { x: 5, y: 7 } });
  });

  it('leaves out visible nodes the layout does not know, and does not mutate its inputs', () => {
    const frozenAuto = Object.freeze({ ...auto });
    expect(overlayPositions(frozenAuto, Object.freeze({}), ['a', 'zzz'])).toEqual({ a: { x: 0, y: 0 } });
  });

  it('with the manual positions cleared (reset) it is the automatic layout again', () => {
    expect(overlayPositions(auto, {}, ['a', 'b', 'c'])).toEqual(auto);
  });
});

describe('applyMoves and movedCount', () => {
  it('records final positions, replacing earlier ones, and returns the same object when nothing changed', () => {
    const one = applyMoves({}, { a: { x: 1, y: 2 } });
    expect(one).toEqual({ a: { x: 1, y: 2 } });
    expect(applyMoves(one, { a: { x: 1, y: 2 } })).toBe(one);
    expect(applyMoves(one, { a: { x: 3, y: 4 }, b: { x: 5, y: 6 } })).toEqual({ a: { x: 3, y: 4 }, b: { x: 5, y: 6 } });
  });

  it('counts only visible moved nodes', () => {
    const manual = { a: { x: 1, y: 1 }, hidden: { x: 2, y: 2 } };
    expect(movedCount(manual, ['a', 'b'])).toBe(1);
    expect(movedCount({}, ['a', 'b'])).toBe(0);
  });
});
