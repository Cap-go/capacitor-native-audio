package ee.forgr.audio;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioAssetRateUnitTest {

    @Test
    public void setRate_clampsAndStoresPlaybackRate() throws Exception {
        NativeAudio owner = mock(NativeAudio.class);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        when(owner.getActivity()).thenReturn(activity);

        File wavFile = File.createTempFile("native-audio-rate", ".wav");
        writeSilentWav(wavFile);

        ParcelFileDescriptor parcel = ParcelFileDescriptor.open(wavFile, ParcelFileDescriptor.MODE_READ_ONLY);
        AssetFileDescriptor descriptor = new AssetFileDescriptor(parcel, 0, wavFile.length());

        AudioAsset asset = new AudioAsset(owner, "rate-test", descriptor, 1, 1f);
        asset.setRate(8f);
        assertEquals(4f, asset.getPlaybackRate(), 0.001f);

        asset.setRate(0.1f);
        assertEquals(0.25f, asset.getPlaybackRate(), 0.001f);
    }

    private static void writeSilentWav(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] header = new byte[44];
            header[0] = 'R';
            header[1] = 'I';
            header[2] = 'F';
            header[3] = 'F';
            header[8] = 'W';
            header[9] = 'A';
            header[10] = 'V';
            header[11] = 'E';
            header[12] = 'f';
            header[13] = 'm';
            header[14] = 't';
            header[15] = ' ';
            header[36] = 'd';
            header[37] = 'a';
            header[38] = 't';
            header[39] = 'a';
            output.write(header);
        }
    }
}
