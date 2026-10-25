package bidder;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * main(): wires the pieces together and serves HTTP on the JDK's built-in server.
 * Each request runs on its own virtual thread (Executors.newVirtualThreadPerTaskExecutor),
 * so plain blocking code scales like async code. Background jobs share one scheduler thread.
 */
public final class Server {

    static final long SLICE_MICROS = 500_000; // $0.50 per lease
    static final Duration PACING_DAY = Duration.ofMinutes(5); // a compressed "day" for the demo

    static List<Campaign> seedCampaigns() {
        var us = Set.of("US-CA", "US-NY", "US-TX");
        return List.of(
                new Campaign("c1", "Sports Drink", usd(8), 18, Set.of("US-CA", "US-TX"), Set.of("sports"), 0, 24, 15, 3),
                new Campaign("c2", "Kids Cereal", usd(6), 12, us, Set.of("kids"), 6, 21, 30, 2),
                new Campaign("c3", "News Network", usd(10), 15, us, Set.of("news"), 0, 24, 15, 5),
                new Campaign("c4", "Auto Lease", usd(12), 25, Set.of("US-CA", "US-NY"), Set.of("auto", "sports"), 0, 24, 30, 3),
                new Campaign("c5", "Streaming Promo", usd(5), 9, Set.of("US-CA", "US-NY", "US-TX", "CA-ON"), Set.of(), 0, 24, 15, 4),
                new Campaign("c6", "Late Night Snack", usd(4), 14, us, Set.of("cooking"), 20, 24, 15, 2));
    }

    private static long usd(double dollars) { return Math.round(dollars * 1_000_000); }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        // Disable Nagle on accepted sockets. Without this, small responses on a
        // keep-alive connection wait for the client's delayed ACK (~40 ms),
        // which dwarfs the bidder's own sub-millisecond decision time.
        System.setProperty("sun.net.httpserver.nodelay", "true");
        Clock clock = Clock.system(ZoneId.of("America/Los_Angeles")); // dayparts in Pacific time

        List<Campaign> campaigns = seedCampaigns();
        Map<String, Long> budgets = campaigns.stream()
                .collect(Collectors.toMap(Campaign::id, Campaign::budgetMicros));

        var index = new CampaignIndex();
        index.publish(campaigns);
        var budgetService = new BudgetService(budgets);
        var slices = new BudgetSlices(budgetService, SLICE_MICROS);
        var cap = new FrequencyCap(Duration.ofDays(1), clock);
        var pacer = new Pacer(budgets, slices::committed, clock.instant(), PACING_DAY);
        var stats = new Stats();
        var config = new Bidder.Config(Duration.ofMillis(20), 0.6, 0.01, Duration.ofSeconds(5));
        var bidder = new Bidder(index, cap, slices, pacer, stats, clock, config);

        ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor();
        background.scheduleAtFixedRate(() -> pacer.step(clock.instant()), 1, 1, TimeUnit.SECONDS);
        background.scheduleAtFixedRate(bidder::expireLosses, 1, 1, TimeUnit.SECONDS);
        background.scheduleAtFixedRate(cap::sweep, 1, 1, TimeUnit.MINUTES);
        // Stand-in for a config service push: rebuild and atomically swap the index every 30 s.
        background.scheduleAtFixedRate(() -> index.publish(seedCampaigns()), 30, 30, TimeUnit.SECONDS);

        warmUp(campaigns, clock, config);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        route(server, "/bid", "POST", body -> bidder.bid(BidRequest.fromJson(Json.parseObject(body))).toJson());
        route(server, "/win", "POST", body -> {
            var m = Json.parseObject(body);
            var result = bidder.win(Json.str(m, "auctionId"), Json.str(m, "campaignId"),
                    Pricing.cpmToMicros(Json.num(m, "clearingCpm")));
            return Map.of("result", result.name());
        });
        route(server, "/stats", "GET", body -> {
            var out = new LinkedHashMap<String, Object>(stats.toJson());
            out.put("indexVersion", index.snapshot().version());
            out.put("pendingBids", bidder.pendingCount());
            out.put("campaigns", campaigns.stream().map(c -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", c.id());
                row.put("name", c.name());
                row.put("budgetUsd", c.budgetMicros() / 1e6);
                row.put("leasedUsd", budgetService.leased(c.id()) / 1e6);
                row.put("committedUsd", slices.committed(c.id()) / 1e6);
                row.put("wonSpendUsd", stats.wonSpend(c.id()) / 1e6);
                row.put("throttle", pacer.throttle(c.id()));
                return row;
            }).toList());
            return out;
        });
        server.createContext("/", Server::servePage);

        server.start();
        System.out.println("bidder listening on http://localhost:" + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            background.shutdownNow();
            bidder.close();
        }));
    }

    /**
     * JIT warm-up: a cold JVM interprets bytecode and its first bids blow the 20 ms deadline.
     * Run synthetic bids through a throwaway bidder (own budgets, so no real money moves) until
     * the hot methods are compiled, and only then open the port. Real fleets gate the health check on this.
     */
    private static void warmUp(List<Campaign> campaigns, Clock clock, Bidder.Config config) {
        var budgets = campaigns.stream().collect(Collectors.toMap(Campaign::id, c -> Long.MAX_VALUE / 2));
        var slices = new BudgetSlices(new BudgetService(budgets), SLICE_MICROS);
        var index = new CampaignIndex();
        index.publish(campaigns);
        var pacer = new Pacer(budgets, slices::committed, clock.instant(), PACING_DAY);
        var warm = new Bidder.Config(Duration.ofSeconds(5), config.shadeAlpha(), 0, config.lossTimeout());
        try (var b = new Bidder(index, new FrequencyCap(Duration.ofDays(1), clock), slices, pacer, new Stats(), clock, warm)) {
            for (int i = 0; i < 20_000; i++) {
                b.bid(new BidRequest("warm-" + i, "dev-" + (i % 500), "app", 30, i % 2 == 0 ? "US-CA" : "US-NY",
                        Set.of("sports", "news"), 2_000));
            }
        }
    }

    /** Register a JSON endpoint. The function gets the request body and returns a JSON-able value. */
    private static void route(HttpServer server, String path, String method, Function<String, Object> fn) {
        server.createContext(path, ex -> {
            try (ex) { // HttpExchange is AutoCloseable: like Python's `with`
                if (!ex.getRequestMethod().equals(method)) {
                    send(ex, 405, "application/json", Json.write(Map.of("error", "use " + method)));
                    return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), UTF_8);
                try {
                    send(ex, 200, "application/json", Json.write(fn.apply(body)));
                } catch (IllegalArgumentException e) { // bad input: unchecked, mapped to 400
                    send(ex, 400, "application/json", Json.write(Map.of("error", String.valueOf(e.getMessage()))));
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    send(ex, 500, "application/json", Json.write(Map.of("error", "internal")));
                }
            }
        });
    }

    private static void servePage(HttpExchange ex) throws IOException {
        try (ex; InputStream in = Server.class.getResourceAsStream("/index.html")) {
            if (!ex.getRequestURI().getPath().equals("/") || in == null) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), UTF_8));
        }
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
