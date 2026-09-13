package benchmark;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualThreadBenchmark {
    private static final int DEFAULT_TASK_COUNT = 1_000_000;

    public static void main(String[] args) throws Exception {
        int taskCount = args.length == 0 ? DEFAULT_TASK_COUNT : Integer.parseInt(args[0]);
        if (taskCount <= 0) {
            throw new IllegalArgumentException("Task count must be positive");
        }

        var gate = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var metrics = new BenchmarkMetrics("Java virtual threads", taskCount);

        metrics.measure(() -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < taskCount; i++) {
                    executor.submit(() -> {
                        try {
                            gate.await();
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
