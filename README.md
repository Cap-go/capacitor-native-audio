# Native audio

 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin"> ➡️ Get Instant updates for your App with Capgo 🚀</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin"> Fix your annoying bug now, Hire a Capacitor expert 💪</a></h2>
</div>

<h3 align="center">Native Audio</h3>
<p align="center">
  <strong>
    <code>@capgo/native-audio</code>
  </strong>
</p>
<p align="center">Capacitor plugin for playing sounds.</p>

<p align="center">
  <img src="https://img.shields.io/maintenance/yes/2023?style=flat-square" />
  <a href="https://github.com/capgo/native-audio/actions?query=workflow%3A%22Test+and+Build+Plugin%22"><img src="https://img.shields.io/github/workflow/status/@capgo/native-audio/Test%20and%20Build%20Plugin?style=flat-square" /></a>
  <a href="https://www.npmjs.com/package/capgo/native-audio"><img src="https://img.shields.io/npm/l/@capgo/native-audio?style=flat-square" /></a>
<br>
  <a href="https://www.npmjs.com/package/@capgo/native-audio"><img src="https://img.shields.io/npm/dw/@capgo/native-audio?style=flat-square" /></a>
  <a href="https://www.npmjs.com/package/@capgo/native-audio"><img src="https://img.shields.io/npm/v/@capgo/native-audio?style=flat-square" /></a>
<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
<a href="#contributors-"><img src="https://img.shields.io/badge/all%20contributors-6-orange?style=flat-square" /></a>
<!-- ALL-CONTRIBUTORS-BADGE:END -->
</p>

# Capacitor Native Audio Plugin

Capacitor plugin for native audio engine.
Capacitor V7 - ✅ Support!

Support local file, remote URL, and m3u8 stream

Click on video to see example 💥

[![YouTube Example](https://img.youtube.com/vi/XpUGlWWtwHs/0.jpg)](https://www.youtube.com/watch?v=XpUGlWWtwHs)

## Maintainers

| Maintainer      | GitHub                              | Social                                  |
| --------------- | ----------------------------------- | --------------------------------------- |
| Martin Donadieu | [riderx](https://github.com/riderx) | [Telegram](https://t.me/martindonadieu) |

Mainteinance Status: Actively Maintained

## Preparation

All audio files must be with the rest of your source files.

First make your sound file end up in your builded code folder, example in folder `BUILDFOLDER/assets/sounds/FILENAME.mp3`
Then use it in preload like that `assets/sounds/FILENAME.mp3`

## Installation

To use npm

```bash
npm install @capgo/native-audio
```

To use yarn

```bash
yarn add @capgo/native-audio
```

Sync native files

```bash
npx cap sync
```

On iOS, Android and Web, no further steps are needed.

## Configuration

No configuration required for this plugin.
<docgen-config>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->



</docgen-config>

## Supported methods

| Name           | Android | iOS | Web |
|:---------------| :------ | :-- | :-- |
| configure      | ✅      | ✅  | ❌  |
| preload        | ✅      | ✅  | ✅  |
| play           | ✅      | ✅  | ✅  |
| pause          | ✅      | ✅  | ✅  |
| resume         | ✅      | ✅  | ✅  |
| loop           | ✅      | ✅  | ✅  |
| stop           | ✅      | ✅  | ✅  |
| unload         | ✅      | ✅  | ✅  |
| setVolume      | ✅      | ✅  | ✅  |
| getDuration    | ✅      | ✅  | ✅  |
| setCurrentTime | ✅      | ✅  | ✅  |
| getCurrentTime | ✅      | ✅  | ✅  |
| isPlaying      | ✅      | ✅  | ✅  |

## Usage

[Example repository](https://github.com/bazuka5801/native-audio-example)

```typescript
import {NativeAudio} from '@capgo/native-audio'


/**
 * This method will load more optimized audio files for background into memory.
 * @param assetPath - relative path of the file, absolute url (file://) or remote url (https://)
 *        assetId - unique identifier of the file
 *        audioChannelNum - number of audio channels
 *        isUrl - pass true if assetPath is a `file://` url
 * @returns void
 */
NativeAudio.preload({
    assetId: "fire",
    assetPath: "assets/sounds/fire.mp3",
    audioChannelNum: 1,
    isUrl: false
});

/**
 * This method will play the loaded audio file if present in the memory.
 * @param assetId - identifier of the asset
 * @param time - (optional) play with seek. example: 6.0 - start playing track from 6 sec
 * @param delay - (optional) delay the audio. default is 0s
 * @param fadeIn - (optional) whether fade in the audio. default is false
 * @param fadeOut - (optional) whether fade out the audio. default is false
 * @param fadeInDuration - (optional) fade in duration in seconds. only used if fadeIn is true. default is 1s
 * @param fadeOutDuration - (optional) fade out duration in seconds. only used if fadeOut is true. default is 1s
 * @param fadeOutStartTime - (optional) time in seconds from the start of the audio to start fading out. only used if fadeOut is true. default is fadeOutDuration before end of audio.
 * @returns void
 */
NativeAudio.play({
    assetId: 'fire',
    // time: 6.0 - seek time
    // volume: 0.4,
    // delay: 1.0,
    // fadeIn: true,
    // fadeOut: true,
    // fadeInDuration: 2,
    // fadeOutDuration: 2
    // fadeOutStartTime: 2
});

/**
 * This method will loop the audio file for playback.
 * @param assetId - identifier of the asset
 * @returns void
 */
NativeAudio.loop({
  assetId: 'fire',
});


/**
 * This method will stop the audio file if it's currently playing.
 * @param assetId - identifier of the asset
 * @param fadeOut - (optional) whether fade out the audio before stopping. default is false
 * @param fadeOutDuration - (optional) fade out duration in seconds. default is 1s
 * @returns void
 */
NativeAudio.stop({
  assetId: 'fire',
  // fadeOut: true,
  // fadeOutDuration: 2
});

/**
 * This method will unload the audio file from the memory.
 * @param assetId - identifier of the asset
 * @returns void
 */
NativeAudio.unload({
  assetId: 'fire',
});

/**
 * This method will set the new volume for a audio file.
 * @param assetId - identifier of the asset
 *        volume - numerical value of the volume between 0.1 - 1.0 default 1.0
 *        duration - time over which to fade to the target volume, in seconds. default is 0s (immediate)
 * @returns void
 */
NativeAudio.setVolume({
  assetId: 'fire',
  volume: 0.4,
  // duration: 2
});

/**
 * this method will get the duration of an audio file.
 * only works if channels == 1
 */
NativeAudio.getDuration({
  assetId: 'fire'
})
.then(result => {
  console.log(result.duration);
})

/**
 * this method will get the current time of a playing audio file.
 * only works if channels == 1
 */
NativeAudio.getCurrentTime({
  assetId: 'fire'
})
.then(result => {
  console.log(result.currentTime);
})

/**
 * this method will set the current time of a playing audio file.
 * @param assetId - identifier of the asset
*  time - time to set the audio, in seconds
 */
NativeAudio.setCurrentTime({
  assetId: 'fire',
  time: 6.0
})

/**
 * This method will return false if audio is paused or not loaded.
 * @param assetId - identifier of the asset
 * @returns {isPlaying: boolean}
 */
NativeAudio.isPlaying({
  assetId: 'fire'
})
.then(result => {
  console.log(result.isPlaying);
})
```

## API

<docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### configure(...)

```typescript
configure(options: ConfigureOptions) => Promise<void>
```

Configure the audio player

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#configureoptions">ConfigureOptions</a></code> |

**Since:** 5.0.0

--------------------


### preload(...)

```typescript
preload(options: PreloadOptions) => Promise<void>
```

Load an audio file

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#preloadoptions">PreloadOptions</a></code> |

**Since:** 5.0.0

--------------------


### isPreloaded(...)

```typescript
isPreloaded(options: PreloadOptions) => Promise<{ found: boolean; }>
```

Check if an audio file is preloaded

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#preloadoptions">PreloadOptions</a></code> |

**Returns:** <code>Promise&lt;{ found: boolean; }&gt;</code>

**Since:** 6.1.0

--------------------


### play(...)

```typescript
play(options: AssetPlayOptions) => Promise<void>
```

Play an audio file

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#assetplayoptions">AssetPlayOptions</a></code> |

**Since:** 5.0.0

--------------------


### pause(...)

```typescript
pause(options: AssetPauseOptions) => Promise<void>
```

Pause an audio file

| Param         | Type                                                            |
| ------------- | --------------------------------------------------------------- |
| **`options`** | <code><a href="#assetpauseoptions">AssetPauseOptions</a></code> |

**Since:** 5.0.0

--------------------


### resume(...)

```typescript
resume(options: AssetResumeOptions) => Promise<void>
```

Resume an audio file

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#assetresumeoptions">AssetResumeOptions</a></code> |

**Since:** 5.0.0

--------------------


### loop(...)

```typescript
loop(options: Assets) => Promise<void>
```

Stop an audio file

| Param         | Type                                      |
| ------------- | ----------------------------------------- |
| **`options`** | <code><a href="#assets">Assets</a></code> |

**Since:** 5.0.0

--------------------


### stop(...)

```typescript
stop(options: AssetStopOptions) => Promise<void>
```

Stop an audio file

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#assetstopoptions">AssetStopOptions</a></code> |

**Since:** 5.0.0

--------------------


### unload(...)

```typescript
unload(options: Assets) => Promise<void>
```

Unload an audio file

| Param         | Type                                      |
| ------------- | ----------------------------------------- |
| **`options`** | <code><a href="#assets">Assets</a></code> |

**Since:** 5.0.0

--------------------


### setVolume(...)

```typescript
setVolume(options: AssetVolume) => Promise<void>
```

Set the volume of an audio file

| Param         | Type                                                |
| ------------- | --------------------------------------------------- |
| **`options`** | <code><a href="#assetvolume">AssetVolume</a></code> |

**Since:** 5.0.0

--------------------


### setRate(...)

```typescript
setRate(options: AssetRate) => Promise<void>
```

Set the rate of an audio file

| Param         | Type                                            |
| ------------- | ----------------------------------------------- |
| **`options`** | <code><a href="#assetrate">AssetRate</a></code> |

**Since:** 5.0.0

--------------------


### setCurrentTime(...)

```typescript
setCurrentTime(options: AssetSetTime) => Promise<void>
```

Set the current time of an audio file

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#assetsettime">AssetSetTime</a></code> |

**Since:** 6.5.0

--------------------


### getCurrentTime(...)

```typescript
getCurrentTime(options: Assets) => Promise<{ currentTime: number; }>
```

Get the current time of an audio file

| Param         | Type                                      |
| ------------- | ----------------------------------------- |
| **`options`** | <code><a href="#assets">Assets</a></code> |

**Returns:** <code>Promise&lt;{ currentTime: number; }&gt;</code>

**Since:** 5.0.0

--------------------


### getDuration(...)

```typescript
getDuration(options: Assets) => Promise<{ duration: number; }>
```

Get the duration of an audio file in seconds

| Param         | Type                                      |
| ------------- | ----------------------------------------- |
| **`options`** | <code><a href="#assets">Assets</a></code> |

**Returns:** <code>Promise&lt;{ duration: number; }&gt;</code>

**Since:** 5.0.0

--------------------


### isPlaying(...)

```typescript
isPlaying(options: Assets) => Promise<{ isPlaying: boolean; }>
```

Check if an audio file is playing

| Param         | Type                                      |
| ------------- | ----------------------------------------- |
| **`options`** | <code><a href="#assets">Assets</a></code> |

**Returns:** <code>Promise&lt;{ isPlaying: boolean; }&gt;</code>

**Since:** 5.0.0

--------------------


### addListener('complete', ...)

```typescript
addListener(eventName: 'complete', listenerFunc: CompletedListener) => Promise<PluginListenerHandle>
```

Listen for complete event

| Param              | Type                                                            |
| ------------------ | --------------------------------------------------------------- |
| **`eventName`**    | <code>'complete'</code>                                         |
| **`listenerFunc`** | <code><a href="#completedlistener">CompletedListener</a></code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 5.0.0
return {@link CompletedEvent}

--------------------


### addListener('currentTime', ...)

```typescript
addListener(eventName: 'currentTime', listenerFunc: CurrentTimeListener) => Promise<PluginListenerHandle>
```

Listen for current time updates
Emits every 100ms while audio is playing

| Param              | Type                                                                |
| ------------------ | ------------------------------------------------------------------- |
| **`eventName`**    | <code>'currentTime'</code>                                          |
| **`listenerFunc`** | <code><a href="#currenttimelistener">CurrentTimeListener</a></code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 6.5.0
return {@link CurrentTimeEvent}

--------------------


### clearCache()

```typescript
clearCache() => Promise<void>
```

Clear the audio cache for remote audio files

**Since:** 6.5.0

--------------------


### setDebugMode(...)

```typescript
setDebugMode(options: { enabled: boolean; }) => Promise<void>
```

Set the debug mode

| Param         | Type                               | Description                               |
| ------------- | ---------------------------------- | ----------------------------------------- |
| **`options`** | <code>{ enabled: boolean; }</code> | - Options to enable or disable debug mode |

**Since:** 6.5.0

--------------------


### Interfaces


#### ConfigureOptions

| Prop               | Type                 | Description                                                                   |
| ------------------ | -------------------- | ----------------------------------------------------------------------------- |
| **`focus`**        | <code>boolean</code> | focus the audio with Audio Focus                                              |
| **`background`**   | <code>boolean</code> | Play the audio in the background                                              |
| **`ignoreSilent`** | <code>boolean</code> | Ignore silent mode, works only on iOS setting this will nuke other audio apps |


#### PreloadOptions

| Prop                  | Type                 | Description                                                                                                                                                                           |
| --------------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`assetPath`**       | <code>string</code>  | Path to the audio file, relative path of the file, absolute url (file://) or remote url (https://) Supported formats: - MP3, WAV (all platforms) - M3U8/HLS streams (iOS and Android) |
| **`assetId`**         | <code>string</code>  | Asset Id, unique identifier of the file                                                                                                                                               |
| **`volume`**          | <code>number</code>  | Volume of the audio, between 0.1 and 1.0                                                                                                                                              |
| **`audioChannelNum`** | <code>number</code>  | Audio channel number, default is 1                                                                                                                                                    |
| **`isUrl`**           | <code>boolean</code> | Is the audio file a URL, pass true if assetPath is a `file://` url or a streaming URL (m3u8)                                                                                          |


#### AssetPlayOptions

| Prop                   | Type                 | Description                                                                                                                                    |
| ---------------------- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| **`assetId`**          | <code>string</code>  | Asset Id, unique identifier of the file                                                                                                        |
| **`time`**             | <code>number</code>  | Time to start playing the audio, in seconds                                                                                                    |
| **`delay`**            | <code>number</code>  | Delay to start playing the audio, in seconds                                                                                                   |
| **`volume`**           | <code>number</code>  | Volume of the audio, between 0.1 and 1.0                                                                                                       |
| **`fadeIn`**           | <code>boolean</code> | Whether to fade in the audio                                                                                                                   |
| **`fadeOut`**          | <code>boolean</code> | Whether to fade out the audio                                                                                                                  |
| **`fadeInDuration`**   | <code>number</code>  | Fade in duration in seconds. Only used if fadeIn is true. Default is 1s.                                                                       |
| **`fadeOutDuration`**  | <code>number</code>  | Fade out duration in seconds. Only used if fadeOut is true. Default is 1s.                                                                     |
| **`fadeOutStartTime`** | <code>number</code>  | Time in seconds from the start of the audio to start fading out. Only used if fadeOut is true. Default is fadeOutDuration before end of audio. |


#### AssetPauseOptions

| Prop                  | Type                 | Description                                  |
| --------------------- | -------------------- | -------------------------------------------- |
| **`assetId`**         | <code>string</code>  | Asset Id, unique identifier of the file      |
| **`fadeOut`**         | <code>boolean</code> | Whether to fade out the audio before pausing |
| **`fadeOutDuration`** | <code>number</code>  | Fade out duration in seconds. Default is 1s. |


#### AssetResumeOptions

| Prop                 | Type                 | Description                                 |
| -------------------- | -------------------- | ------------------------------------------- |
| **`assetId`**        | <code>string</code>  | Asset Id, unique identifier of the file     |
| **`fadeIn`**         | <code>boolean</code> | Whether to fade in the audio during resume  |
| **`fadeInDuration`** | <code>number</code>  | Fade in duration in seconds. Default is 1s. |


#### Assets

| Prop          | Type                | Description                             |
| ------------- | ------------------- | --------------------------------------- |
| **`assetId`** | <code>string</code> | Asset Id, unique identifier of the file |


#### AssetStopOptions

| Prop                  | Type                 | Description                                   |
| --------------------- | -------------------- | --------------------------------------------- |
| **`assetId`**         | <code>string</code>  | Asset Id, unique identifier of the file       |
| **`fadeOut`**         | <code>boolean</code> | Whether to fade out the audio before stopping |
| **`fadeOutDuration`** | <code>number</code>  | Fade out duration in seconds. Default is 1s.  |


#### AssetVolume

| Prop           | Type                | Description                                                                          |
| -------------- | ------------------- | ------------------------------------------------------------------------------------ |
| **`assetId`**  | <code>string</code> | Asset Id, unique identifier of the file                                              |
| **`volume`**   | <code>number</code> | Volume of the audio, between 0.1 and 1.0                                             |
| **`duration`** | <code>number</code> | Time over which to fade to the target volume, in seconds. Default is 0s (immediate). |


#### AssetRate

| Prop          | Type                | Description                             |
| ------------- | ------------------- | --------------------------------------- |
| **`assetId`** | <code>string</code> | Asset Id, unique identifier of the file |
| **`rate`**    | <code>number</code> | Rate of the audio, between 0.1 and 1.0  |


#### AssetSetTime

| Prop          | Type                | Description                             |
| ------------- | ------------------- | --------------------------------------- |
| **`assetId`** | <code>string</code> | Asset Id, unique identifier of the file |
| **`time`**    | <code>number</code> | Time to set the audio, in seconds       |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### CompletedEvent

| Prop          | Type                | Description                | Since |
| ------------- | ------------------- | -------------------------- | ----- |
| **`assetId`** | <code>string</code> | Emit when a play completes | 5.0.0 |


#### CurrentTimeEvent

| Prop              | Type                | Description                          | Since |
| ----------------- | ------------------- | ------------------------------------ | ----- |
| **`currentTime`** | <code>number</code> | Current time of the audio in seconds | 6.5.0 |
| **`assetId`**     | <code>string</code> | Asset Id of the audio                | 6.5.0 |


### Type Aliases


#### CompletedListener

<code>(state: <a href="#completedevent">CompletedEvent</a>): void</code>


#### CurrentTimeListener

<code>(state: <a href="#currenttimeevent">CurrentTimeEvent</a>): void</code>

</docgen-api>

## Development and Testing

### Building

```bash
npm run build
```

### Testing

This plugin includes a comprehensive test suite for iOS:

1. Open the iOS project in Xcode: `npx cap open ios`
2. Navigate to the `PluginTests` directory
3. Run tests using Product > Test (⌘+U)

The tests cover core functionality including audio asset initialization, playback, volume control, fade effects, and more. See the [test documentation](ios/PluginTests/README.md) for more details.
