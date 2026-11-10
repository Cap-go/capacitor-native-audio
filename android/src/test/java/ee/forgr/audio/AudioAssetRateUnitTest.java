package ee.forgr.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioAssetRateUnitTest {

    @Test
    public void clampPlaybackRate_enforcesSupportedRange() {
        assertEquals(0.25f, AudioAsset.clampPlaybackRate(0.1f), 0.001f);
        assertEquals(4f, AudioAsset.clampPlaybackRate(8f), 0.001f);
        assertEquals(1.25f, AudioAsset.clampPlaybackRate(1.25f), 0.001f);
    }
}
