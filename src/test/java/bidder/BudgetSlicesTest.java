package bidder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BudgetSlicesTest {

    @Test
    void spendsLocallyThenLeasesMoreThenStops() {
        var service = new BudgetService(Map.of("c1", 1_000L));
        var slices = new BudgetSlices(service, 400);

        assertTrue(slices.tryCommit("c1", 300));   // leases 400, spends 300
        assertEquals(400, service.leased("c1"));
        assertEquals(100, slices.outstanding("c1"));

        assertTrue(slices.tryCommit("c1", 300));   // 100 left locally: leases another 400
        assertEquals(800, service.leased("c1"));

        assertTrue(slices.tryCommit("c1", 300));   // 200 local + last 200 from the service
        assertFalse(slices.tryCommit("c1", 300), "budget exhausted: no-bid");
        assertEquals(900, slices.committed("c1"));
    }

    @Test
    void refundAndReturnUnusedPutMoneyBack() {
        var service = new BudgetService(Map.of("c1", 1_000L));
        var slices = new BudgetSlices(service, 500);
        assertTrue(slices.tryCommit("c1", 200));
        slices.refund("c1", 50);                  // cleared at 150, not 200
        assertEquals(150, slices.committed("c1"));
        slices.returnUnused("c1");
        assertEquals(0, slices.outstanding("c1"));
        assertEquals(850, service.unleased("c1"));
    }

    /**
     * Four bidder boxes, 20,000 virtual threads racing to spend one $1 budget.
     * Spend must never exceed the budget, and money is conserved:
     * committed + outstanding slices + unleased == budget.
     */
    @Test
    void manyVirtualThreadsNeverOverspend() {
        long budget = 1_000_000;
        var service = new BudgetService(Map.of("c1", budget));
        List<BudgetSlices> boxes = List.of(
                new BudgetSlices(service, 20_000), new BudgetSlices(service, 20_000),
                new BudgetSlices(service, 20_000), new BudgetSlices(service, 20_000));
        var acceptedTotal = new AtomicLong();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) { // close() waits for all tasks
            for (int i = 0; i < 20_000; i++) {
                BudgetSlices box = boxes.get(i % boxes.size());
                pool.submit(() -> {
                    long amount = ThreadLocalRandom.current().nextLong(1, 1_000);
                    if (box.tryCommit("c1", amount)) acceptedTotal.addAndGet(amount);
                });
            }
        }

        long committed = boxes.stream().mapToLong(b -> b.committed("c1")).sum();
        long outstanding = boxes.stream().mapToLong(b -> b.outstanding("c1")).sum();

        assertEquals(acceptedTotal.get(), committed, "every accepted bid was counted once");
        assertTrue(committed <= budget, "overspend: " + (committed - budget));
        assertEquals(budget, committed + outstanding + service.unleased("c1"), "money is conserved");
        // 20,000 bids averaging ~$0.0005 want ~$10: the $1 budget must be fully leased out.
        assertEquals(0, service.unleased("c1"));
        // The overspend bound: money the service cannot see <= boxes x slice size.
        assertTrue(outstanding <= boxes.size() * 20_000L, "stranded in slices: " + outstanding);
    }
}
