import type { PlaybackStateValue } from './definitions';

export const MIN_PLAYBACK_RATE = 0.25;
export const MAX_PLAYBACK_RATE = 4;
export const DEFAULT_PROGRESS_INTERVAL_SEC = 0.5;

export const clampPlaybackRate = (rate: number): number =>
  Math.min(MAX_PLAYBACK_RATE, Math.max(MIN_PLAYBACK_RATE, rate));

export const normalizeProgressInterval = (seconds?: number): number => {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) {
    return DEFAULT_PROGRESS_INTERVAL_SEC;
  }
  return seconds;
};

export const resolveIsPlayingState = (isPlaying: boolean): PlaybackStateValue => (isPlaying ? 'playing' : 'paused');

export type RepeatMode = 'none' | 'one' | 'all';

export const normalizeRepeatMode = (mode?: string): RepeatMode => {
  if (mode === 'one' || mode === 'all') {
    return mode;
  }
  return 'none';
};

export const queueIndexAfterStep = (index: number, count: number, delta: number, mode: RepeatMode): number | null => {
  if (count <= 0) {
    return null;
  }
  const next = index + delta;
  if (next >= 0 && next < count) {
    return next;
  }
  if (mode !== 'all') {
    return null;
  }
  return next < 0 ? count - 1 : 0;
};

export const queueIndexAfterComplete = (index: number, count: number, mode: RepeatMode): number | null => {
  if (count <= 0 || index < 0 || index >= count || mode === 'none') {
    return null;
  }
  if (mode === 'one') {
    return index;
  }
  return queueIndexAfterStep(index, count, 1, mode);
};
