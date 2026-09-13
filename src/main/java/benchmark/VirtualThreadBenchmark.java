package benchmark;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualThreadBenchmark {
    private static final int DEFAULT_TASK_COUNT = 1_000_000;
    private static final long DEFAULT_IO_WAIT_MILLIS = 100L;

    public static void main(String[] args) throws Exception {
        int taskCount = args.length == 0 ? DEFAULT_TASK_COUNT : Integer.parseInt(args[0]);
        long ioWaitMillis = args.length < 2
                ? DEFAULT_IO_WAIT_MILLIS
                : Long.parseLong(args[1]);
        if (taskCount <= 0) {
            throw new IllegalArgumentException("Task count must be positive");
        }
        if (ioWaitMillis < 0) {
            throw new IllegalArgumentException("I/O wait must not be negative");
        }

        var gate = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var metrics = new BenchmarkMetrics("Java virtual threads", taskCount);

        System.out.println("Simulated I/O wait:    " + ioWaitMillis
                + " ms per task (virtual-thread sleep)");
        metrics.measure(() -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < taskCount; i++) {
                    executor.submit(() -> {
                        try {
                            gate.await();
                            Thread.sleep(ioWaitMillis);
                            completed.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    });
                }
                gate.countDown();
            }
        });

        if (completed.get() != taskCount) {
            throw new IllegalStateException(
                    "Expected " + taskCount + " completions, got " + completed.get());
        }
    }
}
