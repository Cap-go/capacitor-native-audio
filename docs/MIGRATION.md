# Migration notes (8.5 queue and playback helpers)

All changes in 8.5 are **additive**. Existing `preload`, `play`, and event names stay the same.

## New APIs

- `setQueue`, `queueNext`, `queuePrevious`, `queueSelect`, `getQueueState`, `clearQueue`
- `getPlaybackState` snapshot
- `getRate`
- `progress` listener (`configure({ progressInterval })`)
- `queueChange` listener
- `setSpeaker`, `setEarpiece`, `getAudioRoute`, `audioRouteChange` (native only)

## Web

- `configure()` now accepts `progressInterval` on web.
- `playbackState` events are emitted on web during play, pause, and complete.
- Speaker and earpiece routing APIs resolve on web but always report `speaker`.

## Lock screen

Enable `configure({ showNotification: true })` and pass `notificationMetadata` on preload or queue items for artwork and titles.
