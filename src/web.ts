import { WebPlugin } from '@capacitor/core';

import { AudioAsset } from './audio-asset';
import type {
  ConfigureOptions,
  PreloadOptions,
  AssetPlayOptions,
  Assets,
  AssetSetTime,
  AssetVolume,
  AssetRate,
  AssetStopOptions,
} from './definitions';
import { NativeAudio } from './definitions';

export class NativeAudioWeb extends WebPlugin implements NativeAudio {
  private static readonly FILE_LOCATION: string = '';
  private static readonly DEFAULT_FADE_DURATION_SEC: number = 1;
  private static readonly CURRENT_TIME_UPDATE_INTERVAL: number = 100;

  private static readonly AUDIO_PRELOAD_OPTIONS_MAP: Map<string, PreloadOptions> = new Map<string, PreloadOptions>();
  private static readonly AUDIO_ASSET_BY_ASSET_ID: Map<string, AudioAsset> = new Map<string, AudioAsset>();
  private static readonly AUDIO_CONTEXT_MAP: Map<HTMLMediaElement, AudioContext> = new Map();
  private static readonly MEDIA_ELEMENT_SOURCE_MAP: Map<HTMLMediaElement, MediaElementAudioSourceNode> = new Map();
  private static readonly GAIN_NODE_MAP: Map<HTMLMediaElement, GainNode> = new Map();
  private static readonly INITIAL_VOLUME_MAP: Map<HTMLMediaElement, number> = new Map();

  private currentTimeIntervals: Map<string, number> = new Map();

  private fadeOutTimer = 0;
  private startTimer = 0;
  private zeroVolume = 0.0001; // Avoids the gain node being set to 0 for exponential ramping

  async resume(options: Assets): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.startCurrentTimeUpdates(options.assetId);
    if (audio.paused) {
      return audio.play();
    }
  }

  async pause(options: Assets): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.cancelGainNodeRamp(audio); // cancel any existing scheduled volume changes
    this.clearFadeOutTimer();
    this.stopCurrentTimeUpdates(options.assetId);
    return audio.pause();
  }

  async setCurrentTime(options: AssetSetTime): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.currentTime = options.time;
    return;
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

  async configure(options: ConfigureOptions): Promise<void> {
    throw `configure is not supported for web: ${JSON.stringify(options)}`;
  }

  async isPreloaded(options: PreloadOptions): Promise<{ found: boolean }> {
    try {
      return { found: !!this.getAudioAsset(options.assetId) };
    } catch (e) {
      return { found: false };
    }
  }

  async preload(options: PreloadOptions): Promise<void> {
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
      audio.crossOrigin = 'anonymous';
      audio.src = options.assetPath;
      audio.autoplay = false;
      audio.loop = false;

      audio.preload = 'metadata';
      audio.addEventListener('loadedmetadata', () => {
        resolve();
      });
      audio.addEventListener('error', () => reject('Error loading audio file'));

      if (options.volume) {
        audio.volume = options.volume;
        NativeAudioWeb.INITIAL_VOLUME_MAP.set(audio, options.volume);
      } else {
        NativeAudioWeb.INITIAL_VOLUME_MAP.set(audio, audio.volume);
      }
      NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.set(options.assetId, new AudioAsset(audio));
    });
  }
  private onEnded(assetId: string): void {
    this.notifyListeners('complete', { assetId });
  }

  async play(options: AssetPlayOptions): Promise<void> {
    this.clearFadeOutTimer();
    const { delay = 0 } = options;
    if (delay > 0) {
      this.startTimer = setTimeout(() => {
        this.doPlay(options);
        this.startTimer = 0;
      }, delay * 1000);
    } else {
      await this.doPlay(options);
    }
  }

  private async doPlay(options: AssetPlayOptions): Promise<void> {
    const { assetId, time = 0 } = options;

    if (!NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.has(assetId)) {
      throw `no asset for assetId "${assetId}" available. Call preload first!`;
    }

    const preloadOptions = NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.get(assetId) as PreloadOptions;

    // unload asset to create a new HTMLAudioElement because reusing the same element causes issues with the audio context
    await this.unload(options);

    // preload the asset again to create a new HTMLAudioElement
    await this.preload(preloadOptions);

    const audio = this.getAudioAsset(assetId).audio;
    audio.loop = false;
    audio.currentTime = time;
    audio.addEventListener('ended', () => this.onEnded(assetId), {
      once: true,
    });

    if (options.volume) {
      audio.volume = options.volume;
      NativeAudioWeb.INITIAL_VOLUME_MAP.set(audio, options.volume);
      this.setGainNodeVolume(audio, options.volume);
    } else if (!NativeAudioWeb.INITIAL_VOLUME_MAP.has(audio)) {
      NativeAudioWeb.INITIAL_VOLUME_MAP.set(audio, audio.volume);
    }

    audio.play();
    this.startCurrentTimeUpdates(assetId);

    if (options.fadeIn) {
      const fadeDuration = options.fadeInDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      this.setGainNodeVolume(audio, 0);
      const initialVolume = NativeAudioWeb.INITIAL_VOLUME_MAP.get(audio) ?? 1;
      this.linearRampGainNodeVolume(audio, initialVolume, fadeDuration);
    }

    if (options.fadeOut && !Number.isNaN(audio.duration) && Number.isFinite(audio.duration)) {
      const fadeDuration = options.fadeOutDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;

      const fadeOutStartTime = options.fadeOutStartTime || audio.duration - fadeDuration;

      this.fadeOutTimer = setTimeout(() => {
        this.setGainNodeVolume(audio, audio.volume);
        this.linearRampGainNodeVolume(audio, 0, fadeDuration);
        this.fadeOutTimer = 0;
      }, fadeOutStartTime * 1000);
    }
  }

  async loop(options: Assets): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.reset(audio);
    audio.loop = true;
    this.startCurrentTimeUpdates(options.assetId);
    return audio.play();
  }

  async stop(options: AssetStopOptions): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;

    this.clearFadeOutTimer();
    this.cancelGainNodeRamp(audio); // cancel any existing scheduled volume changes
    if (!audio.paused && options.fadeOut) {
      const fadeDuration = options.fadeOutDuration || NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;

      this.linearRampGainNodeVolume(audio, 0, fadeDuration);
      this.fadeOutTimer = setTimeout(() => {
        this.doStop(audio, options);
      }, fadeDuration * 1000);
    } else {
      this.doStop(audio, options);
    }
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
        break;
      }
    }
    this.clearFadeOutTimer();
    this.clearStartTimer();
    this.cancelGainNodeRamp(audio);
    const initialVolume = NativeAudioWeb.INITIAL_VOLUME_MAP.get(audio) ?? 1;
    this.setGainNodeVolume(audio, initialVolume);
  }

  private clearFadeOutTimer(): void {
    if (this.fadeOutTimer) {
      clearTimeout(this.fadeOutTimer);
      this.fadeOutTimer = 0;
    }
  }

  private clearStartTimer(): void {
    if (this.startTimer) {
      clearTimeout(this.startTimer);
      this.startTimer = 0;
    }
  }

  async unload(options: Assets): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.reset(audio);
    NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.delete(options.assetId);
    NativeAudioWeb.AUDIO_PRELOAD_OPTIONS_MAP.delete(options.assetId);

    this.cleanupAudioContext(audio);
    NativeAudioWeb.INITIAL_VOLUME_MAP.delete(audio);
  }

  private cleanupAudioContext(audio: HTMLMediaElement): void {
    const gainNode = NativeAudioWeb.GAIN_NODE_MAP.get(audio);
    if (gainNode) {
      gainNode.disconnect();
      NativeAudioWeb.GAIN_NODE_MAP.delete(audio);
    }
    const audioContext = NativeAudioWeb.AUDIO_CONTEXT_MAP.get(audio);
    if (audioContext) {
      audioContext.close();
      NativeAudioWeb.AUDIO_CONTEXT_MAP.delete(audio);
    }
    const sourceNode = NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.get(audio);
    if (sourceNode) {
      sourceNode.disconnect();
      NativeAudioWeb.MEDIA_ELEMENT_SOURCE_MAP.delete(audio);
    }
  }

  async setVolume(options: AssetVolume): Promise<void> {
    if (typeof options?.volume !== 'number') {
      throw 'no volume provided';
    }
    const { volume, duration = 0 } = options;

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    this.cancelGainNodeRamp(audio); // cancel any existing scheduled volume changes
    if (duration > 0) {
      this.exponentialRampGainNodeVolume(audio, volume, duration);
    } else {
      audio.volume = volume;
    }
  }

  async setRate(options: AssetRate): Promise<void> {
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
    // Web audio doesn't have a persistent cache to clear
    return;
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
    const track = this.getOrCreateMediaElementSource(audioContext, audio);
    const gainNode = this.getOrCreateGainNode(audio, track);

    if (time) {
      gainNode.gain.setValueAtTime(volume, time);
    } else {
      gainNode.gain.setValueAtTime(volume, audioContext.currentTime);
    }
  }

  private exponentialRampGainNodeVolume(audio: HTMLMediaElement, volume: number, duration: number): void {
    const audioContext = this.getOrCreateAudioContext(audio);
    const track = this.getOrCreateMediaElementSource(audioContext, audio);
    const gainNode = this.getOrCreateGainNode(audio, track);
    let adjustedVolume = volume;
    if (volume < this.zeroVolume) {
      adjustedVolume = this.zeroVolume;
    }
    // Use exponential ramping for human hearing perception
    gainNode.gain.exponentialRampToValueAtTime(adjustedVolume, audioContext.currentTime + duration);
  }

  private linearRampGainNodeVolume(audio: HTMLMediaElement, volume: number, duration: number): void {
    const audioContext = this.getOrCreateAudioContext(audio);
    const track = this.getOrCreateMediaElementSource(audioContext, audio);
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
        const currentTime = Math.round(audio.currentTime * 10) / 10; // Round to nearest 100ms
        this.notifyListeners('currentTime', { assetId, currentTime });
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
    } else {
      for (const intervalId of this.currentTimeIntervals.values()) {
        clearInterval(intervalId);
      }
      this.currentTimeIntervals.clear();
    }
  }
}

const NativeAudio = new NativeAudioWeb();

export { NativeAudio };
