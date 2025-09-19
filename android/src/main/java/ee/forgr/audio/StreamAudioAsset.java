package ee.forgr.audio;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class StreamAudioAsset extends AudioAsset implements AutoCloseable {

    private static final String TAG = "StreamAudioAsset";
    private static final Logger logger = new Logger(TAG);
    private ExoPlayer player;
    private final Uri uri;
    private float volume;
    private boolean isPrepared = false;
    private static final long LIVE_OFFSET_MS = 5000; // 5 seconds behind live

    public StreamAudioAsset(NativeAudio owner, String assetId, Uri uri, float volume) throws Exception {
        super(owner, assetId, null, 0, volume);
        this.uri = uri;
        this.volume = volume;
        this.fadeExecutor = Executors.newSingleThreadScheduledExecutor();

        createPlayer();
    }

    private void createPlayer() {
        // Adjust buffer settings for smoother playback
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60000, // Increase min buffer to 60s
                180000, // Increase max buffer to 180s
                5000, // Increase buffer for playback
                10000 // Increase buffer to start playback
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60000, true) // Increase back buffer
            .build();

        player = new ExoPlayer.Builder(owner.getContext())
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(
                new DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.04f)
                    .setMaxLiveOffsetErrorMsForUnitSpeed(LIVE_OFFSET_MS)
                    .build()
            )
            .build();

        player.setVolume(volume);
        initializePlayer();
    }

    private void initializePlayer() {
        logger.debug("Initializing stream player with volume: " + volume);

        // Configure HLS source with better settings for live streaming
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent("ExoPlayer");

        HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpDataSourceFactory)
            .setAllowChunklessPreparation(true)
            .setTimestampAdjusterInitializationTimeoutMs(LIVE_OFFSET_MS) // 30 seconds timeout
            .createMediaSource(MediaItem.fromUri(uri));

        player.setMediaSource(mediaSource);
        player.setVolume(volume);
        player.prepare();

        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    logger.debug("Stream state changed to: " + getStateString(state));
                    if (state == Player.STATE_READY && !isPrepared) {
                        isPrepared = true;
                        if (player.isCurrentMediaItemLive()) {
                            player.seekToDefaultPosition();
                        }
                    }
                }

                @Override
                public void onIsLoadingChanged(boolean isLoading) {
                    logger.debug("Loading state changed: " + isLoading);
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    logger.debug("Playing state changed: " + isPlaying);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    logger.error("Player error: " + error.getMessage());
                    isPrepared = false;
                    // Try to recover by recreating the player
                    owner
                        .getActivity()
                        .runOnUiThread(() -> {
                            player.release();
                            createPlayer();
                        });
                }
            }
        );
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
        logger.debug("Play called with time: " + time + ", isPrepared: " + isPrepared);
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (!isPrepared) {
                    // If not prepared, wait for preparation
                    player.addListener(
                        new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int state) {
                                logger.debug("Play-wait state changed to: " + getStateString(state));
                                if (state == Player.STATE_READY) {
                                    startPlayback(time, volume);
                                    startCurrentTimeUpdates();
                                    player.removeListener(this);
                                }
                            }
                        }
                    );
                } else {
                    startPlayback(time, volume);
                }
            });
    }

    private void startPlayback(double time, float volume) {
        logger.debug("Starting playback with time: " + time);
        if (time != 0) {
            player.seekTo(Math.round(time * 1000));
        } else if (player.isCurrentMediaItemLive()) {
            player.seekToDefaultPosition();
        }
        player.setPlaybackParameters(new PlaybackParameters(1.0f));
        player.setVolume(volume);
        player.setPlayWhenReady(true);
        startCurrentTimeUpdates();
    }

    @Override
    public boolean pause() throws Exception {
        final boolean[] wasPlaying = { false };
        owner
            .getActivity()
            .runOnUiThread(() -> {
                cancelFade();
                if (player != null && player.isPlaying()) {
                    player.setPlayWhenReady(false);
                    stopCurrentTimeUpdates();
                    wasPlaying[0] = true;
                }
            });
        return wasPlaying[0];
    }

    @Override
    public void resume() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.setPlayWhenReady(true);
                startCurrentTimeUpdates();
            });
    }

    @Override
    public void stop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                cancelFade();
                // First stop playback
                player.stop();
                // Reset player state
                player.clearMediaItems();
                isPrepared = false;

                // Create new media source
                DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent("ExoPlayer");

                HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .setTimestampAdjusterInitializationTimeoutMs(LIVE_OFFSET_MS)
                    .createMediaSource(MediaItem.fromUri(uri));

                // Set new media source and prepare
                player.setMediaSource(mediaSource);
                player.prepare();

                // Add listener for preparation completion
                player.addListener(
                    new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            logger.debug("Stop-reinit state changed to: " + getStateString(state));
                            if (state == Player.STATE_READY) {
                                isPrepared = true;
                                player.removeListener(this);
                            } else if (state == Player.STATE_IDLE) {
                                // Retry preparation if it fails
                                player.prepare();
                            }
                        }
                    }
                );
            });
    }

    @Override
    public void loop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.setPlayWhenReady(true);
                startCurrentTimeUpdates();
            });
    }

    @Override
    public void unload() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                cancelFade();
                player.stop();
                player.clearMediaItems();
                player.release();
                isPrepared = false;
                close(); // Ensure fadeExecutor is shutdown
            });
    }

    @Override
    public void close() {
        if (fadeExecutor != null && !fadeExecutor.isShutdown()) {
            fadeExecutor.shutdown();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    @Override
    public void setVolume(float volume, double duration) throws Exception {
        this.volume = volume;
        owner
            .getActivity()
            .runOnUiThread(() -> {
                cancelFade();
                try {
                    if (this.isPlaying() && duration > 0) {
                        fadeTo(duration, volume);
                    } else {
                        player.setVolume(volume);
                    }
                } catch (Exception e) {
                    logger.error("Error setting volume", e);
                }
            });
    }

    @Override
    public float getVolume() throws Exception {
        if (player != null) {
            return player.getVolume();
        }
        return 0;
    }

    @Override
    public boolean isPlaying() throws Exception {
        return player != null && player.isPlaying();
    }

    @Override
    public double getDuration() {
        if (isPrepared) {
            final double[] duration = { 0 };
            owner
                .getActivity()
                .runOnUiThread(() -> {
                    if (player.getPlaybackState() == Player.STATE_READY) {
                        long rawDuration = player.getDuration();
                        if (rawDuration != androidx.media3.common.C.TIME_UNSET) {
                            duration[0] = rawDuration / 1000.0;
                        }
                    }
                });
            return duration[0];
        }
        return 0;
    }

    @Override
    public double getCurrentPosition() {
        if (isPrepared) {
            final double[] position = { 0 };
            owner
                .getActivity()
                .runOnUiThread(() -> {
                    if (player.getPlaybackState() == Player.STATE_READY) {
                        position[0] = player.getCurrentPosition() / 1000.0;
                    }
                });
            return position[0];
        }
        return 0;
    }

    @Override
    public void setCurrentTime(double time) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.seekTo(Math.round(time * 1000));
            });
    }

    @Override
    public void playWithFadeIn(double time, float volume, double fadeInDurationMs) throws Exception {
        logger.debug("playWithFadeIn called with time: " + time);
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (!isPrepared) {
                    // If not prepared, wait for preparation
                    player.addListener(
                        new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int state) {
                                if (state == Player.STATE_READY) {
                                    startPlaybackWithFade(time, volume, fadeInDurationMs);
                                    player.removeListener(this);
                                }
                            }
                        }
                    );
                } else {
                    startPlaybackWithFade(time, volume, fadeInDurationMs);
                }
            });
    }

    private void startPlaybackWithFade(double time, float volume, double fadeInDurationMs) {
        if (!player.isPlayingAd()) { // Make sure we're not in an ad
            if (time != 0) {
                player.seekTo(Math.round(time * 1000));
            } else if (player.isCurrentMediaItemLive()) {
                long liveEdge = player.getCurrentLiveOffset();
                if (liveEdge > 0) {
                    player.seekTo(liveEdge - LIVE_OFFSET_MS);
                }
            }

            // Wait for buffering to complete before starting playback
            player.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) {
                            player.removeListener(this);
                            // Ensure playback rate is normal
                            player.setPlaybackParameters(new PlaybackParameters(1.0f));
                            // Start with volume 0
                            player.setVolume(0);
                            player.setPlayWhenReady(true);
                            startCurrentTimeUpdates();
                            // Start fade after ensuring we're actually playing
                            checkAndStartFade(fadeInDurationMs, volume);
                        }
                    }
                }
            );
        }
    }

    private void checkAndStartFade(double fadeInDurationMs, float volume) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(
            new Runnable() {
                int attempts = 0;

                @Override
                public void run() {
                    if (player != null && player.isPlaying()) {
                        fadeIn(fadeInDurationMs, volume);
                    } else if (attempts < 10) { // Try for 5 seconds (10 * 500ms)
                        attempts++;
                        handler.postDelayed(this, 500);
                    }
                }
            },
            500
        );
    }

    private void fadeIn(double fadeInDurationMs, float volume) {
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
                        logger.verbose("Fade in complete at time " + getCurrentPosition());
                        return;
                    }
                    final float previousCurrentVolume = currentVolume;
                    currentVolume += fadeStep;
                    final float resolvedTargetVolume = Math.min(currentVolume, targetVolume);
                    Log.d(
                        TAG,
                        "Fade in step: from " + previousCurrentVolume + " to " + currentVolume + " to target " + resolvedTargetVolume
                    );
                    owner
                        .getActivity()
                        .runOnUiThread(() -> {
                            if (player != null && player.isPlaying()) {
                                player.setVolume(resolvedTargetVolume);
                            }
                        });
                }
            },
            0,
            FADE_DELAY_MS,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void stopWithFade(double fadeOutDurationMs, boolean asPause) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (player != null && player.isPlaying()) {
                    fadeOut(fadeOutDurationMs, asPause);
                }
            });
    }

    private void fadeOut(double fadeOutDurationMs, boolean asPause) {
        cancelFade();
        fadeState = FadeState.FADE_OUT;

        final float initialVolume = player.getVolume();
        final int steps = Math.max(1, (int) (fadeOutDurationMs / FADE_DELAY_MS));
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
                        try {
                            if (asPause) {
                                player.setPlayWhenReady(false);
                                logger.verbose("Faded out to pause at time " + getCurrentPosition());
                            } else {
                                stop();
                                logger.verbose("Faded out to stop at time " + getCurrentPosition());
                            }
                        } catch (Exception e) {
                            logger.error("Error stopping playback", e);
                        }
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

    private void fadeTo(double fadeDurationMs, float targetVolume) {
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
                        logger.verbose("Fade to complete at time " + getCurrentPosition());
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
                        logger.debug("Fade to step " + currentStep + ": volume set to " + currentVolume);
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

    private void cancelFade() {
        if (fadeTask != null && !fadeTask.isCancelled()) {
            fadeTask.cancel(true);
        }
        fadeState = FadeState.NONE;
        fadeTask = null;
    }

    @Override
    public void setRate(float rate) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                logger.debug("Setting playback rate to: " + rate);
                player.setPlaybackParameters(new PlaybackParameters(rate));
            });
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
                    if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                        startTimeUpdateLoop();
                    } else {
                        // Check again in 100ms
                        currentTimeHandler.postDelayed(this, 100);
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

    @Override
    void stopCurrentTimeUpdates() {
        logger.debug("Stopping play timer updates");
        if (currentTimeHandler != null) {
            currentTimeHandler.removeCallbacks(currentTimeRunnable);
            currentTimeHandler = null;
        }
    }
}
