package ee.forgr.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class RemoteAudioAsset extends AudioAsset {

    private static final String TAG = "RemoteAudioAsset";
    private final ArrayList<ExoPlayer> players;
    private int playIndex = 0;
    private final Uri uri;
    private float volume;
    private boolean isPrepared = false;
    private static SimpleCache cache;
    private static final long MAX_CACHE_SIZE = 100 * 1024 * 1024; // 100MB cache
    protected AudioCompletionListener completionListener;
    private static final float FADE_STEP = 0.05f;
    private static final int FADE_DELAY_MS = 80; // 80ms between steps
    private float initialVolume;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;
    private final Map<String, String> headers;

    public RemoteAudioAsset(NativeAudio owner, String assetId, Uri uri, int audioChannelNum, float volume, Map<String, String> headers)
        throws Exception {
        super(owner, assetId, null, 0, volume);
        this.uri = uri;
        this.volume = volume;
        this.initialVolume = volume;
        this.players = new ArrayList<>();
        this.headers = headers;

        if (audioChannelNum < 1) {
            audioChannelNum = 1;
        }

        final int channels = audioChannelNum;
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int i = 0; i < channels; i++) {
                                ExoPlayer player = new ExoPlayer.Builder(owner.getContext()).build();
                                player.setPlaybackSpeed(1.0f);
                                players.add(player);
                                initializePlayer(player);
                            }
                        } catch (Exception e) {
                            logger.error("Error initializing players", e);
                        }
                    }
                }
            );
    }

    @UnstableApi
    private void initializePlayer(ExoPlayer player) {
        logger.debug("Initializing player");

        // Initialize cache if not already done
        if (cache == null) {
            File cacheDir = new File(owner.getContext().getCacheDir(), "media");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            cache = new SimpleCache(
                cacheDir,
                new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE),
                new StandaloneDatabaseProvider(owner.getContext())
            );
        }

        // Create cached data source factory with custom headers
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000);

        // Add custom headers if provided
        if (headers != null && !headers.isEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(headers);
        }

        CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        // Create media source
        MediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));

        player.setMediaSource(mediaSource);
        player.setVolume(volume);
        player.prepare();

        // Add listener for duration
        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "Player state changed to: " + getStateString(playbackState));
                    if (playbackState == Player.STATE_READY) {
                        isPrepared = true;
                        long duration = player.getDuration();
                        Log.d(TAG, "Duration available on STATE_READY: " + duration + " ms");
                        if (duration != androidx.media3.common.C.TIME_UNSET) {
                            double durationSec = duration / 1000.0;
                            Log.d(TAG, "Notifying duration: " + durationSec + " seconds");
                            owner.notifyDurationAvailable(assetId, durationSec);
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        notifyCompletion();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    logger.debug("isPlaying changed to: " + isPlaying + ", state: " + getStateString(player.getPlaybackState()));
                }

                @Override
                public void onIsLoadingChanged(boolean isLoading) {
                    logger.debug("isLoading changed to: " + isLoading + ", state: " + getStateString(player.getPlaybackState()));
                }
            }
        );

        logger.debug("Player initialization complete");
    }

    private String getStateString(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "IDLE";
            case Player.STATE_BUFFERING:
                return "BUFFERING";
            case Player.STATE_READY:
                return "READY";
            case Player.STATE_ENDED:
                return "ENDED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    @Override
    public void play(double time, float volume) throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!isPrepared) {
                            player.addListener(
                                new Player.Listener() {
                                    @Override
                                    public void onPlaybackStateChanged(int playbackState) {
                                        if (playbackState == Player.STATE_READY) {
                                            isPrepared = true;
                                            try {
                                                playInternal(player, time, volume);
                                                startCurrentTimeUpdates();
                                            } catch (Exception e) {
                                                Log.e(TAG, "Error playing after prepare", e);
                                            }
                                        } else if (playbackState == Player.STATE_ENDED) {
                                            notifyCompletion();
                                        }
                                    }
                                }
                            );
                        } else {
                            try {
                                playInternal(player, time, volume);
                                startCurrentTimeUpdates();
                            } catch (Exception e) {
                                logger.error("Error playing", e);
                            }
                        }
                    }
                }
            );

        playIndex = (playIndex + 1) % players.size();
    }

    private void playInternal(final ExoPlayer player, final double time, final float volume) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (time != 0) {
                            player.seekTo(Math.round(time * 1000));
                        }
                        if (volume != 0) {
                            player.setVolume(volume);
                        }
                        player.play();
                    }
                }
            );
    }

    @Override
    public boolean pause() throws Exception {
        final boolean[] wasPlaying = { false };
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        cancelFade();
                        for (ExoPlayer player : players) {
                            if (player != null && player.isPlaying()) {
                                player.pause();
                                stopCurrentTimeUpdates();
                                wasPlaying[0] = true;
                            }
                        }
                    }
                }
            );
        return wasPlaying[0];
    }

    @Override
    public void resume() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        for (ExoPlayer player : players) {
                            if (player != null && !player.isPlaying()) {
                                player.play();
                            }
                        }
                        startCurrentTimeUpdates();
                    }
                }
            );
    }

    @Override
    public void stop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        cancelFade();
                        for (ExoPlayer player : players) {
                            if (player != null && player.isPlaying()) {
                                player.stop();
                                dispatchComplete();
                            }
                            // Reset the ExoPlayer to make it ready for future playback
                            initializePlayer(player);
                        }
                        isPrepared = false;
                    }
                }
            );
    }

    @Override
    public void loop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!players.isEmpty()) {
                            ExoPlayer player = players.get(playIndex);
                            player.setRepeatMode(Player.REPEAT_MODE_ONE);
                            player.play();
                            playIndex = (playIndex + 1) % players.size();
                            startCurrentTimeUpdates();
                        }
                    }
                }
            );
    }

    @Override
    public void unload() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Synchronous cleanup when already on the main thread
            stopCurrentTimeUpdates();
            for (ExoPlayer player : new ArrayList<>(players)) {
                try {
                    player.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing player", e);
                }
            }
            players.clear();
            isPrepared = false;
            playIndex = 0;
            return;
        }
        // Ensure cleanup completes before returning when called off the main thread
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                stopCurrentTimeUpdates();
                for (ExoPlayer player : new ArrayList<>(players)) {
                    try {
                        player.release();
                    } catch (Exception e) {
                        Log.w(TAG, "Error releasing player", e);
                    }
                }
                players.clear();
                isPrepared = false;
                playIndex = 0;
            } finally {
                latch.countDown();
            }
        });
        try {
            // Don't block forever; adjust timeout as needed
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void setVolume(final float volume, final double duration) throws Exception {
        this.volume = volume;
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        cancelFade();
                        for (ExoPlayer player : players) {
                            if (player == null) continue;
                            if (player.isPlaying() && duration > 0) {
                                fadeTo(player, (float) duration, volume);
                            } else {
                                player.setVolume(volume);
                            }
                        }
                    }
                }
            );
    }

    @Override
    public void setVolume(final float volume) throws Exception {
        setVolume(volume, 0);
    }

    @Override
    public float getVolume() throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        return player != null ? player.getVolume() : 0;
    }

    @Override
    public boolean isPlaying() throws Exception {
        if (players.isEmpty() || !isPrepared) return false;

        ExoPlayer player = players.get(playIndex);
        return player != null && player.isPlaying();
    }

    @Override
    public double getDuration() {
        logger.debug("getDuration called, players empty: " + players.isEmpty() + ", isPrepared: " + isPrepared);
        if (!players.isEmpty() && isPrepared) {
            final double[] duration = { 0 };
            owner
                .getActivity()
                .runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            ExoPlayer player = players.get(playIndex);
                            int state = player.getPlaybackState();
                            logger.debug("Player state: " + state + " (READY=" + Player.STATE_READY + ")");
                            if (state == Player.STATE_READY) {
                                long rawDuration = player.getDuration();
                                logger.debug("Raw duration: " + rawDuration + ", TIME_UNSET=" + androidx.media3.common.C.TIME_UNSET);
                                if (rawDuration != androidx.media3.common.C.TIME_UNSET) {
                                    duration[0] = rawDuration / 1000.0;
                                    logger.debug("Final duration in seconds: " + duration[0]);
                                } else {
                                    logger.debug("Duration is TIME_UNSET");
                                }
                            } else {
                                logger.debug("Player not in READY state");
                            }
                        }
                    }
                );
            return duration[0];
        }
        logger.debug("No players or not prepared for duration");
        return 0;
    }

    @Override
    public double getCurrentPosition() {
        if (!players.isEmpty() && isPrepared) {
            final double[] position = { 0 };
            owner
                .getActivity()
                .runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            ExoPlayer player = players.get(playIndex);
                            if (player.getPlaybackState() == Player.STATE_READY) {
                                long rawPosition = player.getCurrentPosition();
                                logger.debug("Raw position: " + rawPosition);
                                position[0] = rawPosition / 1000.0;
                            }
                        }
                    }
                );
            return position[0];
        }
        return 0;
    }

    @Override
    public void setCurrentTime(double time) throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (isPrepared) {
                            player.seekTo(Math.round(time * 1000));
                        } else {
                            player.addListener(
                                new Player.Listener() {
                                    @Override
                                    public void onPlaybackStateChanged(int playbackState) {
                                        if (playbackState == Player.STATE_READY) {
                                            isPrepared = true;
                                            player.seekTo(Math.round(time * 1000));
                                        }
                                    }
                                }
                            );
                        }
                    }
                }
            );
    }

    @UnstableApi
    public static void clearCache(Context context) {
        try {
            if (cache != null) {
                cache.release();
                cache = null;
            }
            File cacheDir = new File(context.getCacheDir(), "media");
            if (cacheDir.exists()) {
                deleteDir(cacheDir);
            }
        } catch (Exception e) {
            logger.error("Error clearing audio cache", e);
        }
    }

    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    public void playWithFadeIn(double time, float volume, float fadeInDurationMs) throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (player != null && !player.isPlaying()) {
                            if (time != 0) {
                                player.seekTo(Math.round(time * 1000));
                            }
                            player.setVolume(0);
                            player.play();
                            startCurrentTimeUpdates();
                            fadeIn(player, fadeInDurationMs, volume);
                        }
                    }
                }
            );
    }

    private void fadeIn(final ExoPlayer player, float fadeInDurationMs, float volume) {
        cancelFade();
        fadeState = FadeState.FADE_IN;

        final float targetVolume = volume;
        final int steps = Math.max(1, (int) (fadeInDurationMs / FADE_DELAY_MS));
        final float fadeStep = targetVolume / steps;

        Log.d(
            TAG,
            "Beginning fade in at time " +
                getCurrentPosition() +
                " over " +
                (fadeInDurationMs / 1000.0) +
                "s to target volume " +
                targetVolume +
                " in " +
                steps +
                " steps (step duration: " +
                (FADE_DELAY_MS / 1000.0) +
                "s"
        );

        fadeTask = fadeExecutor.scheduleWithFixedDelay(
            new Runnable() {
                float currentVolume = 0;

                @Override
                public void run() {
                    if (fadeState != FadeState.FADE_IN || currentVolume >= targetVolume) {
                        fadeState = FadeState.NONE;
                        cancelFade();
                        logger.debug("Fade in complete at time " + getCurrentPosition());
                        return;
                    }
                    final float previousCurrentVolume = currentVolume;
                    currentVolume += fadeStep;
                    final float resolvedTargetVolume = Math.min(currentVolume, targetVolume);
                    Log.v(
                        TAG,
                        "Fade in step: from " + previousCurrentVolume + " to " + currentVolume + " to target " + resolvedTargetVolume
                    );
                    owner
                        .getActivity()
                        .runOnUiThread(() -> {
                            if (player != null && player.isPlaying()) {
                                player.setVolume(currentVolume);
                            }
                        });
                }
            },
            0,
            FADE_DELAY_MS,
            TimeUnit.MILLISECONDS
        );
    }

    public void stopWithFade(float fadeOutDurationMs, boolean asPause) throws Exception {
        if (players.isEmpty()) {
            return;
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (player != null && player.isPlaying()) {
                    fadeOut(player, fadeOutDurationMs, asPause);
                }
            });
    }

    private void fadeOut(final ExoPlayer player, float fadeOutDurationMs, boolean asPause) {
        cancelFade();
        fadeState = FadeState.FADE_OUT;

        final int steps = Math.max(1, (int) (fadeOutDurationMs / FADE_DELAY_MS));
        final float initialVolume = player.getVolume();
        final float fadeStep = initialVolume / steps;

        Log.d(
            TAG,
            "Beginning fade out from volume " +
                initialVolume +
                " at time " +
                getCurrentPosition() +
                " over " +
                (fadeOutDurationMs / 1000.0) +
                "s in " +
                steps +
                " steps (step duration: " +
                (FADE_DELAY_MS / 1000.0) +
                "s)"
        );

        fadeTask = fadeExecutor.scheduleWithFixedDelay(
            new Runnable() {
                float currentVolume = initialVolume;

                @Override
                public void run() {
                    if (fadeState != FadeState.FADE_OUT || currentVolume <= 0) {
                        fadeState = FadeState.NONE;
                        owner
                            .getActivity()
                            .runOnUiThread(() -> {
                                if (player != null && player.isPlaying()) {
                                    if (asPause) {
                                        player.pause();
                                        logger.verbose("Faded out to pause at time " + getCurrentPosition());
                                    } else {
                                        player.setVolume(0);
                                        player.stop();
                                        logger.verbose("Faded out to stop at time " + getCurrentPosition());
                                    }
                                }
                            });
                        cancelFade();
                        logger.verbose("Fade out complete at time " + getCurrentPosition());
                        return;
                    }
                    final float previousCurrentVolume = currentVolume;
                    currentVolume -= fadeStep;
                    final float thisTargetVolume = Math.max(currentVolume, 0);
                    logger.debug(
                        "Fade out step: from " + previousCurrentVolume + " to " + currentVolume + " to target " + thisTargetVolume
                    );
                    owner
                        .getActivity()
                        .runOnUiThread(() -> {
                            if (player != null && player.isPlaying()) {
                                player.setVolume(thisTargetVolume);
                            }
                        });
                }
            },
            0,
            FADE_DELAY_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void fadeTo(final ExoPlayer player, float fadeDurationMs, float targetVolume) {
        cancelFade();
        fadeState = FadeState.FADE_TO;

        final int steps = Math.max(1, (int) (fadeDurationMs / FADE_DELAY_MS));
        final float minVolume = zeroVolume;
        final float maxVol = maxVolume;
        final float initialVolume = Math.max(player.getVolume(), minVolume);
        final float finalTargetVolume = Math.max(targetVolume, minVolume);

        // Clamp values to avoid overflow/underflow and invalid pow inputs
        final float safeInitialVolume = Math.max(initialVolume, minVolume);
        final float safeFinalTargetVolume = Math.max(finalTargetVolume, minVolume);

        double ratio;
        if (steps <= 0 || safeInitialVolume <= 0f || safeFinalTargetVolume <= 0f) {
            ratio = 1.0;
        } else if (safeInitialVolume == safeFinalTargetVolume) {
            ratio = 1.0;
        } else {
            ratio = Math.pow(safeFinalTargetVolume / safeInitialVolume, 1.0 / steps);
            if (Double.isNaN(ratio) || Double.isInfinite(ratio) || ratio <= 0.0) {
                ratio = 1.0;
            }
        }

        Log.d(
            TAG,
            "Beginning exponential fade from volume " +
                initialVolume +
                " to " +
                finalTargetVolume +
                " over " +
                (fadeDurationMs / 1000.0) +
                "s in " +
                steps +
                " steps (step duration: " +
                (FADE_DELAY_MS / 1000.0) +
                "s, ratio: " +
                ratio +
                ")"
        );

        double finalRatio = ratio;
        fadeTask = fadeExecutor.scheduleWithFixedDelay(
            new Runnable() {
                int currentStep = 0;
                float currentVolume = initialVolume;

                @Override
                public void run() {
                    if (fadeState != FadeState.FADE_TO || player == null || !player.isPlaying() || currentStep >= steps) {
                        fadeState = FadeState.NONE;
                        cancelFade();
                        logger.debug("Fade to complete at time " + getCurrentPosition());
                        return;
                    }
                    try {
                        if (finalRatio == 1.0) {
                            currentVolume = safeFinalTargetVolume;
                        } else {
                            currentVolume *= (float) finalRatio;
                        }
                        // Clamp volume between minVolume and maxVolume
                        currentVolume = Math.min(Math.max(currentVolume, minVolume), maxVol);
                        logger.verbose("Fade to step " + currentStep + ": volume set to " + currentVolume);
                        owner
                            .getActivity()
                            .runOnUiThread(() -> {
                                if (player != null && player.isPlaying()) {
                                    player.setVolume(currentVolume);
                                }
                            });
                        currentStep++;
                    } catch (Exception e) {
                        logger.error("Error during fade to", e);
                        cancelFade();
                    }
                }
            },
            0,
            FADE_DELAY_MS,
            TimeUnit.MILLISECONDS
        );
    }

    protected void cancelFade() {
        if (fadeTask != null && !fadeTask.isCancelled()) {
            fadeTask.cancel(true);
        }
        fadeState = FadeState.NONE;
        fadeTask = null;
    }

    @Override
    protected void startCurrentTimeUpdates() {
        logger.debug("Starting timer updates");
        if (currentTimeHandler == null) {
            currentTimeHandler = new Handler(Looper.getMainLooper());
        }
        // Reset completion status for this assetId
        dispatchedCompleteMap.put(assetId, false);

        // Wait for player to be truly ready
        currentTimeHandler.postDelayed(
            new Runnable() {
                @Override
                public void run() {
                    if (!players.isEmpty() && playIndex >= 0 && playIndex < players.size()) {
                        ExoPlayer player = players.get(playIndex);
                        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                            startTimeUpdateLoop();
                        } else {
                            // Check again in 100ms
                            currentTimeHandler.postDelayed(this, 100);
                        }
                    }
                }
            },
            100
        );
    }

    private void startTimeUpdateLoop() {
        currentTimeRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    boolean isPaused = false;
                    if (!players.isEmpty() && playIndex >= 0 && playIndex < players.size()) {
                        ExoPlayer player = players.get(playIndex);
                        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                            if (player.isPlaying()) {
                                double currentTime = player.getCurrentPosition() / 1000.0; // Get time directly
                                logger.debug("Play timer update: currentTime = " + currentTime);
                                if (owner != null) owner.notifyCurrentTime(assetId, currentTime);
                                currentTimeHandler.postDelayed(this, 100);
                                return;
                            } else if (!player.getPlayWhenReady()) {
                                isPaused = true;
                            }
                        }
                    }
                    logger.debug("Stopping play timer - not playing or not ready");
                    stopCurrentTimeUpdates();
                    if (isPaused) {
                        logger.verbose("Playback is paused, not dispatching complete");
                    } else {
                        logger.verbose("Playback is stopped, dispatching complete");
                        dispatchComplete();
                    }
                } catch (Exception e) {
                    logger.error("Error getting current time", e);
                    stopCurrentTimeUpdates();
                }
            }
        };
        try {
            if (currentTimeHandler == null) {
                currentTimeHandler = new Handler(Looper.getMainLooper());
            }
            currentTimeHandler.post(currentTimeRunnable);
        } catch (Exception e) {
            logger.error("Error starting current time updates", e);
        }
    }
}
