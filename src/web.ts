import { WebPlugin } from '@capacitor/core';

import { AudioAsset } from './audio-asset';
import type { ConfigureOptions, PreloadOptions } from './definitions';
import { NativeAudio } from './definitions';

export class NativeAudioWeb extends WebPlugin implements NativeAudio {
  private static readonly FILE_LOCATION: string = '';
  private static readonly AUDIO_ASSET_BY_ASSET_ID: Map<string, AudioAsset> = new Map<string, AudioAsset>();

  constructor() {
    super();
  }

  async resume(options: { assetId: string }): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (audio.paused) {
      return audio.play();
    }
  }

  async pause(options: { assetId: string }): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    return audio.pause();
  }

  async setCurrentTime(options: { assetId: string; time: number }): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.currentTime = options.time;
    return;
  }

  async getCurrentTime(options: { assetId: string }): Promise<{ currentTime: number }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    return { currentTime: audio.currentTime };
  }

  async getDuration(options: { assetId: string }): Promise<{ duration: number }> {
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
    if (!options.isUrl && !new RegExp('^/?' + NativeAudioWeb.FILE_LOCATION).test(options.assetPath)) {
      const slashPrefix: string = options.assetPath.startsWith('/') ? '' : '/';
      options.assetPath = `${NativeAudioWeb.FILE_LOCATION}${slashPrefix}${options.assetPath}`;
    }
    const audio: HTMLAudioElement = new Audio(options.assetPath);
    audio.autoplay = false;
    audio.loop = false;
    audio.preload = 'auto';
    if (options.volume) {
      audio.volume = options.volume;
    }
    NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.set(options.assetId, new AudioAsset(audio));
  }
  private onEnded(assetId: string): void {
    this.notifyListeners('complete', { assetId });
  }

  async play(options: { assetId: string; time?: number }): Promise<void> {
    const { assetId, time = 0 } = options;
    const audio = this.getAudioAsset(assetId).audio;
    await this.stop(options);
    audio.loop = false;
    audio.currentTime = time;
    audio.addEventListener('ended', () => this.onEnded(assetId), {
      once: true,
    });
    return audio.play();
  }

  async loop(options: { assetId: string }): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    await this.stop(options);
    audio.loop = true;
    return audio.play();
  }

  async stop(options: { assetId: string }): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.pause();
    audio.loop = false;
    audio.currentTime = 0;
  }

  async unload(options: { assetId: string }): Promise<void> {
    await this.stop(options);
    NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.delete(options.assetId);
  }

  async setVolume(options: { assetId: string; volume: number }): Promise<void> {
    if (typeof options?.volume !== 'number') {
      throw 'no volume provided';
    }

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.volume = options.volume;
  }

  async setRate(options: { assetId: string; rate: number }): Promise<void> {
    if (typeof options?.rate !== 'number') {
      throw 'no rate provided';
    }

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.playbackRate = options.rate;
  }

  async isPlaying(options: { assetId: string }): Promise<{ isPlaying: boolean }> {
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
}

const NativeAudio = new NativeAudioWeb();

export { NativeAudio };
