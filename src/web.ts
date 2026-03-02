import { WebPlugin } from '@capacitor/core';

import { AudioAsset } from './audio-asset';
import type {
  Assets,
  AssetPauseOptions,
  AssetPlayOptions,
  AssetRate,
  AssetResumeOptions,
  AssetSetTime,
  AssetStopOptions,
  AssetVolume,
  ConfigureOptions,
  PlayOnceOptions,
  PlayOnceResult,
  PreloadOptions,
} from './definitions';
import { NativeAudio } from './definitions';

type AudioAssetData = {
  fadeOut?: boolean;
  fadeOutStartTime?: number;
  fadeOutDuration?: number;
  fadeOutToStopTimer?: number;
  fadeInTimer?: number;
  startTimer?: number;
  volume?: number;
  volumeBeforePause?: number;
};

export class NativeAudioWeb extends WebPlugin implements NativeAudio {
  private static readonly LOG_TAG: string = '[NativeAudioWeb]';
  private static readonly FILE_LOCATION: string = '';
  private static readonly DEFAULT_FADE_DURATION_SEC: number = 1;
  private static readonly CURRENT_TIME_UPDATE_INTERVAL: number = 100;

  private static readonly AUDIO_PRELOAD_OPTIONS_MAP: Map<string, PreloadOptions> = new Map<string, PreloadOptions>();
  private static readonly AUDIO_DATA_MAP: Map<string, AudioAssetData> = new Map<string, AudioAssetData>();
  private static readonly AUDIO_ASSET_BY_ASSET_ID: Map<string, AudioAsset> = new Map<string, AudioAsset>();
  private static readonly AUDIO_CONTEXT_MAP: Map<HTMLMediaElement, AudioContext> = new Map<
    HTMLMediaElement,
    AudioContext
  >();
  private static readonly MEDIA_ELEMENT_SOURCE_MAP: Map<HTMLMediaElement, MediaElementAudioSourceNode> = new Map<
    HTMLMediaElement,
    MediaElementAudioSourceNode
  >();
  private static readonly GAIN_NODE_MAP: Map<HTMLMediaElement, GainNode> = new Map<HTMLMediaElement, GainNode>();

  private static readonly playOnceAssets: Set<string> = new Set<string>();

  private debugMode = false;
  private currentTimeIntervals: Map<string, number> = new Map<string, number>();
  private readonly zeroVolume = 0.0001;

  constructor() {
    super();
  }

  async resume(options: AssetResumeOptions): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    const data = this.getAudioAssetData(options.assetId);
    const targetVolume = data.volumeBeforePause ?? data.volume ?? 1;

    if (options.fadeIn) {
      const fadeDuration = options.fadeInDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      this.doFadeIn(audio, fadeDuration, targetVolume);
    } else if (audio.volume <= this.zeroVolume) {
      audio.volume = targetVolume;
      this.setGainNodeVolume(audio, targetVolume);
    }

    this.clearFadeOutToStopTimer(options.assetId);
    return this.doResume(options.assetId);
  }

  private async doResume(assetId: string): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(assetId).audio;
    this.startCurrentTimeUpdates(assetId);
    if (audio.paused) {
      return audio.play();
    }
  }

  async pause(options: AssetPauseOptions): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.cancelGainNodeRamp(audio);

    const data = this.getAudioAssetData(options.assetId);
    data.volumeBeforePause = data.volume ?? audio.volume;
    this.setAudioAssetData(options.assetId, data);

    if (!audio.paused && options.fadeOut) {
      const fadeOutDuration = options.fadeOutDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      this.doFadeOut(audio, fadeOutDuration);
      data.fadeOutToStopTimer = window.setTimeout(() => {
        this.doPause(options.assetId).catch(() => {
          // no-op
        });
      }, fadeOutDuration * 1000);
      this.setAudioAssetData(options.assetId, data);
      return;
    }

    return this.doPause(options.assetId);
  }

  private async doPause(assetId: string): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(assetId).audio;
    this.clearFadeOutToStopTimer(assetId);
    this.stopCurrentTimeUpdates(assetId);
    audio.pause();
  }

  async setCurrentTime(options: AssetSetTime): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.currentTime = options.time;
  }

  async getCurrentTime(options: Assets): Promise<{ currentTime: number }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    return { currentTime: audio.currentTime };
  }

  async getDuration(options: Assets): Promise<{ duration: number }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (Number.isNaN(audio.duration)) {
      throw 'no duration available';
    }
    if (!Number.isFinite(audio.duration)) {
      throw 'duration not available => media resource is streaming';
    }
    return { duration: audio.duration };
  }

  async setDebugMode(options: { enabled: boolean }): Promise<void> {
    this.debugMode = options.enabled;
    if (this.debugMode) {
      this.logInfo('Debug mode enabled');
    }
  }

  async configure(options: ConfigureOptions): Promise<void> {
    throw `configure is not supported for web: ${JSON.stringify(options)}`;
  }

  async isPreloaded(options: PreloadOptions): Promise<{ found: boolean }> {
    try {
      return { found: !!this.getAudioAsset(options.assetId) };
    } catch {
      return { found: false };
    }
  }

  async preload(options: PreloadOptions): Promise<void> {
    this.logInfo(`Preloading audio asset with options: ${JSON.stringify(options)}`);

    if (NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.has(options.assetId)) {
      throw 'AssetId already exists. Unload first if like to change!';
    }
    if (!options.assetPath?.length) {
      throw 'no assetPath provided';
    }

    NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.set(options.assetId, options);

    await new Promise<void>((resolve, reject) => {
      if (!options.isUrl && !new RegExp('^/?' + NativeAudioWeb.FILE_LOCATION).test(options.assetPath)) {
        const slashPrefix: string = options.assetPath.startsWith('/') ? '' : '/';
        options.assetPath = `${NativeAudioWeb.FILE_LOCATION}${slashPrefix}${options.assetPath}`;
      }

      const audio: HTMLAudioElement = document.createElement('audio');
      audio.id = options.assetId;
      audio.crossOrigin = 'anonymous';
      audio.src = options.assetPath;
      audio.autoplay = false;
      audio.loop = false;
      audio.preload = 'metadata';

      audio.addEventListener('loadedmetadata', () => {
        resolve();
      });

      audio.addEventListener('error', (errEvt) => {
        this.logError(`Error loading audio file: ${options.assetPath}, error: ${String(errEvt)}`);
        reject('Error loading audio file');
      });

      const data = this.getAudioAssetData(options.assetId);
      if (typeof options.volume === 'number') {
        audio.volume = options.volume;
        data.volume = options.volume;
      } else {
        data.volume = audio.volume;
      }

      NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.set(options.assetId, new AudioAsset(audio));
      this.setAudioAssetData(options.assetId, data);
      this.setGainNodeVolume(audio, data.volume ?? 1);
    });
  }

  async playOnce(options: PlayOnceOptions): Promise<PlayOnceResult> {
    const assetId = `playOnce_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
    NativeAudioWeb.playOnceAssets.add(assetId);

    const autoPlay = options.autoPlay !== false;
    const deleteAfterPlay = options.deleteAfterPlay ?? false;

    try {
      await this.preload({
        assetId,
        assetPath: options.assetPath,
        volume: options.volume,
        isUrl: options.isUrl,
      });

      const cleanupHandler = async () => {
        try {
          await this.unload({ assetId });
          NativeAudioWeb.playOnceAssets.delete(assetId);
          if (deleteAfterPlay) {
            console.warn('[NativeAudio] deleteAfterPlay is not supported on web platform. File deletion is ignored.');
          }
        } catch (error) {
          console.error('[NativeAudio] Error during playOnce cleanup:', error);
        }
      };

      if (autoPlay) {
        await this.doPlay({ assetId, volume: options.volume }, false);
      }

      const currentAudio = this.getAudioAsset(assetId).audio;
      currentAudio.addEventListener(
        'ended',
        () => {
          cleanupHandler().catch((error) => {
            console.error('[NativeAudio] Error during ended cleanup:', error);
          });
        },
        { once: true },
      );

      currentAudio.addEventListener(
        'error',
        () => {
          cleanupHandler().catch((error) => {
            console.error('[NativeAudio] Error during error cleanup:', error);
          });
        },
        { once: true },
      );

      return { assetId };
    } catch (error) {
      try {
        await this.unload({ assetId });
        NativeAudioWeb.playOnceAssets.delete(assetId);
      } catch {
        // Ignore cleanup errors
      }
      throw error;
    }
  }

  private onEnded(assetId: string): void {
    this.logDebug(`Playback ended for assetId: ${assetId}`);
    this.notifyListeners('complete', { assetId });
  }

  async play(options: AssetPlayOptions): Promise<void> {
    this.logInfo(`Playing audio asset with options: ${JSON.stringify(options)}`);

    this.clearFadeOutToStopTimer(options.assetId);

    const { delay = 0 } = options;
    if (delay > 0) {
      const data = this.getAudioAssetData(options.assetId);
      data.startTimer = window.setTimeout(() => {
        this.doPlay(options).catch((error) => {
          this.logError(`Delayed play failed: ${String(error)}`);
        });
        data.startTimer = undefined;
        this.setAudioAssetData(options.assetId, data);
      }, delay * 1000);
      this.setAudioAssetData(options.assetId, data);
      return;
    }

    await this.doPlay(options);
  }

  private async doPlay(options: AssetPlayOptions, recreateAudioElement = true): Promise<void> {
    const { assetId, time = 0 } = options;

    if (!NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.has(assetId)) {
      throw `no asset for assetId "${assetId}" available. Call preload first!`;
    }

    if (recreateAudioElement) {
      const preloadOptions = NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.get(assetId) as PreloadOptions;
      await this.unload({ assetId });
      await this.preload(preloadOptions);
    }

    const audio = this.getAudioAsset(assetId).audio;
    audio.id = assetId;
    audio.loop = false;
    audio.currentTime = time;
    audio.addEventListener('ended', () => this.onEnded(assetId), {
      once: true,
    });

    const data = this.getAudioAssetData(assetId);

    if (typeof options.volume === 'number') {
      audio.volume = options.volume;
      data.volume = options.volume;
      this.setGainNodeVolume(audio, options.volume);
    } else if (typeof data.volume !== 'number') {
      data.volume = audio.volume;
    }

    await audio.play();
    this.startCurrentTimeUpdates(assetId);

    if (options.fadeIn) {
      this.logDebug(`Fading in audio asset with assetId: ${assetId}`);
      const fadeDuration = options.fadeInDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      this.doFadeIn(audio, fadeDuration);
    }

    if (options.fadeOut && !Number.isNaN(audio.duration) && Number.isFinite(audio.duration)) {
      this.logDebug(`Scheduling fade out for audio asset with assetId: ${assetId}`);
      const fadeOutDuration = options.fadeOutDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      const fadeOutStartTime = options.fadeOutStartTime || audio.duration - fadeOutDuration;
      data.fadeOut = true;
      data.fadeOutStartTime = fadeOutStartTime;
      data.fadeOutDuration = fadeOutDuration;
    }

    this.setAudioAssetData(assetId, data);
  }

  private doFadeIn(audio: HTMLAudioElement, fadeDuration: number, targetVolume?: number): void {
    const data = this.getAudioAssetData(audio.id);
    this.setGainNodeVolume(audio, this.zeroVolume);
    const fadeToVolume = targetVolume ?? data.volume ?? 1;
    this.linearRampGainNodeVolume(audio, fadeToVolume, fadeDuration);
    data.fadeInTimer = window.setTimeout(() => {
      data.fadeInTimer = undefined;
      this.setAudioAssetData(audio.id, data);
    }, fadeDuration * 1000);
    this.setAudioAssetData(audio.id, data);
  }

  private doFadeOut(audio: HTMLAudioElement, fadeDuration: number): void {
    this.linearRampGainNodeVolume(audio, this.zeroVolume, fadeDuration);
  }

  async loop(options: Assets): Promise<void> {
    this.logInfo(`Looping audio asset with options: ${JSON.stringify(options)}`);
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.reset(audio);
    audio.loop = true;
    this.startCurrentTimeUpdates(options.assetId);
    return audio.play();
  }

  async stop(options: AssetStopOptions): Promise<void> {
    this.logInfo(`Stopping audio asset with options: ${JSON.stringify(options)}`);
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    const data = this.getAudioAssetData(options.assetId);

    this.clearFadeOutToStopTimer(options.assetId);
    this.cancelGainNodeRamp(audio);

    if (!audio.paused && options.fadeOut) {
      const fadeDuration = options.fadeOutDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      this.doFadeOut(audio, fadeDuration);
      data.fadeOutToStopTimer = window.setTimeout(() => {
        this.doStop(audio, options);
      }, fadeDuration * 1000);
      this.setAudioAssetData(options.assetId, data);
      return;
    }

    this.doStop(audio, options);
  }

  private doStop(audio: HTMLAudioElement, options: AssetStopOptions): void {
    audio.pause();
    this.onEnded(options.assetId);
    this.reset(audio);
  }

  private reset(audio: HTMLAudioElement): void {
    audio.currentTime = 0;

    for (const [assetId, asset] of NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.entries()) {
      if (asset.audio === audio) {
        this.stopCurrentTimeUpdates(assetId);
        this.clearFadeOutToStopTimer(assetId);
        this.clearStartTimer(assetId);
        this.cancelGainNodeRamp(audio);
        const data = this.getAudioAssetData(assetId);
        const initialVolume = data.volume ?? 1;
        this.setGainNodeVolume(audio, initialVolume);
        this.setAudioAssetData(assetId, data);
        break;
      }
    }
  }

  private clearFadeOutToStopTimer(assetId: string): void {
    const data = this.getAudioAssetData(assetId);
    if (data.fadeOutToStopTimer) {
      clearTimeout(data.fadeOutToStopTimer);
      data.fadeOutToStopTimer = undefined;
      this.setAudioAssetData(assetId, data);
    }
  }

  private clearStartTimer(assetId: string): void {
    const data = this.getAudioAssetData(assetId);
    if (data.startTimer) {
      clearTimeout(data.startTimer);
      data.startTimer = undefined;
      this.setAudioAssetData(assetId, data);
    }
  }

  async unload(options: Assets): Promise<void> {
    this.logInfo(`Unloading audio asset with options: ${JSON.stringify(options)}`);
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.reset(audio);
    NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.delete(options.assetId);
    NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.delete(options.assetId);
    NativeAudioWeb.AUDIO_DATA_MAP.delete(options.assetId);
    this.cleanupAudioContext(audio);
  }

  private cleanupAudioContext(audio: HTMLMediaElement): void {
    const gainNode = NativeAudioWeb.GAIN_NODE_MAP.get(audio);
    if (gainNode) {
      gainNode.disconnect();
      NativeAudioWeb.GAIN_NODE_MAP.delete(audio);
    }

    const sourceNode = NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.get(audio);
    if (sourceNode) {
      sourceNode.disconnect();
      NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.delete(audio);
    }

    const audioContext = NativeAudioWeb.AUDIO_CONTEXT_MAP.get(audio);
    if (audioContext) {
      audioContext.close().catch(() => {
        // no-op
      });
      NativeAudioWeb.AUDIO_CONTEXT_MAP.delete(audio);
    }
  }

  async setVolume(options: AssetVolume): Promise<void> {
    this.logInfo(`Setting volume for audio asset with options: ${JSON.stringify(options)}`);

    if (typeof options?.volume !== 'number') {
      throw 'no volume provided';
    }

    const { volume, duration = 0 } = options;

    const data = this.getAudioAssetData(options.assetId);
    data.volume = volume;
    this.setAudioAssetData(options.assetId, data);

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.cancelGainNodeRamp(audio);

    if (duration > 0) {
      this.exponentialRampGainNodeVolume(audio, volume, duration);
      return;
    }

    audio.volume = volume;
    this.setGainNodeVolume(audio, volume);
  }

  async setRate(options: AssetRate): Promise<void> {
    this.logInfo(`Setting playback rate for audio asset with options: ${JSON.stringify(options)}`);

    if (typeof options?.rate !== 'number') {
      throw 'no rate provided';
    }

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.playbackRate = options.rate;
  }

  async isPlaying(options: Assets): Promise<{ isPlaying: boolean }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    return { isPlaying: !audio.paused };
  }

  async clearCache(): Promise<void> {
    this.logWarning('clearCache is not supported for web. No cache to clear.');
  }

  private getAudioAsset(assetId: string): AudioAsset {
    this.checkAssetId(assetId);

    if (!NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.has(assetId)) {
      throw `no asset for assetId "${assetId}" available. Call preload first!`;
    }

    return NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.get(assetId) as AudioAsset;
  }

  private checkAssetId(assetId: string): void {
    if (typeof assetId !== 'string') {
      throw 'assetId must be a string';
    }

    if (!assetId?.length) {
      throw 'no assetId provided';
    }
  }

  private getOrCreateAudioContext(audio: HTMLMediaElement): AudioContext {
    if (NativeAudioWeb.AUDIO_CONTEXT_MAP.has(audio)) {
      return NativeAudioWeb.AUDIO_CONTEXT_MAP.get(audio) as AudioContext;
    }

    const audioContext = new AudioContext();
    NativeAudioWeb.AUDIO_CONTEXT_MAP.set(audio, audioContext);
    return audioContext;
  }

  private getOrCreateMediaElementSource(
    audioContext: AudioContext,
    audio: HTMLAudioElement,
  ): MediaElementAudioSourceNode {
    if (NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.has(audio)) {
      return NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.get(audio) as MediaElementAudioSourceNode;
    }

    const sourceNode = audioContext.createMediaElementSource(audio);
    NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.set(audio, sourceNode);
    return sourceNode;
  }

  private getOrCreateGainNode(audio: HTMLMediaElement, track: MediaElementAudioSourceNode): GainNode {
    const audioContext = this.getOrCreateAudioContext(audio);

    if (NativeAudioWeb.GAIN_NODE_MAP.has(audio)) {
      return NativeAudioWeb.GAIN_NODE_MAP.get(audio) as GainNode;
    }

    const gainNode = audioContext.createGain();
    track.connect(gainNode).connect(audioContext.destination);
    NativeAudioWeb.GAIN_NODE_MAP.set(audio, gainNode);
    return gainNode;
  }

  private setGainNodeVolume(audio: HTMLMediaElement, volume: number, time?: number): void {
    const audioContext = this.getOrCreateAudioContext(audio);
    const track = this.getOrCreateMediaElementSource(audioContext, audio as HTMLAudioElement);
    const gainNode = this.getOrCreateGainNode(audio, track);

    if (time !== undefined) {
      gainNode.gain.setValueAtTime(volume, time);
    } else {
      gainNode.gain.setValueAtTime(volume, audioContext.currentTime);
    }
  }

  private exponentialRampGainNodeVolume(audio: HTMLMediaElement, volume: number, duration: number): void {
    const audioContext = this.getOrCreateAudioContext(audio);
    const track = this.getOrCreateMediaElementSource(audioContext, audio as HTMLAudioElement);
    const gainNode = this.getOrCreateGainNode(audio, track);
    const adjustedVolume = volume < this.zeroVolume ? this.zeroVolume : volume;
    gainNode.gain.exponentialRampToValueAtTime(adjustedVolume, audioContext.currentTime + duration);
  }

  private linearRampGainNodeVolume(audio: HTMLMediaElement, volume: number, duration: number): void {
    const audioContext = this.getOrCreateAudioContext(audio);
    const track = this.getOrCreateMediaElementSource(audioContext, audio as HTMLAudioElement);
    const gainNode = this.getOrCreateGainNode(audio, track);
    gainNode.gain.linearRampToValueAtTime(volume, audioContext.currentTime + duration);
  }

  private cancelGainNodeRamp(audio: HTMLMediaElement): void {
    const gainNode = NativeAudioWeb.GAIN_NODE_MAP.get(audio);
    if (gainNode) {
      gainNode.gain.cancelScheduledValues(0);
    }
  }

  private startCurrentTimeUpdates(assetId: string): void {
    this.stopCurrentTimeUpdates(assetId);

    const audio = this.getAudioAsset(assetId).audio;
    const intervalId = window.setInterval(() => {
      if (!audio.paused) {
        const currentTime = Math.round(audio.currentTime * 10) / 10;
        this.notifyListeners('currentTime', { assetId, currentTime });
        this.logDebug(`Current time update for assetId: ${assetId}, currentTime: ${currentTime}`);

        const data = this.getAudioAssetData(assetId);
        if (data.fadeOut && typeof data.fadeOutStartTime === 'number' && currentTime >= data.fadeOutStartTime) {
          this.cancelGainNodeRamp(audio);
          const fadeOutDuration = data.fadeOutDuration ?? NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
          this.doFadeOut(audio, fadeOutDuration);
          data.fadeOut = false;
          this.setAudioAssetData(assetId, data);
        }
      } else {
        this.stopCurrentTimeUpdates(assetId);
      }
    }, NativeAudioWeb.CURRENT_TIME_UPDATE_INTERVAL);

    this.currentTimeIntervals.set(assetId, intervalId);
  }

  private stopCurrentTimeUpdates(assetId?: string): void {
    if (assetId) {
      const intervalId = this.currentTimeIntervals.get(assetId);
      if (intervalId) {
        clearInterval(intervalId);
        this.currentTimeIntervals.delete(assetId);
      }
      return;
    }

    for (const intervalId of this.currentTimeIntervals.values()) {
      clearInterval(intervalId);
    }
    this.currentTimeIntervals.clear();
  }

  private getAudioAssetData(assetId: string): AudioAssetData {
    return NativeAudioWeb.AUDIO_DATA_MAP.get(assetId) || {};
  }

  private setAudioAssetData(assetId: string, data: AudioAssetData): void {
    const currentData = NativeAudioWeb.AUDIO_DATA_MAP.get(assetId) || {};
    const newData = { ...currentData, ...data };
    NativeAudioWeb.AUDIO_DATA_MAP.set(assetId, newData);
  }

  private logError(message: string): void {
    if (!this.debugMode) return;
    console.error(`${NativeAudioWeb.LOG_TAG} Error: ${message}`);
  }

  private logWarning(message: string): void {
    if (!this.debugMode) return;
    console.warn(`${NativeAudioWeb.LOG_TAG} Warning: ${message}`);
  }

  private logInfo(message: string): void {
    if (!this.debugMode) return;
    console.info(`${NativeAudioWeb.LOG_TAG} Info: ${message}`);
  }

  private logDebug(message: string): void {
    if (!this.debugMode) return;
    console.debug(`${NativeAudioWeb.LOG_TAG} Debug: ${message}`);
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }

  async deinitPlugin(): Promise<void> {
    for (const [assetId] of NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID) {
      await this.unload({ assetId });
    }
    this.stopCurrentTimeUpdates();
  }
}

const NativeAudio = new NativeAudioWeb();

export { NativeAudio };
