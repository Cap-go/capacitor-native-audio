import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { NativeAudio } from './web';

class MockAudioContext {
  destination = {};
  currentTime = 0;

  createGain() {
    const node = {
      gain: {
        setValueAtTime: () => undefined,
        linearRampToValueAtTime: () => undefined,
        cancelScheduledValues: () => undefined,
      },
      connect: () => node,
      disconnect: () => undefined,
    };
    return node;
  }

  createMediaElementSource() {
    return {
      connect: (node: { connect: (target: unknown) => unknown }) => node,
      disconnect: () => undefined,
    };
  }

  close() {
    return Promise.resolve();
  }
}

function blobAudioUrl(): string {
  const bytes = new Uint8Array(44);
  bytes.set([82, 73, 70, 70, 8, 0, 0, 0, 87, 65, 86, 69], 0);
  return URL.createObjectURL(new Blob([bytes], { type: 'audio/wav' }));
}

describe('NativeAudio web queue and playback state', () => {
  beforeEach(async () => {
    vi.stubGlobal('AudioContext', MockAudioContext);

    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      const element = originalCreateElement(tagName);
      if (tagName.toLowerCase() === 'audio') {
        const audio = element as HTMLAudioElement;
        queueMicrotask(() => {
          Object.defineProperty(audio, 'duration', { value: 12, configurable: true });
          audio.dispatchEvent(new Event('loadedmetadata'));
        });
      }
      return element;
    });

    await NativeAudio.deinitPlugin();
    await NativeAudio.configure({ progressInterval: 0.5 });
  });

  afterEach(async () => {
    await NativeAudio.deinitPlugin();
    vi.unstubAllGlobals();
  });

  it('getPlaybackState_returnsStoppedSnapshotWhenNoAssetLoaded', async () => {
    const snapshot = await NativeAudio.getPlaybackState();
    expect(snapshot.state).toBe('stopped');
    expect(snapshot.assetId).toBe('');
  });

  it('setQueue_buildsQueueStateFromBlobUrls', async () => {
    const first = blobAudioUrl();
    const second = blobAudioUrl();

    await NativeAudio.setQueue({
      items: [
        { assetId: 'q1', assetPath: first, isUrl: true },
        { assetId: 'q2', assetPath: second, isUrl: true },
      ],
      startIndex: 1,
      autoPlay: false,
    });

    const state = await NativeAudio.getQueueState();
    expect(state.count).toBe(2);
    expect(state.index).toBe(1);
    expect(state.assetId).toBe('q2');
  }, 10000);

  it('stop_reportsStoppedPlaybackStateOnManualStop', async () => {
    const url = blobAudioUrl();
    await NativeAudio.preload({ assetId: 'stop-test', assetPath: url, isUrl: true });

    const states: string[] = [];
    const handle = await NativeAudio.addListener('playbackState', ({ state }) => {
      states.push(state);
    });

    await NativeAudio.play({ assetId: 'stop-test', time: 0 });
    await NativeAudio.stop({ assetId: 'stop-test' });

    expect(states.some((state) => state === 'stopped')).toBe(true);
    await handle.remove();
  }, 10000);

  it('clearQueue_resetsQueueAndQueueNextRejects', async () => {
    const url = blobAudioUrl();
    await NativeAudio.setQueue({
      items: [{ assetId: 'c1', assetPath: url, isUrl: true }],
      autoPlay: false,
    });

    await NativeAudio.clearQueue();
    const state = await NativeAudio.getQueueState();
    expect(state.count).toBe(0);
    await expect(NativeAudio.queueNext()).rejects.toBeDefined();
  }, 10000);

  it('queueNext_atBoundaryRejectsWithoutUnhandledRejection', async () => {
    const url = blobAudioUrl();
    await NativeAudio.setQueue({
      items: [{ assetId: 'only', assetPath: url, isUrl: true }],
      autoPlay: false,
    });

    const unhandled: unknown[] = [];
    const onUnhandled = (reason: PromiseRejectionEvent) => unhandled.push(reason.reason);
    window.addEventListener('unhandledrejection', onUnhandled);

    await expect(NativeAudio.queueNext()).rejects.toBeDefined();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    window.removeEventListener('unhandledrejection', onUnhandled);
    expect(unhandled).toHaveLength(0);
  }, 10000);
});
