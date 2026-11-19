# Playlist queue

```typescript
import { NativeAudio } from '@capgo/capacitor-native-audio';

// Web: only progressInterval is supported in configure().
await NativeAudio.configure({ progressInterval: 0.5 });

// iOS and Android: notification and background options (see README Notification Center section).
await NativeAudio.configure({
  showNotification: true,
  backgroundPlayback: true,
  progressInterval: 0.5,
});
```

`backgroundPlayback: true` skips the plugin pause or resume logic when the app backgrounds. On Android, together with `showNotification: true`, it starts the plugin media foreground service. The app manifest still needs `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `WAKE_LOCK`. See README Android Background Playback.

```typescript
await NativeAudio.setRepeatMode({ mode: 'all' });
await NativeAudio.queueAdd({
  items: [{ assetId: 'ep-3', assetPath: 'https://example.com/ep3.mp3', isUrl: true }],
});
await NativeAudio.queueRemove({ assetId: 'ep-1' });
```

`mode` is `none`, `one`, or `all`. `none` stops at either end of the queue. `one` replays the current item. `all` wraps next and previous, and starts the next item when the current one finishes.

On web, `preload` and `playOnce` accept a `blob` instead of `assetPath`.

On iOS, `stop({ assetId, deactivateAudioSession: false })` leaves the audio session active so the next `play()` does not have to reactivate it.

```typescript
await NativeAudio.addListener('queueChange', ({ assetId, index, reason }) => {
  console.log('queue', assetId, index, reason);
});

await NativeAudio.addListener('progress', ({ assetId, position, duration }) => {
  console.log(assetId, position, duration);
});

await NativeAudio.setQueue({
  items: [
    {
      assetId: 'ep-1',
      assetPath: 'https://example.com/ep1.mp3',
      isUrl: true,
      notificationMetadata: { title: 'Episode 1', artist: 'Show', artworkUrl: 'https://example.com/cover.png' },
    },
    {
      assetId: 'ep-2',
      assetPath: 'https://example.com/ep2.mp3',
      isUrl: true,
      notificationMetadata: { title: 'Episode 2', artist: 'Show', artworkUrl: 'https://example.com/cover.png' },
    },
  ],
  autoPlay: true,
});

// On complete, advance manually (same as preload/play model)
await NativeAudio.addListener('complete', async ({ assetId }) => {
  const { assetId: selectedId, index, count } = await NativeAudio.getQueueState();
  if (assetId !== selectedId) {
    return;
  }
  if (index + 1 < count) {
    await NativeAudio.queueNext();
  }
});

await NativeAudio.setSpeaker();
const { route } = await NativeAudio.getAudioRoute();
console.log(route);

const snapshot = await NativeAudio.getPlaybackState();
console.log(snapshot);
```
