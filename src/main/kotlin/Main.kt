import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.future.await
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext

// All callbacks and coroutine resumptions run serially on main's callback loop.
// The queue deliberately has no context propagation wrapper: each callback must
// restore the ambient context it found on entry, just as a transport callback must.
private val callbacks = ArrayDeque<Runnable>()
private val marker = ThreadLocal<String>()
private val dispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) { callbacks.add(block) }
}

@WithSpan("outer")
suspend fun outer() {
    withContext(marker.asContextElement("outer")) { inner() }
}

@WithSpan("inner")
suspend fun inner() {
    withContext(marker.asContextElement("inner")) {
        val future = CompletableFuture<Unit>()
        callbacks.add(Runnable { future.complete(Unit) })
        future.await()
    }
}

fun main() {
    val tracer = GlobalOpenTelemetry.getTracer("reproducer")
    val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    val traceIds = mutableSetOf<String>()
    val jobs = mutableListOf<Job>()
    var completed = 0
    var leakedCallbacks = 0
    val requests = 3

    fun enqueueRequest(number: Int) {
        callbacks.add(Runnable {
            val span = tracer.spanBuilder("request-$number").startSpan()
            traceIds.add(span.spanContext.traceId)
            // Close the entry scope before returning from this callback; propagate
            // the request context through the coroutine's ThreadContextElement.
            span.makeCurrent().use {
                jobs += coroutineScope.launch(Context.current().asContextElement()) {
                    try {
                        outer()
                    } finally {
                        span.end()
                        completed++
                        if (number + 1 < requests) enqueueRequest(number + 1)
                    }
                }
            }
        })
    }

    enqueueRequest(0)
    while (callbacks.isNotEmpty()) {
        callbacks.removeFirst().run()
        val ambient = Span.current().spanContext
        if (ambient.isValid) {
            leakedCallbacks++
            if (leakedCallbacks <= 3) println("LEAK after callback: traceId=${ambient.traceId} spanId=${ambient.spanId}")
        }
    }
    check(jobs.all { it.isCompleted && !it.isCancelled })
    check(marker.get() == null) { "The unrelated ThreadLocal was not restored" }
    coroutineScope.cancel()
    println("RESULT completed=$completed leakedCallbacks=$leakedCallbacks distinctRequestTraces=${traceIds.size}")
    check(completed == requests)
    check(leakedCallbacks == 0) { "A request span remained current after callback completion" }
    check(traceIds.size == requests) { "Independent requests inherited a previous request trace" }
}
