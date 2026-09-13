package benchmark;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Locale;

public final class BenchmarkMetrics {
    private final String name;
    private final int taskCount;
    private final OperatingSystemMXBean osBean;

    public BenchmarkMetrics(String name, int taskCount) {
        this.name = name;
        this.taskCount = taskCount;
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    public void measure(ThrowingRunnable workload) throws Exception {
        forceGc();
        resetPeakHeapUsage();

        long heapBefore = usedHeapBytes();
        long cpuBefore = osBean.getProcessCpuTime();
        long wallBefore = System.nanoTime();

        workload.run();

        long wallNanos = System.nanoTime() - wallBefore;
        long cpuNanos = osBean.getProcessCpuTime() - cpuBefore;
        long heapAfter = usedHeapBytes();
        long peakHeap = peakHeapBytes();
        int processors = osBean.getAvailableProcessors();
        double oneCoreCpuPercent = 100.0 * cpuNanos / wallNanos;
        double machineCpuPercent = oneCoreCpuPercent / processors;
        double throughput = taskCount / (wallNanos / 1e9);

        System.out.printf(Locale.ROOT, "%n=== %s ===%n", name);
        System.out.printf(Locale.ROOT, "Tasks completed:        %,d%n", taskCount);
        System.out.printf(Locale.ROOT, "Wall-clock time:       %,.3f s%n", wallNanos / 1e9);
        System.out.printf(Locale.ROOT, "Throughput:            %,.0f tasks/s%n", throughput);
        System.out.printf(Locale.ROOT, "Process CPU time:      %,.3f s%n", cpuNanos / 1e9);
        System.out.printf(Locale.ROOT, "Average CPU usage:     %,.1f%% of one core%n", oneCoreCpuPercent);
        System.out.printf(Locale.ROOT, "Average machine CPU:   %,.1f%% of %d cores%n", machineCpuPercent, processors);
        System.out.printf(Locale.ROOT, "Heap before workload:  %,.1f MiB%n", toMiB(heapBefore));
        System.out.printf(Locale.ROOT, "Peak heap used:        %,.1f MiB%n", toMiB(peakHeap));
        System.out.printf(Locale.ROOT, "Heap after workload:   %,.1f MiB%n", toMiB(heapAfter));
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(200);
    }

    private static void resetPeakHeapUsage() {
        ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakHeapBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static long usedHeapBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static double toMiB(long bytes) {
        return bytes / 1024.0 / 1024.0;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
