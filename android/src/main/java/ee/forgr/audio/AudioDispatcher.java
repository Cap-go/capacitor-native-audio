package ee.forgr.audio;

import static ee.forgr.audio.Constant.INVALID;
import static ee.forgr.audio.Constant.LOOPING;
import static ee.forgr.audio.Constant.PAUSE;
import static ee.forgr.audio.Constant.PENDING_LOOP;
import static ee.forgr.audio.Constant.PENDING_PLAY;
import static ee.forgr.audio.Constant.PLAYING;
import static ee.forgr.audio.Constant.PREPARED;

import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;

@UnstableApi
public class AudioDispatcher
    implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnSeekCompleteListener
{

    private final String TAG = "AudioDispatcher";
    private final MediaPlayer mediaPlayer;
    private int mediaState;
    private AudioAsset owner;

    private float currentVolume = 1.0f;

    public AudioDispatcher(AssetFileDescriptor assetFileDescriptor, float volume) throws Exception {
        mediaState = INVALID;

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setDataSource(
            assetFileDescriptor.getFileDescriptor(),
            assetFileDescriptor.getStartOffset(),
            assetFileDescriptor.getLength()
        );
        mediaPlayer.setOnSeekCompleteListener(this);
        mediaPlayer.setAudioAttributes(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        );
        mediaPlayer.setVolume(volume, volume);
        currentVolume = volume;
        mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(1.0f));
        mediaPlayer.prepare();
    }

    public void setOwner(AudioAsset asset) {
        owner = asset;
    }

    public double getDuration() {
        return mediaPlayer.getDuration() / 1000.0;
    }

    public void setCurrentPosition(double time) {
        if (mediaState == PLAYING || mediaState == PAUSE) {
            mediaPlayer.seekTo((int) (time * 1000));
        }
    }

    public double getCurrentPosition() {
        return mediaPlayer.getCurrentPosition() / 1000.0;
    }

    public void play(Double time) throws Exception {
        invokePlay(time);
    }

    public boolean pause() throws Exception {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            mediaState = PAUSE;
            return true;
        }

        return false;
    }

    public void resume() throws Exception {
        mediaPlayer.start();
    }

    public void stop() throws Exception {
        if (mediaPlayer.isPlaying()) {
            mediaState = INVALID;
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
        }
    }

    public void setVolume(float volume) throws Exception {
        mediaPlayer.setVolume(volume, volume);
        currentVolume = volume;
    }

    public float getVolume() {
        return currentVolume;
    }

    public void setRate(float rate) throws Exception {
        mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(rate));
    }

    public void loop() throws Exception {
        mediaPlayer.setLooping(true);
        mediaPlayer.start();
    }

    public void unload() throws Exception {
        this.stop();
        mediaPlayer.release();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        try {
            if (mediaState != LOOPING) {
                this.mediaState = INVALID;

                this.stop();

                if (this.owner != null) {
                    this.owner.notifyCompletion();
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Caught exception while listening for onCompletion: " + ex.getLocalizedMessage());
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        try {
            if (mediaState == PENDING_PLAY) {
                mediaPlayer.setLooping(false);
            } else if (mediaState == PENDING_LOOP) {
                mediaPlayer.setLooping(true);
            } else {
                mediaState = PREPARED;
            }
        } catch (Exception ex) {
            Log.d(TAG, "Caught exception while listening for onPrepared: " + ex.getLocalizedMessage());
        }
    }

    private void seek(Double time) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaPlayer.seekTo((int) (time * 1000), MediaPlayer.SEEK_NEXT_SYNC);
        } else {
            mediaPlayer.seekTo((int) (time * 1000));
        }
    }

    private void invokePlay(Double time) {
        try {
            boolean playing = mediaPlayer.isPlaying();

            if (playing) {
                mediaPlayer.pause();
                mediaPlayer.setLooping(false);
                mediaState = PENDING_PLAY;
                seek(time);
            } else {
                if (mediaState == PREPARED) {
                    mediaState = (PENDING_PLAY);
                    onPrepared(mediaPlayer);
                    seek(time);
                } else {
                    mediaState = (PENDING_PLAY);
                    mediaPlayer.setLooping(false);
                    seek(time);
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Caught exception while invoking audio: " + ex.getLocalizedMessage());
        }
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {
        if (mediaState == PENDING_PLAY || mediaState == PENDING_LOOP) {
            Log.w("AudioDispatcher", "play " + mediaState);
            mediaPlayer.start();
            mediaState = PLAYING;
        }
    }

    public boolean isPlaying() {
        boolean playing = false;
        try {
            playing = mediaPlayer.isPlaying();
        } catch (IllegalStateException ex) {
            Log.v(TAG, "Caught exception while checking if audio is playing: " + ex.getLocalizedMessage());
        }
        return playing;
    }

    public boolean isPaused() {
        return mediaState == PAUSE;
    }
}
