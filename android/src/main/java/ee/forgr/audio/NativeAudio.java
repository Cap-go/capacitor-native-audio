package ee.forgr.audio;

import static ee.forgr.audio.Constant.ASSET_ID;
import static ee.forgr.audio.Constant.ASSET_PATH;
import static ee.forgr.audio.Constant.AUDIO_CHANNEL_NUM;
import static ee.forgr.audio.Constant.ERROR_ASSET_NOT_LOADED;
import static ee.forgr.audio.Constant.ERROR_ASSET_PATH_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ASSET_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_EXISTS;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ID_MISSING;
import static ee.forgr.audio.Constant.LOOP;
import static ee.forgr.audio.Constant.NOTIFICATION_METADATA;
import static ee.forgr.audio.Constant.OPT_FOCUS_AUDIO;
import static ee.forgr.audio.Constant.RATE;
import static ee.forgr.audio.Constant.SHOW_NOTIFICATION;
import static ee.forgr.audio.Constant.VOLUME;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.util.UnstableApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@UnstableApi
@CapacitorPlugin(
    permissions = {
        @Permission(strings = { Manifest.permission.MODIFY_AUDIO_SETTINGS }),
        @Permission(strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }),
        @Permission(strings = { Manifest.permission.READ_PHONE_STATE })
    }
)
public class NativeAudio extends Plugin implements AudioManager.OnAudioFocusChangeListener {

    private final String pluginVersion = "";

    public static final String TAG = "NativeAudio";

    private static HashMap<String, AudioAsset> audioAssetList = new HashMap<>();
    private static ArrayList<AudioAsset> resumeList;
    private AudioManager audioManager;
    private boolean fadeMusic = false;
    private boolean audioFocusRequested = false;
    private int originalAudioMode = AudioManager.MODE_INVALID;

    private final Map<String, PluginCall> pendingDurationCalls = new HashMap<>();

    // Notification center support
    private boolean showNotification = false;
    private Map<String, Map<String, String>> notificationMetadataMap = new HashMap<>();
    private MediaSessionCompat mediaSession;
    private String currentlyPlayingAssetId;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "native_audio_channel";

    // Track playOnce assets for automatic cleanup
    private Set<String> playOnceAssets = new HashSet<>();

    /**
     * Initializes plugin runtime state by obtaining the system AudioManager, preparing the asset map,
     * and recording the device's original audio mode without requesting audio focus.
     */
    @Override
    public void load() {
        super.load();

        this.audioManager = (AudioManager) this.getActivity().getSystemService(Context.AUDIO_SERVICE);

        audioAssetList = new HashMap<>();

        // Store the original audio mode but don't request focus yet
        if (this.audioManager != null) {
            originalAudioMode = this.audioManager.getMode();
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        try {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                // Pause playback - temporary loss
                for (AudioAsset audio : audioAssetList.values()) {
                    if (audio.isPlaying()) {
                        audio.pause();
                        resumeList.add(audio);
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Resume playback
                if (resumeList != null) {
                    while (!resumeList.isEmpty()) {
                        AudioAsset audio = resumeList.remove(0);
                        audio.resume();
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                // Stop playback - permanent loss
                for (AudioAsset audio : audioAssetList.values()) {
                    audio.stop();
                }
                audioManager.abandonAudioFocus(this);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error handling audio focus change", ex);
        }
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();

        try {
            if (audioAssetList != null) {
                for (HashMap.Entry<String, AudioAsset> entry : audioAssetList.entrySet()) {
                    AudioAsset audio = entry.getValue();

                    if (audio != null) {
                        boolean wasPlaying = audio.pause();

                        if (wasPlaying) {
                            resumeList.add(audio);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Exception caught while listening for handleOnPause: " + ex.getLocalizedMessage());
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();

        try {
            if (resumeList != null) {
                while (!resumeList.isEmpty()) {
                    AudioAsset audio = resumeList.remove(0);

                    if (audio != null) {
                        audio.resume();
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Exception caught while listening for handleOnResume: " + ex.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void configure(PluginCall call) {
        initSoundPool();

        if (this.audioManager == null) {
            call.resolve();
            return;
        }

        // Save original audio mode if not already saved
        if (originalAudioMode == AudioManager.MODE_INVALID) {
            originalAudioMode = this.audioManager.getMode();
        }

        boolean focus = call.getBoolean(OPT_FOCUS_AUDIO, false);
        boolean background = call.getBoolean("background", false);
        this.fadeMusic = call.getBoolean("fade", false);
        this.showNotification = call.getBoolean(SHOW_NOTIFICATION, false);

        try {
            if (focus) {
                // Request audio focus for playback with ducking
                int result = this.audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ); // Allow other audio to play quietly
                audioFocusRequested = true;
            } else if (audioFocusRequested) {
                this.audioManager.abandonAudioFocus(this);
                audioFocusRequested = false;
            }

            if (background) {
                // Set playback to continue in background
                this.audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                this.audioManager.setMode(AudioManager.MODE_NORMAL);
            }

            if (this.showNotification) {
                setupMediaSession();
                createNotificationChannel();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error configuring audio", ex);
        }

        call.resolve();
    }

    /**
     * Checks whether an audio asset identifier has been preloaded.
     *
     * Resolves the provided PluginCall with a JSObject containing the boolean property
     * `"found"` set to `true` when the given `assetId` is present in the plugin's preload map,
     * or `false` when it is not present. Rejects the call with `ERROR_AUDIO_ID_MISSING` when
     * the `assetId` parameter is missing or invalid.
     */
    @PluginMethod
    public void isPreloaded(final PluginCall call) {
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    initSoundPool();

                    String audioId = call.getString(ASSET_ID);

                    if (!isStringValid(audioId)) {
                        call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                        return;
                    }
                    call.resolve(new JSObject().put("found", audioAssetList.containsKey(audioId)));
                }
            }
        )
            .start();
    }

    /**
         * Begin preloading an audio asset specified by the given plugin call.
         *
         * <p>The call must include `assetId` and `assetPath`. It may also include `isUrl`, `isComplex`,
         * `headers`, `volume`, `audioChannelNum`, and optional notification metadata (title, artist,
         * album, artworkUrl). The preload operation is scheduled on the UI thread and the provided
         * PluginCall will be resolved on success or rejected with an error.
         *
         * @param call the PluginCall containing preload options and metadata for the asset
         */
    @PluginMethod
    public void preload(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    preloadAsset(call);
                }
            }
        );
    }

    /**
     * Play an audio asset a single time and automatically remove its resources when finished.
     *
     * <p>Preloads the specified asset, optionally starts playback immediately, and ensures the
     * asset is unloaded and any associated notification metadata are cleared after completion or on
     * error. Supports local file paths and remote URLs, HLS streams when available, custom HTTP
     * headers for remote requests, and optional deletion of local source files after playback.
     *
     * @param call Capacitor PluginCall containing options:
     *             - "assetPath" (required): path or URL to the audio file;
     *             - "volume" (optional): playback volume (0.1–1.0), default 1.0;
     *             - "isUrl" (optional): treat assetPath as a URL when true, default false;
     *             - "autoPlay" (optional): start playback immediately when true, default true;
     *             - "deleteAfterPlay" (optional): delete the local file after playback when true, default false;
     *             - "headers" (optional): JS object of HTTP headers for remote requests;
     *             - "notificationMetadata" (optional): object with "title", "artist", "album", "artworkUrl" for notification display.
     */
    @PluginMethod
    public void playOnce(final PluginCall call) {
        // Capture plugin reference for use in inner classes
        final NativeAudio plugin = this;

        this.getActivity().runOnUiThread(
            new Runnable() {
                /**
                 * Preloads a temporary audio asset, optionally plays it once, and schedules automatic cleanup when playback completes.
                 *
                 * <p>Generates a unique temporary `assetId`, validates options from the current PluginCall (asset path, volume,
                 * headers, notification metadata, autoPlay, deleteAfterPlay), loads the asset into the plugin's asset map, and
                 * registers a completion listener that unloads and removes the asset, clears related notification state, and
                 * optionally deletes the source file if it resides in an application-safe directory. If `autoPlay` is true and
                 * notifications are enabled, the media notification is updated to reflect playback.
                 *
                 * <p>On success the method resolves the PluginCall with an object containing the generated `assetId`. On failure the
                 * PluginCall is rejected with a descriptive error message.
                 */
                @Override
                public void run() {
                    try {
                        plugin.initSoundPool();

                        // Generate unique temporary asset ID
                        final String assetId =
                            "playOnce_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);

                        // Extract options
                        String assetPath = call.getString(ASSET_PATH);
                        if (!plugin.isStringValid(assetPath)) {
                            call.reject(ERROR_ASSET_PATH_MISSING);
                            return;
                        }

                        boolean autoPlay = call.getBoolean("autoPlay", true);
                        final boolean deleteAfterPlay = call.getBoolean("deleteAfterPlay", false);
                        float volume = call.getFloat(VOLUME, 1F);
                        boolean isLocalUrl = call.getBoolean("isUrl", false);
                        int audioChannelNum = 1; // Single channel for playOnce

                        // Track this as a playOnce asset
                        plugin.playOnceAssets.add(assetId);

                        // Store notification metadata if provided
                        JSObject metadata = call.getObject(NOTIFICATION_METADATA);
                        if (metadata != null) {
                            Map<String, String> metadataMap = new HashMap<>();
                            if (metadata.has("title")) metadataMap.put("title", metadata.getString("title"));
                            if (metadata.has("artist")) metadataMap.put("artist", metadata.getString("artist"));
                            if (metadata.has("album")) metadataMap.put("album", metadata.getString("album"));
                            if (metadata.has("artworkUrl")) metadataMap.put("artworkUrl", metadata.getString("artworkUrl"));
                            if (!metadataMap.isEmpty()) {
                                plugin.notificationMetadataMap.put(assetId, metadataMap);
                            }
                        }

                        // Preload the asset using the helper method
                        try {
                            // Check if asset already exists
                            if (plugin.audioAssetList.containsKey(assetId)) {
                                throw new Exception(ERROR_AUDIO_EXISTS + " - " + assetId);
                            }

                            // Load the asset using the helper method
                            JSObject headersObj = call.getObject("headers");
                            AudioAsset asset = plugin.loadAudioAsset(assetId, assetPath, isLocalUrl, volume, audioChannelNum, headersObj);

                            if (asset == null) {
                                throw new Exception("Failed to load asset");
                            }

                            // Add to asset list; completion listener is set below with cleanup
                            plugin.audioAssetList.put(assetId, asset);

                            // Store the file path if we need to delete it later
                            final String filePathToDelete;
                            if (deleteAfterPlay && isLocalUrl && assetPath.startsWith("file://")) {
                                filePathToDelete = assetPath;
                            } else {
                                filePathToDelete = null;
                            }

                            // Set up completion listener for automatic cleanup
                            asset.setCompletionListener(
                                new AudioCompletionListener() {
                                    /**
                                     * Handles completion for a play-once asset by dispatching the completion event and performing cleanup.
                                     *
                                     * Performs the following observable actions: unloads and removes the completed asset from internal maps,
                                     * removes play-once and notification metadata tracking, clears the active notification if the asset was
                                     * the currently playing one, and optionally deletes the asset file after verifying it resides in an
                                     * application-safe directory and is not a directory.
                                     *
                                     * @param completedAssetId the assetId of the asset that finished playback
                                     */
                                    @Override
                                    public void onCompletion(String completedAssetId) {
                                        // Call the original completion dispatcher first
                                        plugin.dispatchComplete(completedAssetId);

                                        // Then perform cleanup
                                        plugin
                                            .getActivity()
                                            .runOnUiThread(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            // Unload the asset
                                                            AudioAsset assetToUnload = plugin.audioAssetList.get(assetId);
                                                            if (assetToUnload != null) {
                                                                assetToUnload.unload();
                                                                plugin.audioAssetList.remove(assetId);
                                                            }

                                                            // Remove from tracking sets
                                                            plugin.playOnceAssets.remove(assetId);
                                                            plugin.notificationMetadataMap.remove(assetId);

                                                            // Clear notification if this was the currently playing asset
                                                            if (assetId.equals(plugin.currentlyPlayingAssetId)) {
                                                                plugin.clearNotification();
                                                                plugin.currentlyPlayingAssetId = null;
                                                            }

                                                            // Delete file if requested (with safety checks)
                                                            if (filePathToDelete != null) {
                                                                try {
                                                                    File fileToDelete;
                                                                    try {
                                                                        // Try to parse as URI first
                                                                        fileToDelete = new File(URI.create(filePathToDelete));
                                                                    } catch (IllegalArgumentException e) {
                                                                        // If URI parsing fails, treat as raw file path
                                                                        Log.d(
                                                                            TAG,
                                                                            "Invalid URI format, using raw path: " + filePathToDelete
                                                                        );
                                                                        fileToDelete = new File(filePathToDelete);
                                                                    }

                                                                    // Validate the file is within safe directories
                                                                    String canonicalPath = fileToDelete.getCanonicalPath();
                                                                    String cacheDir = plugin.getContext().getCacheDir().getCanonicalPath();
                                                                    String filesDir = plugin.getContext().getFilesDir().getCanonicalPath();
                                                                    String externalCacheDir = plugin.getContext().getExternalCacheDir() !=
                                                                        null
                                                                        ? plugin.getContext().getExternalCacheDir().getCanonicalPath()
                                                                        : null;
                                                                    String externalFilesDir = plugin
                                                                            .getContext()
                                                                            .getExternalFilesDir(null) !=
                                                                        null
                                                                        ? plugin.getContext().getExternalFilesDir(null).getCanonicalPath()
                                                                        : null;

                                                                    // Check if file is in a safe directory
                                                                    boolean isSafe =
                                                                        canonicalPath.startsWith(cacheDir) ||
                                                                        canonicalPath.startsWith(filesDir) ||
                                                                        (externalCacheDir != null &&
                                                                            canonicalPath.startsWith(externalCacheDir)) ||
                                                                        (externalFilesDir != null &&
                                                                            canonicalPath.startsWith(externalFilesDir));

                                                                    if (!isSafe) {
                                                                        Log.w(
                                                                            TAG,
                                                                            "Skipping file deletion: path outside safe directories - " +
                                                                                canonicalPath
                                                                        );
                                                                        return;
                                                                    }

                                                                    // Additional check: prevent deletion of directories
                                                                    if (fileToDelete.isDirectory()) {
                                                                        Log.w(
                                                                            TAG,
                                                                            "Skipping file deletion: path is a directory - " + canonicalPath
                                                                        );
                                                                        return;
                                                                    }

                                                                    if (fileToDelete.exists() && fileToDelete.delete()) {
                                                                        Log.d(TAG, "Deleted file after playOnce: " + filePathToDelete);
                                                                    } else {
                                                                        Log.w(
                                                                            TAG,
                                                                            "File does not exist or deletion failed: " + filePathToDelete
                                                                        );
                                                                    }
                                                                } catch (Exception e) {
                                                                    Log.e(
                                                                        TAG,
                                                                        "Error deleting file after playOnce: " + filePathToDelete,
                                                                        e
                                                                    );
                                                                }
                                                            }
                                                        } catch (Exception e) {
                                                            Log.e(TAG, "Error during playOnce cleanup: " + e.getMessage());
                                                        }
                                                    }
                                                }
                                            );
                                    }
                                }
                            );

                            // Auto-play if requested
                            if (autoPlay) {
                                asset.play(0.0);

                                // Update notification if enabled
                                if (showNotification) {
                                    currentlyPlayingAssetId = assetId;
                                    updateNotification(assetId);
                                }
                            }

                            // Return the generated assetId
                            JSObject result = new JSObject();
                            result.put(ASSET_ID, assetId);
                            call.resolve(result);
                        } catch (Exception ex) {
                            // Cleanup on failure
                            plugin.playOnceAssets.remove(assetId);
                            plugin.notificationMetadataMap.remove(assetId);
                            AudioAsset failedAsset = plugin.audioAssetList.get(assetId);
                            if (failedAsset != null) {
                                failedAsset.unload();
                                plugin.audioAssetList.remove(assetId);
                            }
                            call.reject("Failed to load asset for playOnce: " + ex.getMessage());
                        }
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    }
                }
            }
        );
    }

    /**
     * Starts playback of a preloaded audio asset on the main (UI) thread.
     *
     * The PluginCall must include:
     * - "assetId" (String): identifier of the preloaded asset to play.
     * - Optional "time" (number): start position in seconds.
     *
     * @param call the PluginCall containing playback parameters
     */
    @PluginMethod
    public void play(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    playOrLoop("play", call);
                }
            }
        );
    }

    @PluginMethod
    public void getCurrentTime(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    call.resolve(new JSObject().put("currentTime", asset.getCurrentPosition()));
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        try {
            String audioId = call.getString(ASSET_ID);
            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    double duration = asset.getDuration();
                    if (duration > 0) {
                        JSObject ret = new JSObject();
                        ret.put("duration", duration);
                        call.resolve(ret);
                    } else {
                        // Save the call to resolve it later when duration is available
                        saveDurationCall(audioId, call);
                    }
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void loop(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    playOrLoop("loop", call);
                }
            }
        );
    }

    @PluginMethod
    public void pause(PluginCall call) {
        try {
            initSoundPool();
            String audioId = call.getString(ASSET_ID);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    boolean wasPlaying = asset.pause();

                    if (wasPlaying) {
                        resumeList.add(asset);
                    }

                    // Update notification when paused
                    if (showNotification) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    }

                    call.resolve();
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void resume(PluginCall call) {
        try {
            initSoundPool();
            String audioId = call.getString(ASSET_ID);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    asset.resume();
                    resumeList.add(asset);

                    // Update notification when resumed
                    if (showNotification) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    }

                    call.resolve();
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        String audioId = call.getString(ASSET_ID);
                        if (!isStringValid(audioId)) {
                            call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                            return;
                        }
                        stopAudio(audioId);

                        // Clear notification when stopped
                        if (showNotification) {
                            clearNotification();
                            currentlyPlayingAssetId = null;
                        }

                        call.resolve();
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    }
                }
            }
        );
    }

    @PluginMethod
    public void unload(PluginCall call) {
        try {
            initSoundPool();
            new JSObject();
            JSObject status;

            if (isStringValid(call.getString(ASSET_ID))) {
                String audioId = call.getString(ASSET_ID);

                if (audioAssetList.containsKey(audioId)) {
                    AudioAsset asset = audioAssetList.get(audioId);
                    if (asset != null) {
                        asset.unload();
                        audioAssetList.remove(audioId);
                        call.resolve();
                    } else {
                        call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                    }
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ID_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            float volume = call.getFloat(VOLUME, 1F);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    asset.setVolume(volume);
                    call.resolve();
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING);
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setRate(PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            float rate = call.getFloat(RATE, 1F);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    asset.setRate(rate);
                }
                call.resolve();
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void isPlaying(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    call.resolve(new JSObject().put("isPlaying", asset.isPlaying()));
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        RemoteAudioAsset.clearCache(getContext());
        call.resolve();
    }

    @PluginMethod
    public void setCurrentTime(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            Double time = call.getDouble("time", 0.0);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    this.getActivity().runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    asset.setCurrentTime(time);
                                    call.resolve();
                                } catch (Exception e) {
                                    call.reject("Error setting current time: " + e.getMessage());
                                }
                            }
                        }
                    );
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    /**
     * Notify listeners that playback for the specified asset has completed.
     *
     * Emits a "complete" event containing the `assetId`.
     *
     * @param assetId the identifier of the asset that finished playback
     */
    public void dispatchComplete(String assetId) {
        JSObject ret = new JSObject();
        ret.put("assetId", assetId);
        notifyListeners("complete", ret);
    }

    /**
     * Emits a "currentTime" event for the given asset with the playback position rounded to the nearest 0.1 second.
     *
     * The emitted event payload contains `assetId` and `currentTime` (in seconds, rounded to the nearest 0.1).
     *
     * @param assetId     the identifier of the audio asset
     * @param currentTime the current playback time in seconds (will be rounded to nearest 0.1)
     */
    public void notifyCurrentTime(String assetId, double currentTime) {
        // Round to nearest 100ms
        double roundedTime = Math.round(currentTime * 10.0) / 10.0;
        JSObject ret = new JSObject();
        ret.put("currentTime", roundedTime);
        ret.put("assetId", assetId);
        notifyListeners("currentTime", ret);
    }

    /**
     * Create and initialize an AudioAsset for the given identifier and path, supporting remote URLs (including HLS),
     * file URIs, and assets packaged under the app's public folder.
     *
     * <p>If assetPath is a remote HTTP(S) URL, optional HTTP headers from {@code headersObj} are applied.
     * If the URL ends with {@code .m3u8}, HLS support is required and an exception is thrown if unavailable.
     * File URIs are opened directly and validated for existence. Paths treated as public assets are resolved
     * under the app's {@code public/} assets directory.</p>
     *
     * @param assetId         unique identifier for the asset
     * @param assetPath       file path or URL to the audio resource; for public assets this may be relative (will be resolved under {@code public/})
     * @param isLocalUrl      true when {@code assetPath} is a URL or file URI (http/https/file); false when it refers to a public asset path
     * @param volume          initial playback volume (expected range: 0.1 to 1.0)
     * @param audioChannelNum number of audio channels to configure for the asset
     * @param headersObj      optional map-like object of HTTP headers for remote requests (may be null)
     * @return                an initialized AudioAsset instance for the provided path
     * @throws Exception      if the asset cannot be located or initialized (missing file, unsupported HLS, invalid path, or other load errors)
     */
    private AudioAsset loadAudioAsset(
        String assetId,
        String assetPath,
        boolean isLocalUrl,
        float volume,
        int audioChannelNum,
        JSObject headersObj
    ) throws Exception {
        if (isLocalUrl) {
            Uri uri = Uri.parse(assetPath);
            if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                // Remote URL
                Map<String, String> requestHeaders = null;
                if (headersObj != null) {
                    requestHeaders = new HashMap<>();
                    for (Iterator<String> it = headersObj.keys(); it.hasNext(); ) {
                        String key = it.next();
                        try {
                            String value = headersObj.getString(key);
                            if (value != null) {
                                requestHeaders.put(key, value);
                            }
                        } catch (Exception e) {
                            Log.w("AudioPlugin", "Skipping non-string header: " + key);
                        }
                    }
                }

                if (assetPath.endsWith(".m3u8")) {
                    // HLS Stream - check if HLS support is available
                    if (!HlsAvailabilityChecker.isHlsAvailable()) {
                        throw new Exception(
                            "HLS streaming (.m3u8) is not available. " + "Set 'hls: true' in capacitor.config.ts and run 'npx cap sync'."
                        );
                    }
                    AudioAsset streamAudioAsset = createStreamAudioAsset(assetId, uri, volume, requestHeaders);
                    if (streamAudioAsset == null) {
                        throw new Exception("Failed to create HLS stream player. HLS may not be configured.");
                    }
                    return streamAudioAsset;
                } else {
                    RemoteAudioAsset remoteAudioAsset = new RemoteAudioAsset(this, assetId, uri, audioChannelNum, volume, requestHeaders);
                    return remoteAudioAsset;
                }
            } else if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                File file = new File(uri.getPath());
                if (!file.exists()) {
                    throw new Exception(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                }
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                AssetFileDescriptor afd = new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                AudioAsset asset = new AudioAsset(this, assetId, afd, audioChannelNum, volume);
                return asset;
            } else {
                // Handle unexpected URI schemes by attempting to treat as local file
                try {
                    File file = new File(uri.getPath());
                    if (!file.exists()) {
                        throw new Exception(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                    }
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    AssetFileDescriptor afd = new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                    AudioAsset asset = new AudioAsset(this, assetId, afd, audioChannelNum, volume);
                    Log.w(TAG, "Unexpected URI scheme '" + uri.getScheme() + "' treated as local file: " + assetPath);
                    return asset;
                } catch (Exception e) {
                    throw new Exception(
                        "Failed to load asset with unexpected URI scheme '" +
                            uri.getScheme() +
                            "' (expected 'http', 'https', or 'file'). Asset path: " +
                            assetPath +
                            ". Error: " +
                            e.getMessage()
                    );
                }
            }
        } else {
            // Handle asset in public folder
            String finalAssetPath = assetPath;
            if (!assetPath.startsWith("public/")) {
                finalAssetPath = "public/" + assetPath;
            }
            Context ctx = getContext().getApplicationContext();
            AssetManager am = ctx.getResources().getAssets();
            AssetFileDescriptor assetFileDescriptor = am.openFd(finalAssetPath);
            AudioAsset asset = new AudioAsset(this, assetId, assetFileDescriptor, audioChannelNum, volume);
            return asset;
        }
    }

    /**
     * Preloads an audio asset into the plugin's asset list.
     *
     * <p>The provided PluginCall must include:
     * <ul>
     *   <li>`assetId` (string) — identifier for the asset</li>
     *   <li>`assetPath` (string) — path or URL to the audio resource</li>
     * </ul>
     * Optional keys on the call:
     * <ul>
     *   <li>`isUrl` (boolean) — true when `assetPath` is a remote URL</li>
     *   <li>`isComplex` (boolean) — when true, `volume` and `audioChannelNum` may be provided</li>
     *   <li>`volume` (number) — initial playback volume (default 1.0)</li>
     *   <li>`audioChannelNum` (int) — audio channel count (default 1)</li>
     *   <li>`headers` (object) — HTTP headers for remote requests</li>
     *   <li>`notificationMetadata` (object) — optional metadata (`title`, `artist`, `album`, `artworkUrl`) to attach to the asset</li>
     * </ul>
     *
     * <p>On success the call is resolved with a status indicating success. The method rejects the call
     * when required parameters are missing, when an asset with the same id already exists, or when
     * the asset cannot be loaded.
     *
     * @param call the PluginCall containing asset parameters and options
     */
    private void preloadAsset(PluginCall call) {
        float volume = 1F;
        int audioChannelNum = 1;
        JSObject status = new JSObject();
        status.put("STATUS", "OK");

        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            String assetPath = call.getString(ASSET_PATH);
            if (!isStringValid(assetPath)) {
                call.reject(ERROR_ASSET_PATH_MISSING + " - " + audioId + " - " + assetPath);
                return;
            }

            boolean isLocalUrl = call.getBoolean("isUrl", false);
            boolean isComplex = call.getBoolean("isComplex", false);

            Log.d("AudioPlugin", "Debug: audioId = " + audioId + ", assetPath = " + assetPath + ", isLocalUrl = " + isLocalUrl);

            if (audioAssetList.containsKey(audioId)) {
                call.reject(ERROR_AUDIO_EXISTS + " - " + audioId);
                return;
            }

            if (isComplex) {
                volume = call.getFloat(VOLUME, 1F);
                audioChannelNum = call.getInt(AUDIO_CHANNEL_NUM, 1);
            }

            // Store notification metadata if provided
            JSObject metadata = call.getObject(NOTIFICATION_METADATA);
            if (metadata != null) {
                Map<String, String> metadataMap = new HashMap<>();
                if (metadata.has("title")) metadataMap.put("title", metadata.getString("title"));
                if (metadata.has("artist")) metadataMap.put("artist", metadata.getString("artist"));
                if (metadata.has("album")) metadataMap.put("album", metadata.getString("album"));
                if (metadata.has("artworkUrl")) metadataMap.put("artworkUrl", metadata.getString("artworkUrl"));
                if (!metadataMap.isEmpty()) {
                    notificationMetadataMap.put(audioId, metadataMap);
                }
            }

            // Use the helper method to load the asset
            JSObject headersObj = call.getObject("headers");
            AudioAsset asset = loadAudioAsset(audioId, assetPath, isLocalUrl, volume, audioChannelNum, headersObj);

            if (asset == null) {
                call.reject("Failed to load asset");
                return;
            }

            // Set completion listener and add to asset list
            asset.setCompletionListener(this::dispatchComplete);
            audioAssetList.put(audioId, asset);
            call.resolve(status);
        } catch (Exception ex) {
            Log.e("AudioPlugin", "Error in preloadAsset", ex);
            call.reject("Error in preloadAsset: " + ex.getMessage());
        }
    }

    private void playOrLoop(String action, final PluginCall call) {
        try {
            final String audioId = call.getString(ASSET_ID);
            final Double time = call.getDouble("time", 0.0);
            Log.d(TAG, "Playing asset: " + audioId + ", action: " + action + ", assets count: " + audioAssetList.size());

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                Log.d(TAG, "Found asset: " + audioId + ", type: " + asset.getClass().getSimpleName());

                if (asset != null) {
                    if (LOOP.equals(action)) {
                        asset.loop();
                    } else {
                        if (fadeMusic) {
                            asset.playWithFade(time);
                        } else {
                            asset.play(time);
                        }
                    }

                    // Update notification if enabled
                    if (showNotification) {
                        currentlyPlayingAssetId = audioId;
                        updateNotification(audioId);
                    }

                    call.resolve();
                } else {
                    call.reject("Asset is null: " + audioId);
                }
            } else {
                call.reject("Asset not found: " + audioId);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error in playOrLoop", ex);
            call.reject(ex.getMessage());
        }
    }

    private void initSoundPool() {
        if (audioAssetList == null) {
            audioAssetList = new HashMap<>();
        }

        if (resumeList == null) {
            resumeList = new ArrayList<>();
        }
    }

    private boolean isStringValid(String value) {
        return (value != null && !value.isEmpty() && !value.equals("null"));
    }

    /**
     * Creates a StreamAudioAsset via reflection.
     * This allows the StreamAudioAsset class to be excluded at compile time when HLS is disabled,
     * reducing APK size by ~4MB.
     *
     * @param audioId The unique identifier for the audio asset
     * @param uri The URI of the HLS stream
     * @param volume The initial volume (0.0 to 1.0)
     * @param headers Optional HTTP headers for the request
     * @return The created AudioAsset, or null if creation failed
     */
    private AudioAsset createStreamAudioAsset(String audioId, Uri uri, float volume, java.util.Map<String, String> headers) {
        try {
            Class<?> streamAudioAssetClass = Class.forName("ee.forgr.audio.StreamAudioAsset");
            java.lang.reflect.Constructor<?> constructor = streamAudioAssetClass.getConstructor(
                NativeAudio.class,
                String.class,
                Uri.class,
                float.class,
                java.util.Map.class
            );
            return (AudioAsset) constructor.newInstance(this, audioId, uri, volume, headers);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "StreamAudioAsset class not found. HLS support is not included in this build.", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create StreamAudioAsset", e);
            return null;
        }
    }

    private void stopAudio(String audioId) throws Exception {
        if (!audioAssetList.containsKey(audioId)) {
            throw new Exception(ERROR_ASSET_NOT_LOADED);
        }

        AudioAsset asset = audioAssetList.get(audioId);
        if (asset != null) {
            if (fadeMusic) {
                asset.stopWithFade();
            } else {
                asset.stop();
            }
        }
    }

    private void saveDurationCall(String audioId, PluginCall call) {
        Log.d(TAG, "Saving duration call for later: " + audioId);
        pendingDurationCalls.put(audioId, call);
    }

    public void notifyDurationAvailable(String assetId, double duration) {
        Log.d(TAG, "Duration available for " + assetId + ": " + duration);
        PluginCall savedCall = pendingDurationCalls.remove(assetId);
        if (savedCall != null) {
            JSObject ret = new JSObject();
            ret.put("duration", duration);
            savedCall.resolve(ret);
        }
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            ret.put("version", this.pluginVersion);
            call.resolve(ret);
        } catch (final Exception e) {
            call.reject("Could not get plugin version", e);
        }
    }

    @PluginMethod
    public void deinitPlugin(final PluginCall call) {
        try {
            // Stop all playing audio
            if (audioAssetList != null) {
                for (AudioAsset asset : audioAssetList.values()) {
                    if (asset != null) {
                        asset.stop();
                    }
                }
            }

            // Clear notification and release media session
            if (showNotification) {
                clearNotification();
                if (mediaSession != null) {
                    mediaSession.release();
                    mediaSession = null;
                }
            }

            // Release audio focus if we requested it
            if (audioFocusRequested && this.audioManager != null) {
                this.audioManager.abandonAudioFocus(this);
                audioFocusRequested = false;
            }

            // Restore original audio mode if we changed it
            if (originalAudioMode != AudioManager.MODE_INVALID && this.audioManager != null) {
                this.audioManager.setMode(originalAudioMode);
                originalAudioMode = AudioManager.MODE_INVALID;
            }

            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error in deinitPlugin", e);
            call.reject("Error deinitializing plugin: " + e.getMessage());
        }
    }

    // Notification and MediaSession methods

    private void setupMediaSession() {
        if (mediaSession != null) return;

        mediaSession = new MediaSessionCompat(getContext(), "NativeAudio");

        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder().setActions(
            PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP
        );
        mediaSession.setPlaybackState(stateBuilder.build());

        // Set callback for media button events
        mediaSession.setCallback(
            new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null && !asset.isPlaying()) {
                                asset.resume();
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error resuming audio from media session", e);
                        }
                    }
                }

                @Override
                public void onPause() {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null) {
                                asset.pause();
                                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error pausing audio from media session", e);
                        }
                    }
                }

                @Override
                public void onStop() {
                    if (currentlyPlayingAssetId != null) {
                        try {
                            stopAudio(currentlyPlayingAssetId);
                            clearNotification();
                            currentlyPlayingAssetId = null;
                        } catch (Exception e) {
                            Log.e(TAG, "Error stopping audio from media session", e);
                        }
                    }
                }
            }
        );

        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Audio Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows currently playing audio");
            NotificationManager notificationManager = getContext().getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification(String audioId) {
        if (mediaSession == null) return;

        Map<String, String> metadata = notificationMetadataMap.get(audioId);
        String title = metadata != null && metadata.containsKey("title") ? metadata.get("title") : "Playing";
        String artist = metadata != null && metadata.containsKey("artist") ? metadata.get("artist") : "";
        String album = metadata != null && metadata.containsKey("album") ? metadata.get("album") : "";
        String artworkUrl = metadata != null ? metadata.get("artworkUrl") : null;

        // Update MediaSession metadata
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);

        // Load artwork if provided
        if (artworkUrl != null) {
            loadArtwork(artworkUrl, (bitmap) -> {
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
                }
                mediaSession.setMetadata(metadataBuilder.build());
                showNotification(title, artist);
            });
        } else {
            mediaSession.setMetadata(metadataBuilder.build());
            showNotification(title, artist);
        }

        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
    }

    private void showNotification(String title, String artist) {
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setStyle(
                new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(android.R.drawable.ic_media_previous, "Previous", null)
            .addAction(android.R.drawable.ic_media_pause, "Pause", null)
            .addAction(android.R.drawable.ic_media_next, "Next", null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void clearNotification() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
        notificationManager.cancel(NOTIFICATION_ID);

        if (mediaSession != null) {
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
            .setState(state, 0, state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void loadArtwork(String urlString, ArtworkCallback callback) {
        new Thread(() -> {
            try {
                Uri uri = Uri.parse(urlString);
                Bitmap bitmap = null;

                if (uri.getScheme() == null || uri.getScheme().equals("file")) {
                    // Local file
                    File file = new File(uri.getPath());
                    if (file.exists()) {
                        bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    }
                } else {
                    // Remote URL
                    URL url = new URL(urlString);
                    bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
                }

                Bitmap finalBitmap = bitmap;
                new Handler(Looper.getMainLooper()).post(() -> callback.onArtworkLoaded(finalBitmap));
            } catch (Exception e) {
                Log.e(TAG, "Error loading artwork", e);
                new Handler(Looper.getMainLooper()).post(() -> callback.onArtworkLoaded(null));
            }
        })
            .start();
    }

    interface ArtworkCallback {
        void onArtworkLoaded(Bitmap bitmap);
    }
}