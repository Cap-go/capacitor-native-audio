# feat: add playOnce method for fire-and-forget audio playback with automatic cleanup

## Overview
This PR introduces a new `playOnce()` method that provides a simplified API for one-time audio playback with automatic resource cleanup. This is ideal for sound effects, notifications, and other fire-and-forget audio scenarios where manual asset lifecycle management is unnecessary.

## Key Features

### 🎯 Core Functionality
- **Automatic Asset Management**: Generates unique temporary asset IDs and handles full lifecycle
- **Auto-cleanup**: Automatically unloads assets and clears metadata after playback completes
- **Error Handling**: Cleanup occurs even if playback fails or is interrupted
- **Cross-platform**: Consistent API across iOS, Android, and Web

### 🔧 Advanced Options
- `autoPlay` (default: `true`) - Start playback immediately or defer to manual control
- `deleteAfterPlay` (default: `false`) - Automatically delete local audio files after playback
- `notificationMetadata` - Display Now Playing info with title, artist, album, artwork
- `volume`, `headers`, `isUrl` - Full control over playback and network requests

### 🛡️ Safety & Reliability
- **Thread-safe operations**: All shared state access protected by dispatch queues (iOS) / UI thread (Android)
- **Safe file deletion**: Canonical path validation restricts deletion to app sandbox directories only
- **Memory leak prevention**: Comprehensive cleanup on all error paths
- **HLS support**: Explicit availability checking with user-friendly error messages

## API Usage

### Basic Usage
```typescript
// Simple one-shot playback
await NativeAudio.playOnce({
  assetPath: 'audio/notification.mp3',
});
```

### Advanced Usage
```typescript
// Full-featured with metadata and file deletion
const { assetId } = await NativeAudio.playOnce({
  assetPath: 'file:///path/to/temp-audio.wav',
  volume: 0.8,
  autoPlay: true,
  deleteAfterPlay: true,
  notificationMetadata: {
    title: 'Song Name',
    artist: 'Artist Name',
    album: 'Album Name',
    artworkUrl: 'https://example.com/artwork.jpg'
  }
});

// Optional: manually control if autoPlay: false
await NativeAudio.play({ assetId });
```

## Implementation Details

### iOS (Swift)
- Uses existing `AudioAsset` and `RemoteAudioAsset` infrastructure
- Thread-safe via `audioQueue` (concurrent queue with barrier writes)
- File deletion validated with `isDeletableFile()` helper (symlink resolution, allowlist checking)
- Cleanup triggered via `onComplete` callback
- **Files changed**: `Plugin.swift` (+180 lines), `PluginTests.swift` (+330 lines)

### Android (Java)
- Leverages refactored `loadAudioAsset()` helper (no synthetic PluginCall)
- UI thread safety with proper synchronization
- File deletion with 4-level sandbox validation (cache/files/externalCache/externalFiles)
- Completion listener wraps original dispatcher + cleanup logic
- **Files changed**: `NativeAudio.java` (+150 lines)

### Web (TypeScript)
- Uses `HTMLAudioElement` with `ended`/`error` event cleanup
- Warning for unsupported `deleteAfterPlay` (browser security limitation)
- Shared type definitions (`PlayOnceOptions`, `PlayOnceResult`)
- **Files changed**: `web.ts` (+80 lines), `definitions.ts` (+30 lines)

## Testing

### iOS Test Coverage (7 comprehensive tests)
- ✅ Auto-play behavior verification
- ✅ Non-auto-play with `isPlaying` check
- ✅ Cleanup after completion (asset + metadata removal)
- ✅ File deletion with safety validation
- ✅ Notification metadata storage
- ✅ Error handling and cleanup on failure
- ✅ Unique asset ID generation and tracking

### Manual Testing
- Tested on iOS 14+, Android API 21+, modern browsers
- Verified with local files, remote URLs, HLS streams
- Validated file deletion safety (rejects system paths, follows symlinks)
- Confirmed notification display on both platforms

## Code Quality

### Addressed Review Feedback
- ✅ Thread safety: `notificationMetadataMap` access unified on `audioQueue`
- ✅ Memory leaks: `cleanupOnFailure()` applied to all 7 error paths
- ✅ Code duplication: Extracted `loadAudioAsset()` helper (-89 lines net)
- ✅ File deletion hardening: Canonical path validation against allowlist
- ✅ Type coupling: Shared `PlayOnceOptions`/`PlayOnceResult` across platforms
- ✅ Test assertions: Capture assetId from callbacks, verify internal consistency
- ✅ Edge cases: Manual stop/unload cleanup for playOnce assets

### Documentation
- ✅ Comprehensive Swift doc comments (parameters, returns, throws)
- ✅ JavaDoc for all new public and private methods
- ✅ README with usage examples and API reference
- ✅ Code review response document (`.github/REVIEW_RESPONSE.md`)

## Breaking Changes
**None** - This is a purely additive feature. All existing APIs remain unchanged.

## Migration Guide
No migration needed. This is a new convenience method that complements existing `preload()`/`play()`/`unload()` APIs.

## Checklist
- [x] iOS implementation with comprehensive tests
- [x] Android implementation with safety checks
- [x] Web implementation with platform limitations documented
- [x] TypeScript definitions and exports
- [x] README documentation and examples
- [x] All review comments addressed
- [x] Builds passing on all platforms
- [x] No breaking changes

## Related Issues/PRs
- Addresses need for simplified audio playback API
- Complements existing asset lifecycle methods
- Foundation for future audio queue/playlist features

---

**Version**: 7.11.0  
**Platforms**: iOS 14+, Android API 21+, Web (modern browsers)
