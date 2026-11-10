package ee.forgr.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioManager;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QueueSnapshotRoutingUnitTest {

    @Test
    public void queueItemIsValid_requiresNonEmptyAssetIdAndAssetPath() {
        assertFalse(NativeAudio.queueItemIsValid(null, "https://example.com/a.mp3"));
        assertFalse(NativeAudio.queueItemIsValid("ep-1", null));
        assertFalse(NativeAudio.queueItemIsValid("ep-1", ""));
        assertFalse(NativeAudio.queueItemIsValid("ep-1", "null"));
        assertTrue(NativeAudio.queueItemIsValid("ep-1", "https://example.com/a.mp3"));
    }

    @Test
    public void playbackStateLabel_mapsTrackedStatesForSnapshots() {
        assertEquals("playing", NativeAudio.playbackStateLabel(PlaybackStateCompat.STATE_PLAYING));
        assertEquals("paused", NativeAudio.playbackStateLabel(PlaybackStateCompat.STATE_PAUSED));
        assertEquals("stopped", NativeAudio.playbackStateLabel(PlaybackStateCompat.STATE_STOPPED));
        assertEquals("stopped", NativeAudio.playbackStateLabel(PlaybackStateCompat.STATE_NONE));
    }

    @Test
    public void resolveAudioRoute_reportsSpeakerWhenSpeakerphoneEnabled() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        NativeAudio plugin = new NativeAudio();
        plugin.setContext(context);

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Field audioManagerField = NativeAudio.class.getDeclaredField("audioManager");
        audioManagerField.setAccessible(true);
        audioManagerField.set(plugin, audioManager);

        audioManager.setSpeakerphoneOn(true);
        Method resolveAudioRoute = NativeAudio.class.getDeclaredMethod("resolveAudioRoute");
        resolveAudioRoute.setAccessible(true);
        assertEquals("speaker", resolveAudioRoute.invoke(plugin));
    }
}
