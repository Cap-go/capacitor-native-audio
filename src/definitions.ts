import type { PluginListenerHandle } from '@capacitor/core';

export interface CompletedEvent {
  /**
   * Emit when a play completes
   *
   * @since  5.0.0
   */
  assetId: string;
}

export type CompletedListener = (state: CompletedEvent) => void;

export interface Assets {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
}

export interface AssetVolume {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
  /**
   * Volume of the audio, between 0.1 and 1.0
   */
  volume: number;
  /**
   * Time over which to fade to the target volume, in seconds. Default is 0s (immediate).
   */
  duration?: number;
}

export interface AssetRate {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
  /**
   * Rate of the audio, between 0.1 and 1.0
   */
  rate: number;
}

export interface AssetSetTime {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
  /**
   * Time to set the audio, in seconds
   */
  time: number;
}

export interface AssetPlayOptions {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
  /**
   * Time to start playing the audio, in seconds
   */
  time?: number;
  /**
   * Delay to start playing the audio, in seconds
   */
  delay?: number;

  /**
   * Volume of the audio, between 0.1 and 1.0
   */
  volume?: number;

  /**
   * Whether to fade in the audio
   */
  fadeIn?: boolean;

  /**
   * Whether to fade out the audio
   */
  fadeOut?: boolean;

  /**
   * Fade in duration in seconds.
   * Only used if fadeIn is true.
   * Default is 1s.
   */
  fadeInDuration?: number;

  /**
   * Fade out duration in seconds.
   * Only used if fadeOut is true.
   * Default is 1s.
   */
  fadeOutDuration?: number;

  /**
   * Time in seconds from the start of the audio to start fading out.
   * Only used if fadeOut is true.
   * Default is fadeOutDuration before end of audio.
   */
  fadeOutStartTime?: number;
}

export interface AssetStopOptions {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;

  /**
   * Whether to fade out the audio before stopping
   */
  fadeOut?: boolean;

  /**
   * Fade out duration in seconds.
   * Default is 1s.
   */
  fadeOutDuration?: number;
}

export interface ConfigureOptions {
  /**
   * focus the audio with Audio Focus
   */
  focus?: boolean;
  /**
   * Play the audio in the background
   */
  background?: boolean;
  /**
   * Ignore silent mode, works only on iOS setting this will nuke other audio apps
   */
  ignoreSilent?: boolean;
}

export interface PreloadOptions {
  /**
   * Path to the audio file, relative path of the file, absolute url (file://) or remote url (https://)
   * Supported formats:
   * - MP3, WAV (all platforms)
   * - M3U8/HLS streams (iOS and Android)
   */
  assetPath: string;
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
  /**
   * Volume of the audio, between 0.1 and 1.0
   */
  volume?: number;
  /**
   * Audio channel number, default is 1
   */
  audioChannelNum?: number;
  /**
   * Is the audio file a URL, pass true if assetPath is a `file://` url
   * or a streaming URL (m3u8)
   */
  isUrl?: boolean;
}

export interface CurrentTimeEvent {
  /**
   * Current time of the audio in seconds
   * @since 6.5.0
   */
  currentTime: number;
  /**
   * Asset Id of the audio
   * @since 6.5.0
   */
  assetId: string;
}

export type CurrentTimeListener = (state: CurrentTimeEvent) => void;

export interface NativeAudio {
  /**
   * Configure the audio player
   * @since 5.0.0
   * @param option {@link ConfigureOptions}
   * @returns
   */
  configure(options: ConfigureOptions): Promise<void>;

  /**
   * Load an audio file
   * @since 5.0.0
   * @param option {@link PreloadOptions}
   * @returns
   */
  preload(options: PreloadOptions): Promise<void>;

  /**
   * Check if an audio file is preloaded
   *
   * @since 6.1.0
   * @param option {@link Assets}
   * @returns {Promise<boolean>}
   */
  isPreloaded(options: PreloadOptions): Promise<{ found: boolean }>;

  /**
   * Play an audio file
   * @since 5.0.0
   * @param option {@link AssetPlayOptions}
   * @returns
   */
  play(options: AssetPlayOptions): Promise<void>;

  /**
   * Pause an audio file
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns
   */
  pause(options: Assets): Promise<void>;

  /**
   * Resume an audio file
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns
   */
  resume(options: Assets): Promise<void>;

  /**
   * Stop an audio file
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns
   */
  loop(options: Assets): Promise<void>;

  /**
   * Stop an audio file
   * @since 5.0.0
   * @param option {@link AssetStopOptions}
   * @returns
   */
  stop(options: AssetStopOptions): Promise<void>;

  /**
   * Unload an audio file
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns
   */
  unload(options: Assets): Promise<void>;

  /**
   * Set the volume of an audio file
   * @since 5.0.0
   * @param option {@link AssetVolume}
   * @returns {Promise<void>}
   */
  setVolume(options: AssetVolume): Promise<void>;

  /**
   * Set the rate of an audio file
   * @since 5.0.0
   * @param option {@link AssetRate}
   * @returns {Promise<void>}
   */
  setRate(options: AssetRate): Promise<void>;

  /**
   * Set the current time of an audio file
   * @since 6.5.0
   * @param option {@link AssetSetTime}
   * @returns {Promise<void>}
   */
  setCurrentTime(options: AssetSetTime): Promise<void>;

  /**
   * Get the current time of an audio file
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns {Promise<{ currentTime: number }>}
   */
  getCurrentTime(options: Assets): Promise<{ currentTime: number }>;

  /**
   * Get the duration of an audio file in seconds
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns {Promise<{ duration: number }>}
   */
  getDuration(options: Assets): Promise<{ duration: number }>;

  /**
   * Check if an audio file is playing
   *
   * @since 5.0.0
   * @param option {@link Assets}
   * @returns {Promise<boolean>}
   */
  isPlaying(options: Assets): Promise<{ isPlaying: boolean }>;

  /**
   * Listen for complete event
   *
   * @since 5.0.0
   * return {@link CompletedEvent}
   */
  addListener(eventName: 'complete', listenerFunc: CompletedListener): Promise<PluginListenerHandle>;

  /**
   * Listen for current time updates
   * Emits every 100ms while audio is playing
   *
   * @since 6.5.0
   * return {@link CurrentTimeEvent}
   */
  addListener(eventName: 'currentTime', listenerFunc: CurrentTimeListener): Promise<PluginListenerHandle>;

  /**
   * Clear the audio cache for remote audio files
   * @since 6.5.0
   * @returns {Promise<void>}
   */
  clearCache(): Promise<void>;
}
