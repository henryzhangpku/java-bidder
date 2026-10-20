package bidder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrequencyCapTest {

    private final Campaign threePerDay =
            new Campaign("c1", "test", 1_000_000, 10, Set.of("US-CA"), Set.of(), 0, 24, 15, 3);

    @Test
    void blocksAfterCapAndReopensWhenWindowSlides() {
        var clock = TestClock.atNoonPacific();
        var cap = new FrequencyCap(Duration.ofDays(1), clock);

        for (int i = 0; i < 3; i++) {
            assertTrue(cap.allows("dev-1", threePerDay));
            cap.record("dev-1", "c1");
            clock.advance(Duration.ofHours(1));
        }
        assertFalse(cap.allows("dev-1", threePerDay), "4th ad in 24h must be blocked");
        assertTrue(cap.allows("dev-2", threePerDay), "other devices are independent");

        clock.advance(Duration.ofHours(22)); // the first impression is now 25h old
        assertTrue(cap.allows("dev-1", threePerDay), "sliding window: oldest hit expired");
    }

    @Test
    void sweepDropsFullyExpiredKeys() {
        var clock = TestClock.atNoonPacific();
        var cap = new FrequencyCap(Duration.ofDays(1), clock);
        cap.record("dev-1", "c1");
        cap.record("dev-2", "c1");
        clock.advance(Duration.ofHours(12));
        cap.record("dev-2", "c1");

        clock.advance(Duration.ofHours(13)); // dev-1 last seen 25h ago, dev-2 13h ago
        assertEquals(1, cap.sweep());
        assertEquals(1, cap.size());
    }
}
