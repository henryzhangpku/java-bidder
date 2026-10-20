package bidder;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "At most N ads per device per campaign per day", as a sliding-window rate limiter.
 * Each key maps to an immutable array of impression times; updates replace the array inside
 * ConcurrentHashMap.compute, which is atomic per key, so readers never see a half-written value.
 */
final class FrequencyCap {

    private record Key(String deviceId, String campaignId) {} // records give equals/hashCode for free

    private final ConcurrentHashMap<Key, long[]> seen = new ConcurrentHashMap<>();
    private final Duration window;
    private final Clock clock;

    FrequencyCap(Duration window, Clock clock) {
        this.window = window;
        this.clock = clock;
    }

    /**
     * Bid-time check. Check-then-record is not atomic across the bid and the win, so two
     * concurrent bids can both pass: a small, measurable over-cap rate, accepted for latency.
     */
    boolean allows(String deviceId, Campaign c) {
        long[] times = seen.get(new Key(deviceId, c.id()));
        if (times == null) return true;
        long cutoff = clock.millis() - window.toMillis();
        return Arrays.stream(times).filter(t -> t > cutoff).count() < c.capPerDay();
    }

    /** Called on a win (an impression), not on a bid: we only count ads that actually showed. */
    void record(String deviceId, String campaignId) {
        long now = clock.millis();
        long cutoff = now - window.toMillis();
        seen.compute(new Key(deviceId, campaignId), (key, old) -> {
            long[] kept = old == null ? new long[0] : Arrays.stream(old).filter(t -> t > cutoff).toArray();
            long[] next = Arrays.copyOf(kept, kept.length + 1);
            next[kept.length] = now;
            return next;
        });
    }

    /** Expiry: drop keys whose newest impression has left the window. Returns how many were dropped. */
    int sweep() {
        long cutoff = clock.millis() - window.toMillis();
        int before = seen.size();
        seen.values().removeIf(times -> times[times.length - 1] <= cutoff);
        return before - seen.size();
    }

    int size() { return seen.size(); }
}
