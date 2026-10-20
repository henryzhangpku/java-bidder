package bidder;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One ad opportunity sent by the exchange: who is watching, where, how long the slot is, the floor.
 * A record is an immutable data class (think @dataclass(frozen=True)); the compact constructor
 * below plays the role of __post_init__ validation.
 */
record BidRequest(String auctionId, String deviceId, String app, int slotSeconds,
                  String geo, Set<String> segments, long floorMicros) {

    BidRequest {
        Objects.requireNonNull(auctionId, "auctionId");
        Objects.requireNonNull(deviceId, "deviceId");
        if (slotSeconds <= 0) throw new IllegalArgumentException("slotSeconds must be > 0");
        if (floorMicros < 0) throw new IllegalArgumentException("floor must be >= 0");
        segments = Set.copyOf(segments); // defensive copy: the record stays truly immutable
    }

    /** Floor arrives as CPM dollars (price per 1,000 impressions), as in OpenRTB. */
    static BidRequest fromJson(Map<String, Object> m) {
        var auctionId = m.get("auctionId") instanceof String s ? s : UUID.randomUUID().toString();
        return new BidRequest(
                auctionId,
                Json.str(m, "deviceId"),
                Json.str(m, "app"),
                (int) Json.num(m, "slotSeconds"),
                Json.str(m, "geo"),
                Json.strings(m, "segments"),
                Pricing.cpmToMicros(Json.num(m, "floorCpm")));
    }
}
