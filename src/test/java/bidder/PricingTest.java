package bidder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingTest {

    @Test
    void shadedBidSitsBetweenFloorAndValue() {
        long value = 10_000, floor = 4_000;
        long bid = Pricing.shade(value, floor, 0.5).orElseThrow();
        assertEquals(7_000, bid);
        assertTrue(bid >= floor && bid <= value);
    }

    @Test
    void alphaOneBidsFullValueAndAlphaZeroBidsTheFloor() {
        assertEquals(10_000, Pricing.shade(10_000, 4_000, 1.0).getAsLong());
        assertEquals(4_000, Pricing.shade(10_000, 4_000, 0.0).getAsLong());
    }

    @Test
    void noBidWhenValueIsBelowFloor() {
        assertTrue(Pricing.shade(3_000, 4_000, 0.5).isEmpty());
    }

    @Test
    void cpmConversion() {
        assertEquals(5_000, Pricing.cpmToMicros(5.0)); // $5 CPM = half a cent per impression
        assertEquals(5.0, Pricing.microsToCpm(5_000));
    }

    @Test
    void scoreIsAProbabilityAndRisesWithSegmentMatches() {
        var c = new Campaign("c1", "t", 1, 10, Set.of("US-CA"), Set.of("sports", "news"), 0, 24, 15, 3);
        var none = new BidRequest("a", "d", "app", 30, "US-CA", Set.of(), 0);
        var two = new BidRequest("a", "d", "app", 30, "US-CA", Set.of("sports", "news"), 0);
        double p0 = Pricing.score(c, none), p2 = Pricing.score(c, two);
        assertTrue(p0 > 0 && p0 < 1 && p2 > 0 && p2 < 1);
        assertTrue(p2 > p0);
    }
}
