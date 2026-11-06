# OTel Kotlin suspend-annotation context leak

A standalone, deterministic reproduction using only public Maven Central artifacts.

[Bug report and failure analysis](ISSUE.md)
No application framework, gRPC, service, collector, credentials, or extra diagnostic agent is needed.

## Run

```sh
git clone https://github.com/friscoMad/otel-kotlin-context-reproducer.git
cd otel-kotlin-context-reproducer
```

Prerequisite: JDK 21 available to Gradle. The included Gradle wrapper downloads Gradle and public dependencies on first use.

```sh
./gradlew reproduce --console=plain
```

**Expected result on the affected version: command fails with the application's assertion**, after printing:

```text
RESULT completed=3 leakedCallbacks=9 distinctRequestTraces=1
java.lang.IllegalStateException: A request span remained current after callback completion
```

Run the identical code with only annotation instrumentation disabled:

```sh
./gradlew control --console=plain
```

Expected control result: command succeeds and prints:

```text
RESULT completed=3 leakedCallbacks=0 distinctRequestTraces=3
```

The failing assertion is the reproducer's success criterion for demonstrating the bug. A fixed agent should make BOTH commands succeed with zero leaked callbacks and three distinct request traces. Trace/span IDs are random.

## Contents

- `src/main/kotlin/Main.kt`: the entire reproduction. Three logical requests run serially through a small callback queue on the main thread. Nested suspend `@WithSpan` methods change coroutine context without changing dispatcher, then suspend on a future completed by a later callback. Every application-owned scope is closed; spans end after asynchronous work completes.
- `build.gradle.kts`: public dependencies and the two JVM launch tasks. Both use the stock OpenTelemetry agent, all exporters disabled. Only the annotation-instrumentation flag differs.
- `ISSUE.md`: detailed bug report ready to paste into the upstream issue form.
- `results/`: actual local verification output, with the local JDK path redacted.

To check a different agent version without changing the source:

```sh
./gradlew reproduce control --continue --console=plain -PagentVersion=VERSION
```

An aggregate Gradle failure is expected if `reproduce` fails, even when `control` passes. Separate commands make their exit statuses clearer.

This is a small reproduction, not a claim that every line or both annotations are necessary. It demonstrates retained OpenTelemetry context directly; it intentionally does not reproduce the secondary gRPC depth warning.

Verified locally on macOS 26.7.1 arm64 with Temurin 21.0.9+10-LTS, Kotlin 2.4.21, coroutines 1.11.0, and the stock Java agent 2.33.1.
