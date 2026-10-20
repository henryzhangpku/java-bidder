package bidder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the test can move forward by hand. Injecting Clock is how Java code avoids freezegun. */
final class TestClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    TestClock(Instant start, ZoneId zone) { this.now = start; this.zone = zone; }

    static TestClock atNoonPacific() {
        return new TestClock(Instant.parse("2026-10-20T19:00:00Z"), ZoneId.of("America/Los_Angeles"));
    }

    void advance(Duration d) { now = now.plus(d); }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new TestClock(now, z); }
}
