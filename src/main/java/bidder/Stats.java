package bidder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for /stats: requests, bids, no-bid reasons, wins, spend, and latency percentiles.
 * LongAdder is a counter that stays fast when many threads add at once (it shards internally).
 * Latency keeps the last 8,192 samples in a ring buffer; p50/p99 sort a copy on read.
 */
final class Stats {

    final LongAdder requests = new LongAdder();
    final LongAdder bids = new LongAdder();
    final LongAdder wins = new LongAdder();
    final LongAdder duplicateWins = new LongAdder();
    final LongAdder spendMicros = new LongAdder();

    private final ConcurrentHashMap<BidResponse.Reason, LongAdder> noBids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> wonSpendByCampaign = new ConcurrentHashMap<>();
    private final AtomicLongArray latencyNanos = new AtomicLongArray(8192);
    private final AtomicLong samples = new AtomicLong();

    void record(BidResponse response, long nanos) {
        requests.increment();
        switch (response) {
            case BidResponse.Bid b -> bids.increment();
            case BidResponse.NoBid n -> noBids.computeIfAbsent(n.reason(), r -> new LongAdder()).increment();
        }
        long i = samples.getAndIncrement();
        latencyNanos.set((int) (i % latencyNanos.length()), nanos);
    }

    void win(String campaignId, long clearingMicros) {
        wins.increment();
        spendMicros.add(clearingMicros);
        wonSpendByCampaign.computeIfAbsent(campaignId, id -> new LongAdder()).add(clearingMicros);
    }

    long wonSpend(String campaignId) {
        LongAdder a = wonSpendByCampaign.get(campaignId);
        return a == null ? 0 : a.sum();
    }

    /** {p50, p99} in milliseconds over the recent window. */
    double[] latencyMillis() {
        int n = (int) Math.min(samples.get(), latencyNanos.length());
        if (n == 0) return new double[] {0, 0};
        long[] copy = new long[n];
        for (int i = 0; i < n; i++) copy[i] = latencyNanos.get(i);
        Arrays.sort(copy);
        return new double[] {copy[n / 2] / 1e6, copy[Math.min(n - 1, (int) (n * 0.99))] / 1e6};
    }

    Map<String, Object> toJson() {
        var reasons = new LinkedHashMap<String, Object>();
        for (var r : BidResponse.Reason.values()) {
            LongAdder a = noBids.get(r);
            reasons.put(r.name(), a == null ? 0 : a.sum());
        }
        double[] lat = latencyMillis();
        var out = new LinkedHashMap<String, Object>();
        out.put("requests", requests.sum());
        out.put("bids", bids.sum());
        out.put("wins", wins.sum());
        out.put("duplicateWins", duplicateWins.sum());
        out.put("spendUsd", spendMicros.sum() / 1e6);
        out.put("noBidReasons", reasons);
        out.put("p50Ms", lat[0]);
        out.put("p99Ms", lat[1]);
        return out;
    }
}
