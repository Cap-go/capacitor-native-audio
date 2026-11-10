# Playlist queue

```typescript
import { NativeAudio } from '@capgo/capacitor-native-audio';

await NativeAudio.configure({
  showNotification: true,
  backgroundPlayback: true,
  progressInterval: 0.5,
});

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
  const { index, count } = await NativeAudio.getQueueState();
  if (index + 1 < count) {
    await NativeAudio.queueNext();
    await NativeAudio.resume({ assetId: (await NativeAudio.getQueueState()).assetId });
  }
});

await NativeAudio.setSpeaker();
const { route } = await NativeAudio.getAudioRoute();
console.log(route);

const snapshot = await NativeAudio.getPlaybackState();
console.log(snapshot);
```
