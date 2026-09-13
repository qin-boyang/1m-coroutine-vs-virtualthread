package benchmark

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val DEFAULT_TASK_COUNT = 1_000_000

fun main(args: Array<String>) {
    val taskCount = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_TASK_COUNT
    require(taskCount > 0) { "Task count must be positive" }

    val gate = CompletableDeferred<Unit>()
    val completed = AtomicInteger()
    val metrics = BenchmarkMetrics("Kotlin coroutines", taskCount)

    metrics.measure {
        runBlocking {
            coroutineScope {
                repeat(taskCount) {
                    launch(Dispatchers.Default) {
                        gate.await()
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
