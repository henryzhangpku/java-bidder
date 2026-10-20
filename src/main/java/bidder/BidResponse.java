package bidder;

import java.util.Map;

/**
 * The bidder's answer: either a Bid or a NoBid with a reason. A sealed interface lists every
 * allowed subtype, so a switch over it is checked for exhaustiveness by the compiler
 * (Python's closest match: a Union type plus match/case, but without the compile-time check).
 */
sealed interface BidResponse permits BidResponse.Bid, BidResponse.NoBid {

    String auctionId();

    record Bid(String auctionId, String campaignId, long priceMicros, double score) implements BidResponse {}

    record NoBid(String auctionId, Reason reason) implements BidResponse {}

    /** Why we passed. Counting these is the first thing you look at when win rate drops. */
    enum Reason { NO_MATCH, PACING, FREQ_CAP, BELOW_FLOOR, BUDGET, TIMEOUT }

    default Map<String, Object> toJson() {
        return switch (this) { // no default branch needed: the compiler knows the full list
            case Bid b -> Map.of(
                    "auctionId", b.auctionId(), "bid", true, "campaignId", b.campaignId(),
                    "priceCpm", Pricing.microsToCpm(b.priceMicros()), "score", b.score());
            case NoBid n -> Map.of("auctionId", n.auctionId(), "bid", false, "reason", n.reason().name());
        };
    }
}
