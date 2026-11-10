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
