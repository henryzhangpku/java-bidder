package bidder;

import bidder.BidResponse.Bid;
import bidder.BidResponse.NoBid;
import bidder.BidResponse.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The bid pipeline: candidates -> pacing -> frequency cap -> score and shade -> reserve budget.
 * Every request runs under a hard deadline; past it we answer NoBid(TIMEOUT), because a late bid
 * is a lost auction anyway. Also settles win notices, exactly once per auction id.
 */
final class Bidder implements AutoCloseable {

    /** Tunables in one place. A record makes a fine config object. */
    record Config(Duration deadline, double shadeAlpha, double slowFraction, Duration lossTimeout) {}

    enum WinResult { RECORDED, DUPLICATE, UNKNOWN_AUCTION, LATE }

    private record Offer(Campaign campaign, long price, double score) {}

    /** A bid we made and are waiting to hear about. Holds what we need to settle or release it. */
    private record Pending(String campaignId, String deviceId, long priceMicros, long createdMillis) {}

    private final CampaignIndex index;
    private final FrequencyCap cap;
    private final BudgetSlices budget;
    private final Pacer pacer;
    private final Stats stats;
    private final Clock clock;
    private final Config config;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<String> settled = ConcurrentHashMap.newKeySet(); // demo: unbounded; prod: TTL store

    Bidder(CampaignIndex index, FrequencyCap cap, BudgetSlices budget, Pacer pacer,
           Stats stats, Clock clock, Config config) {
        this.index = index;
        this.cap = cap;
        this.budget = budget;
        this.pacer = pacer;
        this.stats = stats;
        this.clock = clock;
        this.config = config;
    }

    /** Entry point for POST /bid. Never throws for a slow pipeline: it times out to a NoBid. */
    BidResponse bid(BidRequest req) {
        long start = System.nanoTime();
        BidResponse response = CompletableFuture
                .supplyAsync(() -> decide(req), workers) // a fresh virtual thread: cheap, ~1 KB
                .completeOnTimeout(new NoBid(req.auctionId(), Reason.TIMEOUT),
                        config.deadline().toMillis(), TimeUnit.MILLISECONDS)
                .join();
        stats.record(response, System.nanoTime() - start);
        return response;
    }

    private BidResponse decide(BidRequest req) {
        simulateSlowDependency();
        int hour = LocalTime.now(clock).getHour();

        List<Campaign> candidates = index.candidates(req, hour);
        if (candidates.isEmpty()) return new NoBid(req.auctionId(), Reason.NO_MATCH);

        var rnd = ThreadLocalRandom.current();
        List<Campaign> paced = candidates.stream()
                .filter(c -> rnd.nextDouble() < pacer.throttle(c.id()))
                .toList();
        if (paced.isEmpty()) return new NoBid(req.auctionId(), Reason.PACING);

        List<Campaign> uncapped = paced.stream()
                .filter(c -> cap.allows(req.deviceId(), c))
                .toList();
        if (uncapped.isEmpty()) return new NoBid(req.auctionId(), Reason.FREQ_CAP);

        List<Offer> offers = new ArrayList<>();
        for (Campaign c : uncapped) {
            double p = Pricing.score(c, req);
            Pricing.shade(Pricing.valueMicros(c, p), req.floorMicros(), config.shadeAlpha())
                    .ifPresent(price -> offers.add(new Offer(c, price, p)));
        }
        if (offers.isEmpty()) return new NoBid(req.auctionId(), Reason.BELOW_FLOOR);

        // Best price first; if its budget is gone, fall through to the next one.
        offers.sort(Comparator.comparingLong(Offer::price).reversed());
        for (Offer o : offers) {
            if (budget.tryCommit(o.campaign().id(), o.price())) {
                // If the deadline already fired, this bid is discarded upstream; the pending
                // entry then expires as a loss and the reservation is released. No leak.
                pending.put(req.auctionId(),
                        new Pending(o.campaign().id(), req.deviceId(), o.price(), clock.millis()));
                return new Bid(req.auctionId(), o.campaign().id(), o.price(), o.score());
            }
        }
        return new NoBid(req.auctionId(), Reason.BUDGET);
    }

    /** Stand-in for a slow remote call or a GC pause, so the deadline path is visible in the demo. */
    private void simulateSlowDependency() {
        if (ThreadLocalRandom.current().nextDouble() >= config.slowFraction()) return;
        try {
            Thread.sleep(config.deadline().multipliedBy(2));
        } catch (InterruptedException e) { // checked exception: the compiler forces us to handle it
            Thread.currentThread().interrupt(); // restore the flag; never swallow an interrupt
        }
    }

    /**
     * POST /win. Exchanges retry, so the same notice can arrive twice, even concurrently.
     * settled.add() is atomic and returns false for the second caller: spend is booked once.
     */
    WinResult win(String auctionId, String campaignId, long clearingMicros) {
        Pending p = pending.get(auctionId);
        if (p == null) return settled.contains(auctionId) ? duplicate() : WinResult.UNKNOWN_AUCTION;
        if (!p.campaignId().equals(campaignId)) return WinResult.UNKNOWN_AUCTION;
        if (!settled.add(auctionId)) return duplicate();
        if (!pending.remove(auctionId, p)) return WinResult.LATE; // expired as a loss a moment ago

        long clearing = Math.min(clearingMicros, p.priceMicros()); // first price: never above our bid
        budget.refund(campaignId, p.priceMicros() - clearing);
        cap.record(p.deviceId(), campaignId);
        stats.win(campaignId, clearing);
        return WinResult.RECORDED;
    }

    private WinResult duplicate() {
        stats.duplicateWins.increment();
        return WinResult.DUPLICATE;
    }

    /** No win notice within lossTimeout means we lost: release the reservation back to the slice. */
    int expireLosses() {
        long cutoff = clock.millis() - config.lossTimeout().toMillis();
        int released = 0;
        for (var e : pending.entrySet()) {
            Pending p = e.getValue();
            if (p.createdMillis() <= cutoff && pending.remove(e.getKey(), p)) {
                budget.refund(p.campaignId(), p.priceMicros());
                released++;
            }
        }
        return released;
    }

    int pendingCount() { return pending.size(); }

    @Override
    public void close() { workers.close(); } // ExecutorService is AutoCloseable since Java 19
}
