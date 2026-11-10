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

`backgroundPlayback: true` skips the plugin pause or resume logic when the app backgrounds. On Android it does **not** start a foreground service. You still need manifest permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`) and your own media foreground service before relying on background playback. See README Notification Center Display.

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
