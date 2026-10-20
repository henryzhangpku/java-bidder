package bidder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * Pacing controller: spreads each budget evenly over the "day". Runs off the hot path every second
 * (on a ScheduledExecutorService), compares spend to the target line, and adjusts a per-campaign
 * throttle in [0, 1]. The bid path only reads that number: no control loop inside a bid.
 */
final class Pacer {

    private final Map<String, Long> budgets;
    private final ToLongFunction<String> spend; // campaign id -> spend so far (expected, in micros)
    private final Instant dayStart;
    private final Duration dayLength;
    private final ConcurrentHashMap<String, Double> throttle = new ConcurrentHashMap<>();

    Pacer(Map<String, Long> budgets, ToLongFunction<String> spend, Instant dayStart, Duration dayLength) {
        this.budgets = Map.copyOf(budgets);
        this.spend = spend;
        this.dayStart = dayStart;
        this.dayLength = dayLength;
    }

    /** Hot path: one map read. 1.0 = bid on every matching request, 0.0 = stop. */
    double throttle(String campaignId) {
        return throttle.getOrDefault(campaignId, 1.0);
    }

    /**
     * One control step. AIMD, like TCP congestion control: ahead of the target line -> cut the
     * throttle hard (x0.7); behind it -> open up gently (+0.1). Budget gone -> 0.
     */
    void step(Instant now) {
        double elapsed = (double) Duration.between(dayStart, now).toMillis() / dayLength.toMillis();
        double fractionOfDay = Math.min(1.0, Math.max(0.0, elapsed));
        budgets.forEach((id, budget) -> {
            long spent = spend.applyAsLong(id);
            double target = budget * fractionOfDay;
            double current = throttle(id);
            double next;
            if (spent >= budget) next = 0.0;
            else if (spent > target) next = current * 0.7;
            else next = Math.min(1.0, current + 0.1);
            throttle.put(id, Math.max(0.0, next));
        });
    }
}
