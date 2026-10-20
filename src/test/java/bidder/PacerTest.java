package bidder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PacerTest {

    private final Instant start = Instant.parse("2026-10-20T00:00:00Z");
    private final Map<String, Long> spend = new HashMap<>();
    private final Pacer pacer = new Pacer(Map.of("c1", 1_000L), id -> spend.getOrDefault(id, 0L),
            start, Duration.ofSeconds(100));

    @Test
    void throttlesWhenAheadOfTheLine() {
        spend.put("c1", 500L); // half the budget at 10% of the day
        pacer.step(start.plusSeconds(10));
        assertEquals(0.7, pacer.throttle("c1"), 1e-9);
        pacer.step(start.plusSeconds(11));
        assertEquals(0.49, pacer.throttle("c1"), 1e-9);
    }

    @Test
    void reopensWhenBehindTheLine() {
        spend.put("c1", 500L);
        pacer.step(start.plusSeconds(10));
        pacer.step(start.plusSeconds(11));
        assertTrue(pacer.throttle("c1") < 0.5);

        // Day moves on to 80%; spend is still 50%: behind, so the throttle climbs back to 1.0.
        for (int s = 80; s < 90; s++) pacer.step(start.plusSeconds(s));
        assertEquals(1.0, pacer.throttle("c1"), 1e-9);
    }

    @Test
    void stopsWhenBudgetIsSpent() {
        spend.put("c1", 1_000L);
        pacer.step(start.plusSeconds(50));
        assertEquals(0.0, pacer.throttle("c1"));
    }

    @Test
    void unknownCampaignDefaultsToFullThrottle() {
        assertEquals(1.0, pacer.throttle("nope"));
    }
}
