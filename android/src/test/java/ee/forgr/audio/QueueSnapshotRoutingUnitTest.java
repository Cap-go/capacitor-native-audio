package ee.forgr.audio;

import static ee.forgr.audio.Constant.ASSET_ID;
import static ee.forgr.audio.Constant.ASSET_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioManager;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
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
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Field audioManagerField = NativeAudio.class.getDeclaredField("audioManager");
        audioManagerField.setAccessible(true);
        audioManagerField.set(plugin, audioManager);

        audioManager.setSpeakerphoneOn(true);
        Method resolveAudioRoute = NativeAudio.class.getDeclaredMethod("resolveAudioRoute");
        resolveAudioRoute.setAccessible(true);
        assertEquals("speaker", resolveAudioRoute.invoke(plugin));
    }

    @Test
    public void resolveAudioRoute_reportsSpeakerByDefaultWithoutSpeakerphone() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        NativeAudio plugin = new NativeAudio();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Field audioManagerField = NativeAudio.class.getDeclaredField("audioManager");
        audioManagerField.setAccessible(true);
        audioManagerField.set(plugin, audioManager);

        audioManager.setSpeakerphoneOn(false);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        Method resolveAudioRoute = NativeAudio.class.getDeclaredMethod("resolveAudioRoute");
        resolveAudioRoute.setAccessible(true);
        assertEquals("speaker", resolveAudioRoute.invoke(plugin));
    }

    @Test
    public void resolveAudioRoute_reportsEarpieceInCommunicationMode() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        NativeAudio plugin = new NativeAudio();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Field audioManagerField = NativeAudio.class.getDeclaredField("audioManager");
        audioManagerField.setAccessible(true);
        audioManagerField.set(plugin, audioManager);

        audioManager.setSpeakerphoneOn(false);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        Method resolveAudioRoute = NativeAudio.class.getDeclaredMethod("resolveAudioRoute");
        resolveAudioRoute.setAccessible(true);
        assertEquals("earpiece", resolveAudioRoute.invoke(plugin));
    }

    @Test
    public void setQueueStaging_preloadFailureLeavesCommittedQueueUntouched() throws Exception {
        NativeAudio plugin = new NativeAudio();
        Field itemsField = NativeAudio.class.getDeclaredField("playbackQueueItems");
        Field indexField = NativeAudio.class.getDeclaredField("playbackQueueIndex");
        itemsField.setAccessible(true);
        indexField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<JSObject> items = (List<JSObject>) itemsField.get(plugin);
        JSObject kept = new JSObject();
        kept.put(ASSET_ID, "kept");
        kept.put(ASSET_PATH, "https://example.com/kept.mp3");
        items.add(kept);
        indexField.setInt(plugin, 0);

        JSObject failingItem = new JSObject();
        failingItem.put(ASSET_ID, "fail-preload");
        failingItem.put(ASSET_PATH, "__unit_test_missing_asset__");
        failingItem.put("isUrl", true);

        Method preloadQueueItem = NativeAudio.class.getDeclaredMethod("preloadQueueItem", JSObject.class);
        preloadQueueItem.setAccessible(true);
        boolean preloadFailed = false;
        try {
            preloadQueueItem.invoke(plugin, failingItem);
        } catch (InvocationTargetException e) {
            preloadFailed = true;
        }
        assertTrue(preloadFailed);
        assertEquals(1, items.size());
        assertEquals("kept", items.get(0).getString(ASSET_ID));
        assertEquals(0, indexField.getInt(plugin));
    }
}
