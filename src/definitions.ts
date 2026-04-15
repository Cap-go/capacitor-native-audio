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

export interface AssetPauseOptions {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;

  /**
   * Whether to fade out the audio before pausing
   */
  fadeOut?: boolean;

  /**
   * Fade out duration in seconds.
   * Default is 1s.
   */
  fadeOutDuration?: number;
}

export interface AssetResumeOptions {
  /**
   * Asset Id, unique identifier of the file
   */
  assetId: string;

  /**
   * Whether to fade in the audio during resume
   */
  fadeIn?: boolean;

  /**
   * Fade in duration in seconds.
   * Default is 1s.
   */
  fadeInDuration?: number;
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
  /**
   * Enable background audio playback (Android only)
   *
   * When enabled, audio will continue playing when the app is backgrounded or the screen is locked.
   * The plugin will skip the automatic pause/resume logic that normally occurs when the app
   * enters the background or returns to the foreground.
   *
   * **Important Android Requirements:**
   * To use background playback on Android, your app must:
   * 1. Declare the required permissions in `AndroidManifest.xml`:
   *    - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
   *    - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />`
   *    - `<uses-permission android:name="android.permission.WAKE_LOCK" />`
   * 2. Start a Foreground Service with a media-style notification before backgrounding
   *    (the plugin does not automatically create or manage the foreground service)
   * 3. Use `showNotification: true` to display playback controls in the notification
   *
   * **Usage Example:**
   * ```typescript
   * await NativeAudio.configure({
   *   backgroundPlayback: true,
   *   showNotification: true
   * });
   * // Start your foreground service here
   * // Then preload and play audio as normal
   * ```
   *
   * @default false
   * @platform Android
   * @since 8.2.0
   */
  backgroundPlayback?: boolean;
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

export interface PlayOnceOptions {
  /**
   * Path to the audio file, relative path of the file, absolute url (file://) or remote url (https://)
   * Supported formats:
   * - MP3, WAV (all platforms)
   * - M3U8/HLS streams (iOS and Android)
   */
  assetPath: string;
  /**
   * Volume of the audio, between 0.1 and 1.0
   * @default 1.0
   */
  volume?: number;
  /**
   * Is the audio file a URL, pass true if assetPath is a `file://` url
   * or a streaming URL (m3u8)
   * @default false
   */
  isUrl?: boolean;
  /**
   * Automatically start playback after loading
   * @default true
   */
  autoPlay?: boolean;
  /**
   * Delete the audio file from disk after playback completes
   * Only works for local files (file:// URLs), ignored for remote URLs
   * @default false
   * @since 7.11.0
   */
  deleteAfterPlay?: boolean;
  /**
   * Metadata to display in the notification center when audio is playing.
   * Only used when `showNotification: true` is set in `configure()`.
   *
   * See {@link ConfigureOptions.showNotification} for important details about
   * how this affects audio mixing behavior on iOS.
   *
   * @see NotificationMetadata
   * @since 7.10.0
   */
  notificationMetadata?: NotificationMetadata;
  /**
   * Custom HTTP headers to include when fetching remote audio files.
   * Only used when isUrl is true and assetPath is a remote URL (http/https).
   * Example: { 'x-api-key': 'abc123', 'Authorization': 'Bearer token' }
   *
   * @since 7.10.0
   */
  headers?: Record<string, string>;
}

export interface PlayOnceResult {
  /**
   * The internally generated asset ID for this playback
   * Can be used to control playback (pause, stop, etc.) before completion
   */
  assetId: string;
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
  /**
   * Custom HTTP headers to include when fetching remote audio files.
   * Only used when isUrl is true and assetPath is a remote URL (http/https).
   * Example: { 'x-api-key': 'abc123', 'Authorization': 'Bearer token' }
   *
   * @since 7.10.0
   */
  headers?: Record<string, string>;
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

export type PlaybackStateValue = 'playing' | 'paused' | 'stopped';

export interface PlaybackStateEvent {
  /**
   * Asset Id of the audio
   */
  assetId: string;
  /**
   * Resolved playback state after a local or remote transport action.
   */
  state: PlaybackStateValue;
  /**
   * Reason for the state change, for example `play`, `pause`, `remotePlay`, or `complete`.
   */
  reason: string;
  /**
   * Whether the asset is currently playing.
   */
  isPlaying: boolean;
  /**
   * Current playback position in seconds when available.
   */
  currentTime?: number;
  /**
   * Total playback duration in seconds when available.
   */
  duration?: number;
}

export type PlaybackStateListener = (state: PlaybackStateEvent) => void;

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
   * Play an audio file once with automatic cleanup
   *
   * Method designed for simple, single-shot audio playback,
   * such as notification sounds, UI feedback, or other short audio clips
   * that don't require manual state management.
   *
   * **Key Features:**
   * - **Fire-and-forget**: No need to manually preload, play, stop, or unload
   * - **Auto-cleanup**: Asset is automatically unloaded after playback completes
   * - **Optional file deletion**: Can delete local files after playback (useful for temp files)
   * - **Returns assetId**: Can still control playback if needed (pause, stop, etc.)
   *
   * **Use Cases:**
   * - Notification sounds
   * - UI sound effects (button clicks, alerts)
   * - Short audio clips that play once
   * - Temporary audio files that should be cleaned up
   *
   * **Comparison with regular play():**
   * - `play()`: Requires manual preload, play, and unload steps
   * - `playOnce()`: Handles everything automatically with a single call
   *
   * @example
   * ```typescript
   * // Simple one-shot playback
   * await NativeAudio.playOnce({ assetPath: 'audio/notification.mp3' });
   *
   * // Play and delete the file after completion
   * await NativeAudio.playOnce({
   *   assetPath: 'file:///path/to/temp/audio.mp3',
   *   isUrl: true,
   *   deleteAfterPlay: true
   * });
   *
   * // Get the assetId to control playback
   * const { assetId } = await NativeAudio.playOnce({
   *   assetPath: 'audio/long-track.mp3',
   *   autoPlay: true
   * });
   * // Later, you can stop it manually if needed
   * await NativeAudio.stop({ assetId });
   * ```
   *
   * @since 7.11.0
   * @param options {@link PlayOnceOptions}
   * @returns {Promise<PlayOnceResult>} Object containing the generated assetId
   */
  playOnce(options: PlayOnceOptions): Promise<PlayOnceResult>;
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
   * @param option {@link AssetPauseOptions}
   * @returns
   */
  pause(options: AssetPauseOptions): Promise<void>;

  /**
   * Resume an audio file
   * @since 5.0.0
   * @param option {@link AssetResumeOptions}
   * @returns
   */
  resume(options: AssetResumeOptions): Promise<void>;

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
   * Listen for playback state changes, including notification and lock-screen transport controls.
   *
   * @since 8.3.15
   * return {@link PlaybackStateEvent}
   */
  addListener(eventName: 'playbackState', listenerFunc: PlaybackStateListener): Promise<PluginListenerHandle>;
  /**
   * Clear the audio cache for remote audio files
   * @since 6.5.0
   * @returns {Promise<void>}
   */
  clearCache(): Promise<void>;

  /**
   * Set debug mode logging
   * @since 6.5.0
   * @param options - Options to enable or disable debug mode
   */
  setDebugMode(options: { enabled: boolean }): Promise<void>;

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
