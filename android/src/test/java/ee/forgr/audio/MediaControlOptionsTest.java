package ee.forgr.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MediaControlOptionsTest {

    @Test
    public void normalizeSeekSeconds_defaultsWhenMissingOrInvalid() {
        assertEquals(15d, MediaControlOptions.normalizeSeekSeconds(null), 0.001d);
        assertEquals(15d, MediaControlOptions.normalizeSeekSeconds(0d), 0.001d);
        assertEquals(15d, MediaControlOptions.normalizeSeekSeconds(-5d), 0.001d);
        assertEquals(15d, MediaControlOptions.normalizeSeekSeconds(Double.NaN), 0.001d);
        assertEquals(30d, MediaControlOptions.normalizeSeekSeconds(30d), 0.001d);
    }

    @Test
    public void seekActionTitle_usesWholeSeconds() {
        assertEquals("Rewind 15 seconds", MediaControlOptions.seekActionTitle("Rewind", 15d));
        assertEquals("Fast forward 30 seconds", MediaControlOptions.seekActionTitle("Fast forward", 30d));
    }

    @Test
    public void compactActionIndices_keepsSeekLayoutWithoutQueue() {
        int[] compact = MediaControlOptions.compactActionIndices(false);
        assertEquals(0, compact[0]);
        assertEquals(1, compact[1]);
        assertEquals(2, compact[2]);
    }

    @Test
    public void compactActionIndices_putsQueueTransportOnTheEnds() {
        int[] compact = MediaControlOptions.compactActionIndices(true);
        assertEquals(0, compact[0]);
        assertEquals(2, compact[1]);
        assertEquals(4, compact[2]);
    }

    @Test
    public void normalizeDrawableName_acceptsDrawableResourceNames() {
        assertEquals("ic_notification", MediaControlOptions.normalizeDrawableName("ic_notification"));
        assertEquals("ic_notification", MediaControlOptions.normalizeDrawableName("@drawable/ic_notification"));
        assertEquals("ic_notification", MediaControlOptions.normalizeDrawableName("res/drawable/ic_notification.png"));
        assertNull(MediaControlOptions.normalizeDrawableName("../ic_notification"));
        assertNull(MediaControlOptions.normalizeDrawableName(""));
    }
}
