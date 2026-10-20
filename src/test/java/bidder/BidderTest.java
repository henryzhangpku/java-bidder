package bidder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bidder.BidResponse.Bid;
import bidder.BidResponse.NoBid;
import bidder.BidResponse.Reason;
import bidder.Bidder.WinResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class BidderTest {

    private final TestClock clock = TestClock.atNoonPacific();
    private final Campaign sports =
            new Campaign("c1", "Sports", 1_000_000, 20, Set.of("US-CA"), Set.of("sports"), 0, 24, 15, 2);
    private final Stats stats = new Stats();
    private final BudgetSlices slices = new BudgetSlices(new BudgetService(Map.of("c1", 1_000_000L)), 100_000);

    /** Functional tests get a generous deadline: a cold JVM (no JIT yet) can take >20 ms on its first bid. */
    private Bidder bidder(double slowFraction) {
        return bidder(slowFraction, Duration.ofSeconds(2));
    }

    private Bidder bidder(double slowFraction, Duration deadline) {
        var index = new CampaignIndex();
        index.publish(List.of(sports));
        var pacer = new Pacer(Map.of("c1", 1_000_000L), slices::committed, clock.instant(), Duration.ofDays(1));
        var cap = new FrequencyCap(Duration.ofDays(1), clock);
        var config = new Bidder.Config(deadline, 0.5, slowFraction, Duration.ofSeconds(5));
        return new Bidder(index, cap, slices, pacer, stats, clock, config);
    }

    private static BidRequest request(String auctionId) {
        return new BidRequest(auctionId, "dev-1", "app", 30, "US-CA", Set.of("sports"), 2_000);
    }

    @Test
    void bidsThenWinIsIdempotent() {
        try (var b = bidder(0)) {
            var bid = assertInstanceOf(Bid.class, b.bid(request("a1")));
            assertEquals(WinResult.RECORDED, b.win("a1", "c1", bid.priceMicros() - 500));
            assertEquals(WinResult.DUPLICATE, b.win("a1", "c1", bid.priceMicros() - 500));
            assertEquals(1, stats.wins.sum());
            assertEquals(bid.priceMicros() - 500, stats.spendMicros.sum());
            assertEquals(bid.priceMicros() - 500, slices.committed("c1"), "unused 500 refunded");
        }
    }

    @Test
    void concurrentDuplicateWinsChargeExactlyOnce() throws Exception {
        try (var b = bidder(0)) {
            var bid = assertInstanceOf(Bid.class, b.bid(request("a1")));
            List<Future<WinResult>> results = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Callable<WinResult> retry = () -> b.win("a1", "c1", bid.priceMicros());
                for (int i = 0; i < 200; i++) results.add(pool.submit(retry));
            }
            long recorded = 0;
            for (var f : results) if (f.get() == WinResult.RECORDED) recorded++;
            assertEquals(1, recorded);
            assertEquals(bid.priceMicros(), stats.spendMicros.sum());
        }
    }

    @Test
    void unknownAuctionIsRejected() {
        try (var b = bidder(0)) {
            assertEquals(WinResult.UNKNOWN_AUCTION, b.win("never-bid", "c1", 1_000));
        }
    }

    @Test
    void frequencyCapCountsWinsNotBids() {
        try (var b = bidder(0)) {
            for (int i = 0; i < 2; i++) {
                var bid = assertInstanceOf(Bid.class, b.bid(request("a" + i)));
                b.win(bid.auctionId(), "c1", bid.priceMicros());
            }
            var third = assertInstanceOf(NoBid.class, b.bid(request("a3")));
            assertEquals(Reason.FREQ_CAP, third.reason());
        }
    }

    @Test
    void lostAuctionsReleaseTheirReservation() {
        try (var b = bidder(0)) {
            assertInstanceOf(Bid.class, b.bid(request("a1")));
            assertTrue(slices.committed("c1") > 0);
            clock.advance(Duration.ofSeconds(6));
            assertEquals(1, b.expireLosses());
            assertEquals(0, slices.committed("c1"));
            assertEquals(WinResult.UNKNOWN_AUCTION, b.win("a1", "c1", 1_000), "too late to win");
        }
    }

    @Test
    void slowPipelineTimesOutToNoBid() {
        try (var b = bidder(1.0, Duration.ofMillis(20))) { // every request stalls for 2x the deadline
            long start = System.nanoTime();
            var resp = assertInstanceOf(NoBid.class, b.bid(request("slow")));
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(Reason.TIMEOUT, resp.reason());
            assertTrue(tookMs < 35, "answered in " + tookMs + " ms, deadline 20 ms");
        }
    }

    @Test
    void noMatchOutsideTargeting() {
        try (var b = bidder(0)) {
            var req = new BidRequest("a1", "dev-1", "app", 30, "US-NY", Set.of("sports"), 2_000);
            assertEquals(Reason.NO_MATCH, assertInstanceOf(NoBid.class, b.bid(req)).reason());
        }
    }
}
