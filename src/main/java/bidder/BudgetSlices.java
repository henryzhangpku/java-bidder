package bidder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * One bidder box's local wallet: the budget slices it has leased, spent lock-free on the bid path.
 * Spend can never exceed what was leased, and leases never exceed the budget, so the most the
 * central service cannot see at any moment (the overspend bound) is the sum of outstanding slices.
 */
final class BudgetSlices {

    private final BudgetService service;
    private final long sliceMicros;
    private final ConcurrentHashMap<String, AtomicLong> wallet = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> committed = new ConcurrentHashMap<>();

    BudgetSlices(BudgetService service, long sliceMicros) {
        this.service = service;
        this.sliceMicros = sliceMicros;
    }

    /**
     * Reserve {@code amount} for a bid. First price means if we win we pay our bid, so the bid is
     * the worst-case spend and we reserve all of it. Returns false when the budget is exhausted.
     */
    boolean tryCommit(String campaignId, long amount) {
        AtomicLong local = wallet.computeIfAbsent(campaignId, id -> new AtomicLong());
        while (true) {
            long cur = local.get();
            if (cur >= amount) {
                if (local.compareAndSet(cur, cur - amount)) {
                    committedFor(campaignId).add(amount);
                    return true;
                }
                continue; // lost a race with another bid: re-read
            }
            // Slice too small: lease another. Several threads may lease at once; that moves extra
            // money to this box (less accurate), but can never create money (no overspend).
            long granted = service.lease(campaignId, Math.max(sliceMicros, amount));
            if (granted == 0) return false;
            local.addAndGet(granted);
        }
    }

    /** Give back part of a reservation: the auction cleared below our bid, or we lost. */
    void refund(String campaignId, long micros) {
        if (micros <= 0) return;
        wallet.computeIfAbsent(campaignId, id -> new AtomicLong()).addAndGet(micros);
        committedFor(campaignId).add(-micros);
    }

    /** Return the unspent slice to the service, e.g. on shutdown or when a lease expires. */
    void returnUnused(String campaignId) {
        AtomicLong local = wallet.get(campaignId);
        if (local != null) service.giveBack(campaignId, local.getAndSet(0));
    }

    long outstanding(String campaignId) {
        AtomicLong local = wallet.get(campaignId);
        return local == null ? 0 : local.get();
    }

    long committed(String campaignId) {
        LongAdder c = committed.get(campaignId);
        return c == null ? 0 : c.sum();
    }

    private LongAdder committedFor(String campaignId) {
        return committed.computeIfAbsent(campaignId, id -> new LongAdder());
    }
}
