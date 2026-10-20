# Java since 2015, for a Python developer

You last wrote Java somewhere around Java 6, 7 or early 8: anonymous inner classes, `Date`,
getters and setters, `ExecutorService` if you were brave. Since then you've lived in Python. This
note covers what changed, in the order you'll meet it in real code, with the Python you know next
to it. Examples come from this repo's bidder where possible.

Reading time is about 45 minutes. Open `jshell` (Java's REPL, since Java 9) in another window and
paste the snippets.

---

## 0. The landscape changed more than the language

| Then (2010-2015) | Now | Python analogy |
|---|---|---|
| A big release every 3 years | A release every 6 months; **LTS** every 2 years: 8 (2014), 11 (2018), 17 (2021), 21 (2023), 25 (2025) | 3.x yearly releases |
| Oracle JDK by default | OpenJDK builds: Eclipse Temurin, Amazon Corretto, Azul Zulu (Oracle JDK has its own license terms) | CPython from python.org vs distro builds |
| Production on 6/7 | Production on **17 or 21**; 8 still lingers in old shops | 2.7 → 3.x |
| `javax.servlet`, `javax.persistence` | **`jakarta.*`** (Jakarta EE 9, 2020). Spring Boot 3 needs Java 17+ and jakarta | a package rename that broke every import |
| No REPL | `jshell` (9) | `python` REPL |
| `javac Hello.java && java Hello` | `java Hello.java` runs a source file directly (11) | `python hello.py` |
| `public static void main(String[] args)` | Java 25: a file can be just `void main() { IO.println("hi"); }` | `print("hi")` |
| Ant, early Maven | Maven or Gradle, always through the **wrapper** (`./mvnw`, `./gradlew`) pinned in the repo | pip + venv, or poetry/uv |
| JUnit 4 (`@Test(expected=...)`) | JUnit 5 (`assertThrows`, `@ParameterizedTest`), AssertJ, Mockito | pytest |
| PermGen errors | PermGen is gone (8, now Metaspace) | n/a |
| CMS / Parallel GC tuning folklore | **G1** default (9); **ZGC** and Shenandoah for sub-ms pauses; CMS removed (14) | refcount + cycle GC |
| JVM ignored Docker memory limits | Container-aware since 10 (and 8u191) | n/a |

Modules (JPMS, Java 9) exist, but most application code never touches them. Skip them.

---

## 1. Version map: what you'll see in code, and when it arrived

| Java | Year | Features you'll actually read |
|---|---|---|
| **8** | 2014 | lambdas, method references, **streams**, `Optional`, **`java.time`**, default methods, `CompletableFuture`, `LongAdder`, CHM `compute`/`merge` |
| 9 | 2017 | `List.of / Set.of / Map.of`, `jshell`, private interface methods, `Optional.stream()` |
| 10 | 2018 | **`var`**, `Collectors.toUnmodifiableList()`, `Optional.orElseThrow()` |
| **11** | 2018 | `HttpClient`, `String.isBlank/strip/lines/repeat`, `Files.readString`, single-file `java X.java` |
| 14 | 2020 | **switch expressions**, helpful NullPointerException messages |
| 15 | 2020 | **text blocks** (`"""`), `String.formatted` |
| 16 | 2021 | **records**, **pattern matching `instanceof`**, `Stream.toList()` |
| **17** | 2021 | **sealed** classes and interfaces |
| **21** | 2023 | **virtual threads**, **pattern matching `switch`**, record patterns, sequenced collections (`getFirst/getLast/reversed`), generational ZGC |
| 22 | 2024 | unnamed variables `_` |
| 24 | 2025 | stream gatherers; virtual threads no longer pinned by `synchronized` |
| **25** | 2025 | compact source files and instance `main`, `import module`, flexible constructor bodies, scoped values, compact object headers |

Bold = big ones. Structured concurrency is still a preview feature as of 25: you may see it, but
don't rely on it.

---

## 2. Syntax you'll read every day

### 2.1 Lambdas and method references (Java 8)

```java
// Then: an anonymous inner class to pass behaviour
Collections.sort(offers, new Comparator<Offer>() {
    @Override public int compare(Offer a, Offer b) { return Long.compare(b.price(), a.price()); }
});

// Now (Bidder.java)
offers.sort(Comparator.comparingLong(Offer::price).reversed());
```
```python
offers.sort(key=lambda o: o.price, reverse=True)
```

- `Offer::price` is a **method reference**, the same as `o -> o.price()`.
- A lambda implements a **functional interface**: any interface with one abstract method. The
  standard ones are in `java.util.function`:

| Java | Shape | Python |
|---|---|---|
| `Function<A,B>` | `A -> B` | `Callable[[A], B]` |
| `Supplier<T>` | `() -> T` | `Callable[[], T]` |
| `Consumer<T>` | `T -> void` | `Callable[[T], None]` |
| `Predicate<T>` | `T -> boolean` | `Callable[[T], bool]` |
| `BiFunction<A,B,C>` | `(A,B) -> C` | `Callable[[A, B], C]` |
| `ToLongFunction<T>` | `T -> long` (no boxing) | the `spend` arg in `Pacer.java` |

- Captured local variables must be **effectively final** (never reassigned). Python closures can
  read outer variables freely and need `nonlocal` to rebind. Java simply forbids rebinding, which
  is why counters inside lambdas become `AtomicLong` or `int[] box = {0}`.
- A lambda can't throw a **checked** exception unless the interface declares it. This is the most
  annoying rough edge; see section 6.

### 2.2 Streams ≈ comprehensions + itertools (Java 8)

```java
// Bidder.java
List<Campaign> uncapped = paced.stream()
        .filter(c -> cap.allows(req.deviceId(), c))
        .toList();                                   // Java 16; before: .collect(Collectors.toList())
```
```python
uncapped = [c for c in paced if cap.allows(req.device_id, c)]
```

| Python | Java stream |
|---|---|
| `[f(x) for x in xs if p(x)]` | `xs.stream().filter(p).map(f).toList()` |
| `sum(x.n for x in xs)` | `xs.stream().mapToLong(X::n).sum()` |
| `any(p(x) for x in xs)` | `xs.stream().anyMatch(p)` |
| `max(xs, key=f)` | `xs.stream().max(Comparator.comparing(f))` → returns `Optional` |
| `defaultdict(list)` build | `collect(Collectors.groupingBy(f))` (`CampaignIndex.java`) |
| `{k(x): v(x) for x in xs}` | `collect(Collectors.toMap(k, v))` (throws on duplicate keys!) |
| `range(n)` | `IntStream.range(0, n)` |
| generator | a stream is lazy and **single-use**, like a generator: consume it twice and you get `IllegalStateException` |

Rules of thumb: streams for transformations; a plain `for` loop for anything with early exits,
checked exceptions or tricky state (the offers loop in `Bidder.decide()` is a loop on purpose).
Avoid `parallelStream()` in servers: it shares one global pool.

### 2.3 `Optional` (Java 8): an explicit "maybe"

```java
// Pricing.java returns OptionalLong.empty() when value < floor
Pricing.shade(value, req.floorMicros(), alpha)
       .ifPresent(price -> offers.add(new Offer(c, price, p)));
```
```python
price = shade(value, floor, alpha)   # Optional[int]
if price is not None:
    offers.append(Offer(c, price, p))
```
Use it for **return values only**: not for fields, parameters or collections. `get()` without a
check is the same bug as a bare `None` dereference; prefer `orElse`, `orElseThrow`, `ifPresent`,
`map`. `OptionalLong/OptionalInt/OptionalDouble` avoid boxing.

### 2.4 `var` (Java 10)

```java
var stats = new Stats();                       // type is still Stats, fixed at compile time
for (var e : pending.entrySet()) { ... }       // instead of Map.Entry<String, Pending>
```
Local variables only (not fields, not method signatures). It's not Python's dynamic typing: the
variable can never hold anything but a `Stats`. Use it when the right-hand side makes the type
obvious.

### 2.5 Collection factories (Java 9)

```java
var us = Set.of("US-CA", "US-NY", "US-TX");   // Server.java
List.of(1, 2, 3);  Map.of("a", 1, "b", 2);
```
These are **immutable**: `add` throws `UnsupportedOperationException`, and they reject `null`.
Closest Python: `tuple` / `frozenset` / `MappingProxyType`. `List.copyOf(x)` makes an immutable copy
(records use it for defensive copies).

### 2.6 Records (Java 16) ≈ `@dataclass(frozen=True)`

```java
record BidRequest(String auctionId, String deviceId, String app, int slotSeconds,
                  String geo, Set<String> segments, long floorMicros) {
    BidRequest {                                         // compact constructor ≈ __post_init__
        if (slotSeconds <= 0) throw new IllegalArgumentException("slotSeconds must be > 0");
        segments = Set.copyOf(segments);
    }
}
```
```python
@dataclass(frozen=True)
class BidRequest:
    auction_id: str
    ...
    def __post_init__(self): ...
```
You get the constructor, `equals`, `hashCode`, `toString` and accessors **with parentheses**:
`req.geo()`, not `req.getGeo()` and not `req.geo`. Records can't extend classes and their fields
are final. They are the modern answer to the getter/setter boilerplate (and to Lombok's `@Value`).
A record makes a perfect map key, like a Python tuple: see `FrequencyCap.Key`.

### 2.7 Text blocks (15) and formatting

```java
String json = """
    {"deviceId": "d1", "geo": "US-CA"}
    """;
String msg = "bid %s at %d micros".formatted(id, price);   // 15; same as String.format
```
Java still has **no f-strings**. String templates were previewed in 21-22, then withdrawn. Use
`formatted`, `String.format` or `+`.

### 2.8 Switch expressions (14) and pattern matching (16, 21)

```java
// Java 7: statement switch, fall-through, break everywhere
// Now (Json.java): an expression, arrows, no fall-through
return switch (s.charAt(i)) {
    case '{' -> object();
    case '[' -> array();
    case '"' -> string();
    default  -> number();
};

// instanceof pattern (16): test + cast + bind in one step
if (m.get(key) instanceof String s) return s;

// switch on type (21), exhaustive over a sealed interface (BidResponse.java)
return switch (this) {
    case Bid b   -> Map.of("bid", true, "campaignId", b.campaignId());
    case NoBid n -> Map.of("bid", false, "reason", n.reason().name());
};

// record deconstruction pattern (21)
if (resp instanceof Bid(var auction, var campaign, long price, var score)) { ... }
```
```python
match resp:
    case Bid(campaign_id=c): ...
    case NoBid(reason=r): ...
```
The difference from Python's `match`: with a **sealed** type, Java checks at compile time that
every case is handled. Add a third subtype and every switch that forgot it stops compiling.

### 2.9 Sealed types (17)

```java
sealed interface BidResponse permits BidResponse.Bid, BidResponse.NoBid { ... }
```
This is a closed union, like `Bid | NoBid` in a Python type hint, but enforced. Together with
records and switch patterns it gives you algebraic data types. This is the biggest change in *how
Java code is designed* since generics.

### 2.10 Interfaces grew methods (8, 9)

`default` methods (8), `static` methods (8) and `private` methods (9) can live in interfaces. So an
interface now works like an ABC with some concrete methods, or a mixin. `BidResponse.toJson()` is
a default method.

### 2.11 Small things that save typing

- `try (var in = Files.newInputStream(p)) { ... }`: try-with-resources (7), which takes an existing
  effectively-final variable since 9. It's `with open(p) as f:`.
- `_` for unused variables and patterns (22): `catch (Exception _)`, `case Bid _ ->`.
- Sequenced collections (21): `list.getFirst()`, `list.getLast()`, `list.reversed()`, also on
  `LinkedHashMap` / `LinkedHashSet`. That's `xs[0]`, `xs[-1]`, `reversed(xs)`.
- Helpful NPEs (14): `Cannot invoke "String.length()" because "req.geo" is null` instead of a
  bare stack trace.

---

## 3. Time: `java.time` (8) replaced `Date` and `Calendar`

| Then | Now | Python |
|---|---|---|
| `new Date()`, `Calendar`, `SimpleDateFormat` (not thread-safe) | `Instant`, `Duration`, `LocalDate`, `LocalTime`, `ZonedDateTime`, `DateTimeFormatter` (immutable, thread-safe) | `datetime`, `timedelta`, `zoneinfo` |
| Joda-Time | built in now | `pendulum` |
| `System.currentTimeMillis()` everywhere | inject a `Clock` and call `clock.instant()` | `freezegun` / passing `now` in |

```java
Clock clock = Clock.system(ZoneId.of("America/Los_Angeles"));   // Server.java
int hour = LocalTime.now(clock).getHour();                       // Bidder.java (dayparting)
Duration.ofMillis(20);  Duration.between(dayStart, now).toMillis();
```
Tests pass a hand-movable clock (`TestClock.java`) instead of monkeypatching time. Use
`System.nanoTime()` for measuring latency, never wall-clock time.

---

## 4. Concurrency: then, now, and versus Python

### 4.1 What you remember (still valid)

`Thread`, `synchronized`, `wait/notify` (don't), `ExecutorService` (5),
`Future.get()` (blocking), `ConcurrentHashMap`, `AtomicInteger`, `CountDownLatch`.

### 4.2 Java 8 additions you'll see everywhere

- **`CompletableFuture`**: composable futures.
  ```java
  CompletableFuture.supplyAsync(() -> decide(req), workers)
      .completeOnTimeout(new NoBid(id, Reason.TIMEOUT), 20, MILLISECONDS)   // 9
      .join();
  ```
  ≈ `await asyncio.wait_for(decide(req), 0.020)`, but with a default value in place of an
  exception, and the slow task keeps running. Fan-out:
  `CompletableFuture.allOf(a, b).join()` ≈ `asyncio.gather(a, b)`; `a.thenCombine(b, f)` combines
  two results.
- **`ConcurrentHashMap` rewritten** (no more segments), with atomic per-key `compute`,
  `computeIfAbsent` and `merge`. `map.merge(k, 1, Integer::sum)` is an atomic `Counter` increment.
- **`LongAdder`**: a counter that stays fast under heavy contention (it shards internally, then
  sums on read). Used in `Stats.java`.
- `StampedLock` (optimistic reads); parallel streams (avoid in servers).

### 4.3 Java 21: virtual threads (the big one)

```java
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());   // Server.java
```
- A virtual thread costs about a KB and is scheduled by the JVM onto a few OS threads. When it
  blocks on I/O or `sleep`, it unmounts and frees the OS thread. You can run a million of them.
- **What it buys versus asyncio:** you write plain blocking code (`socket.read()`, `Thread.sleep`,
  JDBC) and get asyncio-like scalability, **without `async`/`await` colouring every function**.
  No event loop to block by mistake, and stack traces still make sense.
- **Rules:**
  - Don't pool virtual threads; create one per task.
  - Limit concurrency with a `Semaphore`, not a pool size.
  - CPU-bound work still belongs on a pool sized to the core count.
  - Before 24, blocking inside `synchronized` pinned the OS thread; use `ReentrantLock` if you're on 21.
  - `ThreadLocal` works, but a million threads each holding a big one hurts. Scoped values (25)
    are the replacement.
- Structured concurrency (preview) ≈ `asyncio.TaskGroup`.

### 4.4 No GIL: the part that changes how you think

Python threads take turns under the GIL, so `count += 1` is *mostly* safe, and real parallelism
needs processes. Java threads run **truly in parallel** on every core, so:

- Every shared mutable thing needs a tool: an atomic, a concurrent collection, a lock, or
  **immutability**.
- `count++` on a plain field **loses updates**. Use `AtomicLong` or `LongAdder`.
- **Check-then-act is a race**: `if (!map.containsKey(k)) map.put(k, v)` must become
  `map.putIfAbsent(k, v)` / `computeIfAbsent`.
- Compare-and-set is the lock-free pattern (`BudgetSlices.tryCommit`):
  ```java
  while (true) {
      long cur = local.get();
      if (cur < amount) return false;
      if (local.compareAndSet(cur, cur - amount)) return true;   // else someone raced us: retry
  }
  ```
  ≈ `with lock: if left >= amount: left -= amount`, minus the lock.

(Python 3.13 has an experimental free-threaded build. If it becomes the default, Python code will
need the same discipline.)

### 4.5 The Java Memory Model in five lines

Without synchronization, one thread may **never see** another thread's write (CPU caches, compiler
reordering). Writes become visible ("happen-before") through:
1. `synchronized` / `Lock`: everything before the unlock is visible after the next lock.
2. `volatile` fields and `Atomic*` classes: a write is visible to every later read.
3. `final` fields of a properly constructed object: safe to share without locks.
4. `Thread.start()` / `join()`, and handing a task to an executor.
5. Hence the bidder's favourite trick: build an **immutable** snapshot, then publish it through an
   `AtomicReference` (`CampaignIndex.java`). Readers need no locks.

### 4.6 Python ↔ Java concurrency table

| Python | Java |
|---|---|
| `threading.Lock` | `synchronized` block, `ReentrantLock` |
| `threading.RLock` | `ReentrantLock` (Java locks are reentrant by default) |
| `threading.Event` | `CountDownLatch(1)` |
| `queue.Queue` | `ArrayBlockingQueue` / `LinkedBlockingQueue` |
| `concurrent.futures.ThreadPoolExecutor` | `Executors.newFixedThreadPool(n)` |
| `ProcessPoolExecutor` (to dodge the GIL) | not needed: threads are parallel |
| `asyncio` tasks | virtual threads (or `CompletableFuture`) |
| `asyncio.gather` | `CompletableFuture.allOf`, or `invokeAll` on a virtual-thread executor |
| `asyncio.wait_for(x, t)` | `orTimeout` / `completeOnTimeout`, `future.get(t, unit)` |
| `asyncio.Semaphore` | `java.util.concurrent.Semaphore` |
| `loop.call_later` / periodic task | `ScheduledExecutorService.scheduleAtFixedRate` |
| `contextvars` | `ThreadLocal`, scoped values (25) |
| a counter + lock | `AtomicLong`, `LongAdder` |
| a dict + lock | `ConcurrentHashMap` |

---

## 5. The JVM: what's different from CPython

| Topic | CPython | JVM |
|---|---|---|
| Execution | bytecode interpreter (3.13 has an experimental JIT) | interpreter, then **tiered JIT** (C1, then C2) compiles hot methods to native code |
| Warm-up | none: slow from the start | **cold start is slow**: the first bid here took 24 ms, then about 0.2 ms. Services warm up before taking traffic (`Server.warmUp`) |
| Memory | refcounting + cycle collector, freed promptly | tracing GC: allocation is cheap, collection happens in batches |
| GC pauses | rarely discussed | they show up in **p99**. G1 (default) pauses in the ms range; **ZGC** / Shenandoah reach sub-ms. `-XX:+UseZGC`, read GC logs |
| Object size | everything is a heap object | primitives (`long`, 8 bytes) vs boxed (`Long`, 16+ bytes plus a pointer). That's why the bidder stores money as a `long` |
| Profiling | cProfile, py-spy | **JFR** (built in, free since 11), async-profiler, `jcmd`, `jstack` |
| Startup | fast | slower; CDS archives or GraalVM native-image when it matters |

---

## 6. Gotchas that still bite Python people

1. **`==` on objects compares references.** Use `a.equals(b)` (or `Objects.equals(a, b)` if
   either can be null). `"abc" == "abc"` may be true by accident (string interning); don't rely on it.
2. **Boxed integer cache:** `Integer a = 127, b = 127; a == b` is true; at 128 it's false.
   Compare boxed numbers with `equals`.
3. **Integer overflow is silent.** `Integer.MAX_VALUE + 1` wraps negative. Python ints never
   overflow. Use `long`, `Math.addExact`, or `BigInteger`.
4. **Division and modulo round toward zero:** `-7 / 2 == -3` (Python: `-4`); `-7 % 2 == -1`
   (Python: `1`). Use `Math.floorDiv` / `Math.floorMod` for Python behaviour.
5. **No default or keyword arguments.** Use overloads, a builder, or a config record
   (`Bidder.Config`).
6. **Checked exceptions** (`IOException`, `InterruptedException`) must be caught or declared, and
   they don't fit through standard lambdas. Wrap them in an unchecked exception or use a loop. When
   catching `InterruptedException`, call `Thread.currentThread().interrupt()`; never swallow it.
7. **Generics are erased** at runtime, and there are **no primitive generics**: `List<int>` is
   illegal, so it's `List<Integer>` (boxing) or `int[]`.
8. **`Map.get` returns `null`** for a missing key instead of raising `KeyError`. Use `getOrDefault`
   or `containsKey`.
9. **`HashMap` iteration order is unspecified.** A Python `dict` keeps insertion order; in Java
   you need `LinkedHashMap`. Use `TreeMap` for sorted keys.
10. **Removing while iterating** throws `ConcurrentModificationException`. Use `removeIf` or the
    iterator's `remove()`.
11. **Arrays are fixed-size and print as `[I@1b6d3586`.** Use `Arrays.toString`, or a `List`.
12. **`String` concatenation in a loop** is quadratic. Use `StringBuilder` (same as `''.join`).
13. **`split` takes a regex:** `"a.b".split(".")` returns an empty array. Use `split("\\.")`.
14. **`char` is a UTF-16 code unit,** not a code point. Emoji take two `char`s.
15. Everything lives in a class; there are no top-level functions (until 25's compact source files).
    Default access is package-private.

---

## 7. Coding-round cheat sheet: Python idiom → Java

```java
// Counter
Map<String, Integer> cnt = new HashMap<>();
for (String w : words) cnt.merge(w, 1, Integer::sum);

// defaultdict(list)
Map<String, List<Integer>> g = new HashMap<>();
g.computeIfAbsent(k, x -> new ArrayList<>()).add(v);

// deque (queue / stack)
Deque<Integer> q = new ArrayDeque<>();
q.offer(1); q.poll(); q.peek();        // queue
q.push(1);  q.pop();                    // stack

// heapq: a min-heap, then a max-heap
PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Comparator.reverseOrder());

// sorted(people, key=lambda p: (p.age, p.name))
people.sort(Comparator.comparingInt(Person::age).thenComparing(Person::name));
Arrays.sort(intervals, (a, b) -> Integer.compare(a[0], b[0]));   // int[][]

// tuple as a dict/set key → a record (equals/hashCode for free)
record Cell(int r, int c) {}
Set<Cell> seen = new HashSet<>();

// bisect / sortedcontainers → TreeMap / TreeSet
TreeMap<Integer, String> tm = new TreeMap<>();
tm.floorKey(x); tm.ceilingKey(x); tm.headMap(x); tm.firstEntry();

// strings
char[] cs = s.toCharArray(); s.charAt(i); String.join(",", parts);
new StringBuilder(s).reverse().toString(); s.substring(i, j);   // j exclusive, like s[i:j]

// numbers
int big = Integer.MAX_VALUE; long sum = 0L; Math.floorMod(a, n);

// LRU cache (classic)
class Lru<K, V> extends LinkedHashMap<K, V> {
    private final int cap;
    Lru(int cap) { super(16, 0.75f, true); this.cap = cap; }      // accessOrder = true
    @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) { return size() > cap; }
}

// 2D grid, fill, print
int[][] grid = new int[m][n]; Arrays.fill(grid[0], -1);
System.out.println(Arrays.deepToString(grid));
```

| Python | Java |
|---|---|
| `len(xs)` | `xs.size()` (collection), `xs.length` (array), `s.length()` (string) |
| `xs[-1]` | `xs.get(xs.size() - 1)`, or `xs.getLast()` (21) |
| `x in s` | `s.contains(x)` |
| `for i, x in enumerate(xs)` | `for (int i = 0; i < xs.size(); i++)` |
| `zip(a, b)` | index loop |
| `float('inf')` | `Double.POSITIVE_INFINITY`, or `Integer.MAX_VALUE` as a sentinel |
| `@lru_cache` | `Map<Key, V> memo` + `computeIfAbsent` (beware recursive `computeIfAbsent` on `HashMap`: it throws; use get/put) |

---

## 8. Reading a large modern Java codebase

- **Annotations everywhere.** `@Override`, `@FunctionalInterface`; in Spring: `@Component`,
  `@Autowired` / constructor injection, `@Configuration`, `@Bean`. Dependency injection replaces
  the module-level globals you'd use in Python.
- **Lombok** in older code: `@Data`, `@Value`, `@Builder`, `@Slf4j` generate code at compile time.
  Records replace much of it.
- **Layout:** `src/main/java/<package path>/` and `src/test/java/...`, with build config in
  `pom.xml` or `build.gradle(.kts)`.
- **Navigation:** an IDE (IntelliJ) or an AI agent with "find usages" beats grep. Interfaces mean
  the implementation may be one hop away from the call site.
- **Logging:** SLF4J (`log.info("bid {} at {}", id, price)`), whose placeholders avoid building
  strings when the level is off.

---

## 9. A 45-minute drill

1. **10 min:** in `jshell`, type out a record, a sealed interface with a switch over it, a stream
   pipeline with `groupingBy`, and `Optional` chaining.
2. **15 min:** read the bidder in the README's order. For each file, say out loud what the Python
   version would look like and why the Java one differs (usually: no GIL, static types, the JIT).
3. **10 min:** answer the README's ten "explain this line" questions without looking.
4. **10 min:** write `Counter`, `defaultdict`, `heapq` and an LRU in Java from memory (section 7),
   then compile with `java Scratch.java`.
