# One million Kotlin coroutines vs. Java virtual threads

Both benchmarks create one million tasks, hold them at a gate so that they coexist,
release the gate, and wait for every task to finish. They run in separate JVMs with
the same 4 GiB maximum heap and report process CPU time, average CPU utilization,
wall-clock time, and peak JVM heap usage.

```shell
./gradlew runCoroutines
./gradlew runVirtualThreads
```

For a quicker smoke test or a different scale:

```shell
./gradlew runCoroutines -PtaskCount=100000
./gradlew runVirtualThreads -PtaskCount=100000
```

Results vary with JDK version, hardware, background load, and JVM warmup. Run each
benchmark several times, alternate their order, and compare medians. Peak heap is
JVM heap usage, not the process's total resident-set size.
