package bidder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The central budget authority. It never sees individual bids; it only hands out slices
 * ("you may spend $0.50 of campaign c1") and takes back unused ones. In production this is
 * a separate service per region; here it is an object so the demo runs in one process.
 */
final class BudgetService {

    private final Map<String, Long> budgets;
    private final ConcurrentHashMap<String, AtomicLong> unleased = new ConcurrentHashMap<>();

    BudgetService(Map<String, Long> budgetsMicros) {
        this.budgets = Map.copyOf(budgetsMicros);
        budgets.forEach((id, micros) -> unleased.put(id, new AtomicLong(micros)));
    }

    /**
     * Grant up to {@code want} micros, or 0 when the budget is gone. The compare-and-set loop is
     * the lock-free version of "with lock: grant = min(want, left); left -= grant".
     */
    long lease(String campaignId, long want) {
        AtomicLong left = unleased.get(campaignId);
        if (left == null) return 0;
        while (true) {
            long cur = left.get();
            long grant = Math.min(want, cur);
            if (grant <= 0) return 0;
            if (left.compareAndSet(cur, cur - grant)) return grant;
            // another thread changed `left` between our read and our write: re-read and retry
        }
    }

    void giveBack(String campaignId, long micros) {
        if (micros > 0) unleased.get(campaignId).addAndGet(micros);
    }

    long budget(String campaignId) { return budgets.getOrDefault(campaignId, 0L); }

    long leased(String campaignId) {
        AtomicLong left = unleased.get(campaignId);
        return left == null ? 0 : budget(campaignId) - left.get();
    }

    long unleased(String campaignId) {
        AtomicLong left = unleased.get(campaignId);
        return left == null ? 0 : left.get();
    }
}
