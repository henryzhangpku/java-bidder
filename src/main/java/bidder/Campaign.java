package bidder;

import java.util.Set;

/**
 * An advertiser's line item: budget, base value, and targeting (geo, audience segments, daypart,
 * creative length). Immutable, so the whole campaign list can be shared across threads freely.
 */
record Campaign(String id, String name, long budgetMicros, double baseCpm,
                Set<String> geos, Set<String> segments, int startHour, int endHour,
                int creativeSeconds, int capPerDay) {

    Campaign {
        geos = Set.copyOf(geos);
        segments = Set.copyOf(segments);
    }

    /** Targeting check. An empty segment set means "any audience". */
    boolean matches(BidRequest r, int hour) {
        return geos.contains(r.geo())
                && (segments.isEmpty() || r.segments().stream().anyMatch(segments::contains))
                && hour >= startHour && hour < endHour
                && creativeSeconds <= r.slotSeconds();
    }

    long matchedSegments(BidRequest r) {
        return r.segments().stream().filter(segments::contains).count();
    }
}
