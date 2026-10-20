package bidder;

import java.util.OptionalLong;

/**
 * Scoring and pricing: a toy logistic model for "probability the ad is watched to completion",
 * value = base CPM x probability, then first-price shading. Money is a long count of micros
 * (millionths of a dollar) per impression, never a double: no rounding drift in budgets.
 */
final class Pricing {
    private Pricing() {}

    // Toy model weights. In production these come from an offline-trained model, refreshed by config.
    static final double BIAS = -0.5;
    static final double W_SEGMENT = 0.8;   // each matched audience segment
    static final double W_FIT = 1.0;       // creative fills the slot (15s ad in a 15s slot = 1.0)

    /** $1 CPM = $1 per 1,000 impressions = $0.001 per impression = 1,000 micros per impression. */
    static long cpmToMicros(double cpm) { return Math.round(cpm * 1_000); }

    static double microsToCpm(long micros) { return micros / 1_000.0; }

    /** Logistic regression: p = 1 / (1 + e^-z). Always in (0, 1). */
    static double score(Campaign c, BidRequest r) {
        double fit = (double) c.creativeSeconds() / r.slotSeconds();
        double z = BIAS + W_SEGMENT * c.matchedSegments(r) + W_FIT * fit;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** What one impression is worth to the advertiser, in micros. */
    static long valueMicros(Campaign c, double p) {
        return Math.round(cpmToMicros(c.baseCpm()) * p);
    }

    /**
     * First-price bid shading. In a first-price auction the winner pays its own bid, so bidding
     * the full value leaves no surplus. Bid a fraction alpha of the way from the floor to the value.
     * Empty when the impression is worth less than the floor: we cannot bid at all.
     */
    static OptionalLong shade(long valueMicros, long floorMicros, double alpha) {
        if (valueMicros < floorMicros) return OptionalLong.empty();
        return OptionalLong.of(floorMicros + Math.round((valueMicros - floorMicros) * alpha));
    }
}
