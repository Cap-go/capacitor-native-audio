import { describe, expect, it } from 'vitest';

import { clampPlaybackRate, normalizeProgressInterval } from './playback-utils';

describe('playback-utils', () => {
  it('clamps rates to the native supported range', () => {
    expect(clampPlaybackRate(0.1)).toBe(0.25);
    expect(clampPlaybackRate(8)).toBe(4);
    expect(clampPlaybackRate(1.25)).toBe(1.25);
  });

  it('normalizes invalid progress intervals', () => {
    expect(normalizeProgressInterval()).toBe(0.5);
    expect(normalizeProgressInterval(-1)).toBe(0.5);
    expect(normalizeProgressInterval(2)).toBe(2);
  });
});
