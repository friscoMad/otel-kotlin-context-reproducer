# Kotlin suspend @WithSpan leaks the current span when nested withContext calls unwind

With Java agent **2.33.1**, nested suspend `@WithSpan` methods can leave an ended request's context installed on the callback thread. Later independent requests then inherit that request's trace.

[Standalone reproducer](https://github.com/friscoMad/otel-kotlin-context-reproducer) · [Complete source](https://github.com/friscoMad/otel-kotlin-context-reproducer/blob/main/src/main/kotlin/Main.kt)

Run `./gradlew reproduce` to trigger the failing isolation assertion, or `./gradlew control` to run identical code with annotation instrumentation disabled. Build and runtime details are in the repository README and build file.

## What the reproducer does

Three logical requests run serially on one callback queue. Each request creates a span, opens its scope inside `use`, and launches a coroutine with `Context.current().asContextElement()`. The entry scope closes before that entry callback returns. The request span ends in the coroutine's `finally` block, after the asynchronous work finishes.

The coroutine calls this chain:

```text
request coroutine
  outer()                          @WithSpan("outer")
    withContext(marker = "outer")  same dispatcher
      inner()                      @WithSpan("inner")
        withContext(marker = "inner")
          future.await()
```

The future is completed by a callback added to the queue. That callback cannot run until the current runnable returns, so the future necessarily suspends the coroutine. The dispatcher also queues coroutine resumptions; it uses the default `isDispatchNeeded = true`.

The `marker` is an ordinary, unrelated application ThreadLocal. Changing it causes `withContext` to create a new coroutine context, while retaining the existing dispatcher. This exercises Kotlin's `UndispatchedCoroutine` restoration path. “Undispatched” here describes that `withContext` path; it does not mean that the application's dispatcher bypasses its queue.

After every runnable returns, the driver reads `Span.current()`. The queue should be outside all request scopes at those boundaries. After draining it, the test also checks that every job completed successfully and that the application ThreadLocal returned to its original value.

## Where restoration goes wrong

There are two interacting owners of temporary OTel scopes:

1. **Coroutine thread-context propagation.** The request's OTel context is a `ThreadContextElement`. Kotlin installs it while executing the coroutine and restores the preceding thread context when execution suspends or completes. The nested same-dispatcher `withContext` calls participate in this restoration.
2. **Annotation continuation wrapping.** The agent's `AnnotationInstrumentationHelper.ContextContinuation.resumeWith` opens a scope for the annotated method's parent context, resumes its delegate inline, then closes that scope in `finally`.

The inline delegate call can reach completion of an enclosing `UndispatchedCoroutine` **before the annotation wrapper's `finally` runs**. That completion attempts to restore saved thread-context elements while the annotation wrapper's parent-context scope is still current.

An additional diagnostic run of this exact reproducer observed an ignored scope close on this call path, shown in caller-to-callee order with intermediate frames omitted:

```text
DispatchedTask.run
  UndispatchedCoroutine.afterResume
    AnnotationInstrumentationHelper.ContextContinuation.resumeWith
      UndispatchedCoroutine.afterResume
        UndispatchedCoroutine.clearThreadLocal
          ThreadContextKt.restoreThreadContext
            KotlinContextElement.restoreThreadContext
              ThreadLocalContextStorage.ScopeImpl.close
                attached context is not current: close is ignored
```

`ScopeImpl.close` restores its saved previous context only when the scope being closed still represents the current context. When that check fails, the close is ignored. Continuing to unwind the enclosing annotation scope can then restore a context whose coroutine-level restoration has already been attempted. The context left on the thread is therefore no longer the context that existed before the callback.

The diagnostic run also observed an ignored close in `RunnableWrapper.stopPropagation`, so the enclosing task wrapper did not repair the ambient context in this example.

The stack establishes the non-nested restoration attempt in the reduced example. The explanation of the subsequent scope unwinding follows the implementation; a complete identity trace of every allocated scope has not been captured. The normal reproduction uses only the stock agent, without the additional diagnostic instrumentation, and produces the same failed isolation assertion.

Relevant implementations:

- [Annotation continuation wrapping and `resumeWith`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.33.1/instrumentation/kotlinx-coroutines/kotlinx-coroutines-1.0/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/kotlinxcoroutines/v1_0/instrumentationannotations/AnnotationInstrumentationHelper.java)
- [Kotlin `UndispatchedCoroutine` and thread-context restoration](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/kotlinx-coroutines-core/jvm/src/CoroutineContext.kt)
- [OTel `ThreadLocalContextStorage.ScopeImpl.close`](https://github.com/open-telemetry/opentelemetry-java/blob/v1.67.0/context/src/main/java/io/opentelemetry/context/ThreadLocalContextStorage.java)

## Why the assertions fail

Ending a span does not detach its context. An ended span still has a valid trace/span ID. Consequently, the explicit `span.end()` calls do not repair an incorrectly retained current context, and the callback-boundary `Span.current().spanContext.isValid` check detects it.

The next request calls `spanBuilder(...).startSpan()` without an explicit parent. It should start a new trace because there should be no ambient request context at that point. Instead, it finds the retained context and uses it as its parent. All three logical requests consequently share one trace ID.

| Same application code | Completed requests | Callback boundaries with a retained span | Distinct request traces |
| --- | ---: | ---: | ---: |
| Annotation instrumentation enabled | 3 | 9 | 1 |
| Only annotation instrumentation disabled | 3 | 0 | 3 |

The enabled run fails the explicit `leakedCallbacks == 0` assertion. The disabled run passes. Nine is the number of callback-boundary observations, not nine independent requests or a count of leaked objects. All jobs complete without cancellation, and the unrelated ThreadLocal is restored correctly in both runs.

There is one execution thread and requests run serially, so a concurrent data race is not required. The application's entry scopes close before suspension is driven, and request spans end after asynchronous work completes. The observed failure is the agent/coroutine restoration interaction during unwinding.

## Expected behavior

Both configurations should finish with zero retained callback contexts and three distinct request traces. Annotation instrumentation should preserve the callback's original ambient context across suspension and completion, including when nested same-dispatcher `withContext` calls restore their thread-context elements.

*Verified with stock agent 2.33.1, OTel API/Kotlin extension 1.67.0, Kotlin 2.4.21, coroutines 1.11.0, and JDK 21. Report and reproducer prepared with Codex.*
