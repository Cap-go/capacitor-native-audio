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

export interface AssetPlayOptions {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;
  /**
   * Time to start playing the audio, in milliseconds
   */
  time?: number;
  /**
   * Delay to start playing the audio, in milliseconds
   */
  delay?: number;
}

export interface ConfigureOptions {
  /**
   * Play the audio with Fade effect, only available for IOS
   */
  fade?: boolean;
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
  /**
   * Show audio playback in the notification center (iOS and Android)
   * When enabled, displays audio metadata (title, artist, album, artwork) in the system notification
   * and Control Center (iOS) or lock screen.
   * 
   * **Important iOS Behavior:**
   * Enabling this option changes the audio session category to `.playback` with `.default` mode,
   * which means your app's audio will **interrupt** other apps' audio (like background music from
   * Spotify, Apple Music, etc.) instead of mixing with it. This is required for the Now Playing
   * info to appear in Control Center and on the lock screen.
   * 
   * **Trade-offs:**
   * - `showNotification: true` → Shows Now Playing controls, but interrupts other audio
   * - `showNotification: false` → Audio mixes with other apps, but no Now Playing controls
   * 
   * Use this when your app is the primary audio source (music players, podcast apps, etc.).
   * Disable this for secondary audio like sound effects or notification sounds where mixing
   * with background music is preferred.
   * 
   * @see https://github.com/Cap-go/capacitor-native-audio/issues/202
   */
  showNotification?: boolean;
}

/**
 * Metadata to display in the notification center, Control Center (iOS), and lock screen
 * when `showNotification` is enabled in `configure()`.
 * 
 * Note: This metadata will only be displayed if `showNotification: true` is set in the
 * `configure()` method. See {@link ConfigureOptions.showNotification} for important
 * behavior details about audio mixing on iOS.
 */
export interface NotificationMetadata {
  /**
   * The title to display in the notification center
   */
  title?: string;
  /**
   * The artist name to display in the notification center
   */
  artist?: string;
  /**
   * The album name to display in the notification center
   */
  album?: string;
  /**
   * URL or local path to the artwork/album art image
   */
  artworkUrl?: string;
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
  /**
   * Metadata to display in the notification center when audio is playing.
   * Only used when `showNotification: true` is set in `configure()`.
   * 
   * See {@link ConfigureOptions.showNotification} for important details about
   * how this affects audio mixing behavior on iOS.
   * 
   * @see NotificationMetadata
   */
  notificationMetadata?: NotificationMetadata;
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
   * @param option {@link PlayOptions}
   * @returns
   */
  play(options: { assetId: string; time?: number; delay?: number }): Promise<void>;
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
   * @param option {@link Assets}
   * @returns
   */
  stop(options: Assets): Promise<void>;
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
  setVolume(options: { assetId: string; volume: number }): Promise<void>;
  /**
   * Set the rate of an audio file
   * @since 5.0.0
   * @param option {@link AssetPlayOptions}
   * @returns {Promise<void>}
   */
  setRate(options: { assetId: string; rate: number }): Promise<void>;
  /**
   * Set the current time of an audio file
   * @since 6.5.0
   * @param option {@link AssetPlayOptions}
   * @returns {Promise<void>}
   */
  setCurrentTime(options: { assetId: string; time: number }): Promise<void>;
  /**
   * Get the current time of an audio file
   * @since 5.0.0
   * @param option {@link AssetPlayOptions}
   * @returns {Promise<{ currentTime: number }>}
   */
  getCurrentTime(options: { assetId: string }): Promise<{ currentTime: number }>;
  /**
   * Get the duration of an audio file
   * @since 5.0.0
   * @param option {@link AssetPlayOptions}
   * @returns {Promise<{ duration: number }>}
   */
  getDuration(options: Assets): Promise<{ duration: number }>;
  /**
   * Check if an audio file is playing
   *
   * @since 5.0.0
   * @param option {@link AssetPlayOptions}
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

  /**
   * Get the native Capacitor plugin version
   *
   * @returns {Promise<{ id: string }>} an Promise with version for this device
   * @throws An error if the something went wrong
   */
  getPluginVersion(): Promise<{ version: string }>;

  /**
   * Deinitialize the plugin and restore original audio session settings
   * This method stops all playing audio and reverts any audio session changes made by the plugin
   * Use this when you need to ensure compatibility with other audio plugins
   *
   * @since 7.7.0
   * @returns {Promise<void>}
   */
  deinitPlugin(): Promise<void>;
}
