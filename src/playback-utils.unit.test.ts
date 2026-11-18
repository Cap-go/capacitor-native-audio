import { describe, expect, it } from 'vitest';

import {
  clampPlaybackRate,
  normalizeProgressInterval,
  queueIndexAfterComplete,
  queueIndexAfterStep,
} from './playback-utils';

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

  it('wraps queue movement only when repeat is all', () => {
    expect(queueIndexAfterStep(1, 3, 1, 'none')).toBe(2);
    expect(queueIndexAfterStep(2, 3, 1, 'none')).toBeNull();
    expect(queueIndexAfterStep(0, 3, -1, 'one')).toBeNull();
    expect(queueIndexAfterStep(2, 3, 1, 'all')).toBe(0);
    expect(queueIndexAfterStep(0, 3, -1, 'all')).toBe(2);
  });

  it('repeats the current item or the queue when playback ends', () => {
    expect(queueIndexAfterComplete(1, 3, 'none')).toBeNull();
    expect(queueIndexAfterComplete(1, 3, 'one')).toBe(1);
    expect(queueIndexAfterComplete(2, 3, 'all')).toBe(0);
  });
});
