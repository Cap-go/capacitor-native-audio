# Code Review Response

## Actionable Comments - Status: ✅ All Addressed

### Android Implementation (Lines 53-57, 90-91, 264-421)

#### Concern 1: PluginCall callbackId Reuse
**Reviewer's Concern:**
> Reusing PluginCall callbackId via preloadCall. Because preloadCall is constructed with call.getCallbackId(), this can resolve or reject the same Capacitor callback twice...

**Status: ✅ Not Applicable - Already Implemented Correctly**

**Current Implementation:**
The `playOnce` method does NOT create synthetic PluginCall instances or reuse callbackIds. Instead, it uses the `loadAudioAsset()` helper method directly:

```java
// Load the asset using the helper method
JSObject headersObj = call.getObject("headers");
AudioAsset asset = plugin.loadAudioAsset(assetId, assetPath, isLocalUrl, volume, audioChannelNum, headersObj);

if (asset == null) {
    throw new Exception("Failed to load asset");
}
```

The `loadAudioAsset()` method (lines 889-973) is a pure helper that:
- Takes primitive parameters (String, boolean, float, JSObject)
- Returns an AudioAsset or throws an Exception
- Never interacts with PluginCall resolution/rejection
- Is shared between `playOnce()` and `preloadAsset()`

This is exactly the pattern the reviewer suggested. No double-resolution is possible because there's only one `call.resolve()` at the end of `playOnce()`.

**Verification:**
```bash
$ grep -n "new PluginCall\|preloadCall\|call.getCallbackId" android/src/main/java/ee/forgr/audio/NativeAudio.java
# No results - confirms no synthetic PluginCall creation
```

---

#### Concern 2: deleteAfterPlay File Deletion Safety
**Reviewer's Concern:**
> deleteAfterPlay can delete arbitrary file paths... Since filePathToDelete ultimately comes from JS, this allows deletion attempts on any path the app passes in.

**Status: ✅ Already Implemented - Comprehensive Safety Checks**

**Current Implementation (Lines 395-470):**

1. **File:// Scheme Validation** (Line 364):
   ```java
   if (deleteAfterPlay && isLocalUrl && assetPath.startsWith("file://")) {
       filePathToDelete = assetPath;
   }
   ```
   Only `file://` URLs are marked for deletion. Remote URLs are excluded.

2. **URI Parsing with Fallback** (Lines 407-418):
   ```java
   try {
       fileToDelete = new File(URI.create(filePathToDelete));
   } catch (IllegalArgumentException e) {
       Log.d(TAG, "Invalid URI format, using raw path: " + filePathToDelete);
       fileToDelete = new File(filePathToDelete);
   }
   ```

3. **Canonical Path Resolution** (Lines 420-437):
   ```java
   String canonicalPath = fileToDelete.getCanonicalPath();
   String cacheDir = plugin.getContext().getCacheDir().getCanonicalPath();
   String filesDir = plugin.getContext().getFilesDir().getCanonicalPath();
   String externalCacheDir = plugin.getContext().getExternalCacheDir() != null
       ? plugin.getContext().getExternalCacheDir().getCanonicalPath()
       : null;
   String externalFilesDir = plugin.getContext().getExternalFilesDir(null) != null
       ? plugin.getContext().getExternalFilesDir(null).getCanonicalPath()
       : null;
   ```

4. **Safe Directory Validation** (Lines 440-450):
   ```java
   boolean isSafe =
       canonicalPath.startsWith(cacheDir) ||
       canonicalPath.startsWith(filesDir) ||
       (externalCacheDir != null && canonicalPath.startsWith(externalCacheDir)) ||
       (externalFilesDir != null && canonicalPath.startsWith(externalFilesDir));

   if (!isSafe) {
       Log.w(TAG, "Skipping file deletion: path outside safe directories - " + canonicalPath);
       return;
   }
   ```

5. **Directory Prevention Check** (Lines 453-459):
   ```java
   if (fileToDelete.isDirectory()) {
       Log.w(TAG, "Skipping file deletion: path is a directory - " + canonicalPath);
       return;
   }
   ```

6. **Full Exception Logging** (Lines 467-474):
   ```java
   } catch (Exception e) {
       Log.e(TAG, "Error deleting file after playOnce: " + filePathToDelete, e);
   }
   ```

**Implementation exactly matches the reviewer's recommendations** and includes even more safety measures (directory check, full stack trace logging).

---

### Web Implementation (src/web.ts, Line 10, 83-151)

#### Nitpick: Type Coupling
**Reviewer's Suggestion:**
> Consider reusing shared option/result types... change the inline options / return type here to the exported PlayOnceOptions / PlayOnceResult from src/definitions.ts

**Status: ✅ Fixed (Commit 7d8665c)**

**Change:**
```typescript
// Before:
async playOnce(options: {
  assetPath: string;
  volume?: number;
  isUrl?: boolean;
  autoPlay?: boolean;
  deleteAfterPlay?: boolean;
}): Promise<{ assetId: string }> {

// After:
async playOnce(options: PlayOnceOptions): Promise<PlayOnceResult> {
```

**Import Added:**
```typescript
import type { ConfigureOptions, PlayOnceOptions, PlayOnceResult, PreloadOptions } from './definitions';
```

**Benefit:** Ensures automatic type synchronization across platforms when the interface changes.

---

## Summary

| Concern | Type | Status | Action |
|---------|------|--------|--------|
| Android PluginCall reuse | Actionable | ✅ N/A | Already using helper method pattern |
| Android file deletion safety | Actionable | ✅ Complete | Comprehensive validation implemented |
| Web type coupling | Nitpick | ✅ Fixed | Using shared types (commit 7d8665c) |

**All code review concerns have been addressed.** The implementation follows best practices for security, type safety, and maintainability.
