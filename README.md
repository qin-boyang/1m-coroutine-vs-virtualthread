# One million Kotlin coroutines vs. Java virtual threads vs. Go goroutines

This project compares Kotlin coroutines, Java virtual threads, and Go goroutines with
one million concurrent, I/O-bound tasks. Each task waits for simulated I/O rather
than performing CPU-intensive computation.

Both programs use the same basic workload:

1. Create one million lightweight tasks.
2. Hold the tasks at a shared gate so they coexist in memory.
3. Open the gate and simulate 100 ms of I/O latency per task.
   - Kotlin uses the non-blocking suspending function `delay(100)`.
   - Java uses `Thread.sleep(100)`, which parks rather than pins a virtual thread.
   - Go uses `time.Sleep(100 * time.Millisecond)`, which parks the goroutine.
4. Let every task increment a counter, then verify the final count.

Each benchmark runs in a separate JVM with a 4 GiB maximum heap. The report shows
wall-clock time, process CPU time, average CPU utilization, and peak JVM heap usage.

## Requirements and versions

The recorded benchmark used:

| Component | Version or configuration |
|---|---|
| Machine | Apple MacBook Pro with M3 Pro, 11 available CPU cores |
| Java | Oracle JDK 25.0.4.1 LTS |
| Kotlin JVM plugin | 2.4.10 |
| kotlinx.coroutines | 1.10.2 |
| Go | 1.21.5, darwin/arm64 |
| Gradle wrapper | 9.7.1 |
| Maximum heap | 4 GiB per benchmark JVM |
| Default task count | 1,000,000 |
| Default simulated I/O wait | 100 ms per task |

You do not need to install Gradle separately because the project includes the
Gradle wrapper. A compatible JDK 25 installation and Go are required to run all
three implementations.

Check the active Java version with:

```shell
java -version
go version
```

## How to run

Open a terminal in the project directory:

```shell
cd /Users/boyangqin/IntelliJProjects/1m-coroutine-vs-virtualthread
```

Run the benchmarks separately so each gets a fresh JVM:

```shell
./gradlew runCoroutines
./gradlew runVirtualThreads
```

You can also run both sequentially with one command:

```shell
./gradlew runCoroutines runVirtualThreads
```

Build and run the Go benchmark separately so compilation is not included in the
measured workload:

```shell
go build -o build/goroutine-benchmark ./cmd/goroutine-benchmark
./build/goroutine-benchmark 1000000 100
```

On the machine used for the recorded result, Go exists at `/usr/local/go/bin/go`
but is not currently in `PATH`, so the equivalent build command is:

```shell
/usr/local/go/bin/go build -o build/goroutine-benchmark ./cmd/goroutine-benchmark
./build/goroutine-benchmark 1000000 100
```

The results are printed directly in the terminal under `=== Kotlin coroutines ===`,
`=== Java virtual threads ===`, or `=== Go goroutines ===`.

For a quicker smoke test or a different task count:

```shell
./gradlew runCoroutines -PtaskCount=100000
./gradlew runVirtualThreads -PtaskCount=100000
./build/goroutine-benchmark 100000 100
```

Change the simulated I/O latency with `ioWaitMillis`:

```shell
./gradlew runCoroutines -PioWaitMillis=500
./gradlew runVirtualThreads -PioWaitMillis=500
./build/goroutine-benchmark 1000000 500
```

You can combine both properties:

```shell
./gradlew runCoroutines -PtaskCount=100000 -PioWaitMillis=250
./gradlew runVirtualThreads -PtaskCount=100000 -PioWaitMillis=250
./build/goroutine-benchmark 100000 250
```

## Recorded result

I/O-wait benchmark recorded on September 13, 2026, using one million tasks and
100 ms of simulated I/O latency per task:

| Metric | Kotlin coroutines | Java virtual threads | Go goroutines | Winner |
|---|---:|---:|---:|---|
| Tasks completed | 1,000,000 | 1,000,000 | 1,000,000 | Tie |
| Wall-clock time | 3.766 s | 11.504 s | 1.770 s | Go |
| Throughput | 265,514 tasks/s | 86,928 tasks/s | 565,092 tasks/s | Go |
| Process CPU time | 15.325 s | 51.217 s | 9.941 s | Go |
| Average CPU usage | 406.9% | 445.2% | 561.7% | Context-dependent |
| Average machine CPU | 37.0% | 40.5% | 51.1% | Context-dependent |
| Heap before workload | 1.5 MiB | 1.3 MiB | 0.1 MiB | Go |
| Peak heap used | 828.7 MiB | 1,372.1 MiB | 611.5 MiB | Go |
| Heap after workload | 589.1 MiB | 979.1 MiB | 611.5 MiB | See note below |

### Interpretation

**Go goroutines are the overall winner for this particular workload.** Compared with
Kotlin coroutines, Go delivered about **2.13 times the throughput**, used about **26%
less peak heap**, and consumed about **35% less process CPU time**. Compared with Java
virtual threads, Go delivered about **6.50 times the throughput**, used about **55%
less peak heap**, and consumed about **81% less process CPU time**.

Kotlin coroutines placed second and still substantially outperformed Java virtual
threads: about 3.05 times the throughput and 40% less peak heap.

Both implementations used several CPU cores to create, schedule, suspend, resume,
and complete one million tasks. A value such as 406.9% means the process used an
average of about 4.07 CPU cores; Go's 561.7% represents about 5.62 cores. Higher CPU
utilization is not automatically worse when it produces proportionally more
throughput. The waiting operation itself does not occupy a worker thread in any of
the three implementations.

This does not mean goroutines or coroutines always beat virtual threads. Go has a
small runtime, cheap growable goroutine stacks, and a scheduler designed around large
numbers of goroutines, which fits this benchmark especially well. Virtual threads integrate
naturally with blocking Java code and are often simpler when migrating traditional
thread-per-request applications. Coroutines provide structured concurrency and
suspending APIs, but require coroutine-aware Kotlin code. The best choice depends on
the real application, its ecosystem, libraries, blocking behavior, deployment model,
and maintainability needs.

### What this test does and does not measure

The 100 ms wait models latency from an API call, JDBC query, message broker, or file
operation without introducing an external server or database bottleneck. It isolates
the runtime cost of keeping and resuming a very large number of waiting operations.

It is not an end-to-end HTTP or JDBC benchmark. Such a test would also measure the
HTTP client, socket limits, database driver, connection pool, server, query, and
network. Kotlin `delay` represents a suspending/non-blocking client. Traditional JDBC
is blocking; coroutine applications normally isolate it on an appropriate blocking
dispatcher, while virtual threads can call blocking JDBC code directly. Therefore,
an application-specific HTTP or database benchmark can produce a different result.

Java's memory figures use JVM heap-pool measurements, while Go uses sampled
`runtime.MemStats.HeapAlloc`. They are useful for this focused comparison but are not
identical accounting systems and do not represent total process RSS. Go's heap sampler
runs every 10 ms, so a very short-lived peak could be missed. The “heap after” values
are also taken without forcing a final garbage collection.

## Understanding the metrics

- **Wall-clock time** is how long the user waits for the benchmark to finish.
- **Throughput** is completed tasks divided by wall-clock time. Higher is better.
- **Process CPU time** is CPU time summed across every core used by the JVM.
- **Average CPU usage** is process CPU time divided by wall time. It can exceed 100%
  when the JVM uses multiple cores.
- **Peak heap used** is the highest measured JVM heap usage during the workload. It
  does not include all native memory, JVM internals, or total process resident-set
  size (RSS).
- **Heap after workload** is measured before a final garbage collection, so it does
  not mean that all of that memory remains permanently retained.

## Benchmark notes

This is a focused scalability comparison, not a comprehensive application benchmark.
Results change with hardware, JDK and library versions, background activity, thermal
conditions, garbage collection, and JVM warmup. For more reliable numbers:

1. Close CPU- and memory-intensive applications.
2. Run each benchmark several times.
3. Alternate which benchmark runs first.
4. Ignore the first warmup run and compare the medians.
5. Test with work that resembles the intended application, especially real blocking
   I/O or suspending operations.

JDK 25 may print a Gradle `restricted method` warning about native access. The warning
comes from Gradle's native-platform library and does not invalidate the benchmark or
indicate a failure. Look for `BUILD SUCCESSFUL` to confirm that the run completed.
