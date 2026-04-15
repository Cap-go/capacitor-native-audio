package ee.forgr.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExampleUnitTest {

    @Test
    public void clampSeekPositionSeconds_respectsLowerAndUpperBounds() {
        assertEquals(0.0, NativeAudio.clampSeekPositionSeconds(5.0, 120.0, -15.0), 0.0001);
        assertEquals(120.0, NativeAudio.clampSeekPositionSeconds(110.0, 120.0, 15.0), 0.0001);
    }

    @Test
    public void clampSeekPositionSeconds_allowsForwardSeekWithoutKnownDuration() {
        assertEquals(35.0, NativeAudio.clampSeekPositionSeconds(20.0, 0.0, 15.0), 0.0001);
        assertEquals(0.0, NativeAudio.clampSeekPositionSeconds(3.0, 0.0, -15.0), 0.0001);
    }
}
