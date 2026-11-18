import { describe, expect, it } from 'vitest';
import { encodeNodeId } from './encode';

describe('encodeNodeId', () => {
  it('encodes the slash so a file id stays one path segment', () => {
    expect(encodeNodeId('file:src/main/App.java')).toBe('file%3Asrc%2Fmain%2FApp.java');
  });

  it('encodes hash, parentheses, commas and spaces in method ids', () => {
    expect(encodeNodeId('method:com.foo.A#find(String, int)')).toBe('method%3Acom.foo.A%23find%28String%2C%20int%29');
  });

  it('encodes characters encodeURIComponent leaves alone', () => {
    expect(encodeNodeId("function:a/b.js#x!*'()")).toBe('function%3Aa%2Fb.js%23x%21%2A%27%28%29');
  });

  it('round-trips through decodeURIComponent', () => {
    const id = 'module:(default)';
    expect(decodeURIComponent(encodeNodeId(id))).toBe(id);
  });
});
