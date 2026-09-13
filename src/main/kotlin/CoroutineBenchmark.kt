package benchmark

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val DEFAULT_TASK_COUNT = 1_000_000
private const val DEFAULT_IO_WAIT_MILLIS = 100L

fun main(args: Array<String>) {
    val taskCount = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_TASK_COUNT
    val ioWaitMillis = args.getOrNull(1)?.toLongOrNull() ?: DEFAULT_IO_WAIT_MILLIS
    require(taskCount > 0) { "Task count must be positive" }
    require(ioWaitMillis >= 0) { "I/O wait must not be negative" }

    val gate = CompletableDeferred<Unit>()
    val completed = AtomicInteger()
    val metrics = BenchmarkMetrics("Kotlin coroutines", taskCount)

    println("Simulated I/O wait:    $ioWaitMillis ms per task (non-blocking delay)")
    metrics.measure {
        runBlocking {
            coroutineScope {
                repeat(taskCount) {
                    launch(Dispatchers.Default) {
                        gate.await()
                        delay(ioWaitMillis)
                        completed.incrementAndGet()
                    }
                }
                gate.complete(Unit)
            }
        }
    }

    check(completed.get() == taskCount) {
        "Expected $taskCount completions, got ${completed.get()}"
    }
}
