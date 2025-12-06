import { WebPlugin } from '@capacitor/core';

import { AudioAsset } from './audio-asset';
import type { ConfigureOptions, PlayOnceOptions, PlayOnceResult, PreloadOptions } from './definitions';
import { NativeAudio } from './definitions';

export class NativeAudioWeb extends WebPlugin implements NativeAudio {
  private static readonly FILE_LOCATION: string = '';
  private static readonly AUDIO_ASSET_BY_ASSET_ID: Map<string, AudioAsset> = new Map<string, AudioAsset>();
  private static readonly playOnceAssets: Set<string> = new Set<string>();
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

  async playOnce(options: PlayOnceOptions): Promise<PlayOnceResult> {
    // Generate a unique temporary asset ID
    const assetId = `playOnce_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
    NativeAudioWeb.playOnceAssets.add(assetId);

    const autoPlay = options.autoPlay !== false; // Default to true
    const deleteAfterPlay = options.deleteAfterPlay ?? false;

    try {
      // Preload the asset
      await this.preload({
        assetId,
        assetPath: options.assetPath,
        volume: options.volume,
        isUrl: options.isUrl,
      });

      // Set up automatic cleanup on completion
      const audio = this.getAudioAsset(assetId).audio;
      const cleanupHandler = async () => {
        try {
          // Unload the asset
          await this.unload({ assetId });
          NativeAudioWeb.playOnceAssets.delete(assetId);

          // Delete file if requested (Web can't actually delete files from disk)
          // This is a no-op on web, but we keep the interface consistent
          if (deleteAfterPlay) {
            console.warn('[NativeAudio] deleteAfterPlay is not supported on web platform. File deletion is ignored.');
          }
        } catch (error) {
          console.error('[NativeAudio] Error during playOnce cleanup:', error);
        }
      };

      audio.addEventListener('ended', cleanupHandler, { once: true });

      // Handle errors during playback - cleanup if play fails
      audio.addEventListener(
        'error',
        () => {
          cleanupHandler().catch((error) => {
            console.error('[NativeAudio] Error during error cleanup:', error);
          });
        },
        { once: true },
      );

      // Auto-play if requested
      if (autoPlay) {
        await this.play({ assetId });
      }

      return { assetId };
    } catch (error) {
      // Cleanup on failure
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

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }

  async deinitPlugin(): Promise<void> {
    // Stop and unload all audio assets
    for (const [assetId] of NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID) {
      await this.unload({ assetId });
    }
    return;
  }
}

const NativeAudio = new NativeAudioWeb();

export { NativeAudio };
