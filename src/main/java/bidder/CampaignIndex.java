package bidder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The in-memory campaign index every bid reads. It is an immutable snapshot behind an
 * AtomicReference: readers take one pointer and never lock; a config refresh builds a new
 * snapshot off to the side and swaps the pointer in one atomic step.
 */
final class CampaignIndex {

    record Snapshot(long version, Map<String, List<Campaign>> byGeo, Map<String, Campaign> byId) {}

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(0, Map.of(), Map.of()));

    /** Called by the config refresher. In-flight bids keep the old snapshot until they finish. */
    void publish(List<Campaign> campaigns) {
        Map<String, List<Campaign>> byGeo = campaigns.stream()
                .flatMap(c -> c.geos().stream().map(geo -> Map.entry(geo, c)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toUnmodifiableList())));
        Map<String, Campaign> byId = campaigns.stream()
                .collect(Collectors.toUnmodifiableMap(Campaign::id, c -> c));
        // updateAndGet may retry the lambda if two writers race; it has no side effects, so that's safe.
        current.updateAndGet(old -> new Snapshot(old.version() + 1, Map.copyOf(byGeo), byId));
    }

    Snapshot snapshot() { return current.get(); }

    /** Read the pointer once, then work on that snapshot only: a consistent view, no locks. */
    List<Campaign> candidates(BidRequest req, int hour) {
        Snapshot snap = current.get();
        return snap.byGeo().getOrDefault(req.geo(), List.of()).stream()
                .filter(c -> c.matches(req, hour))
                .toList();
    }
}
