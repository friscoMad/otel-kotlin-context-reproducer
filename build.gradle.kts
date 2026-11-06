plugins {
    kotlin("jvm") version "2.4.21"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
val otelAgent by configurations.creating
val agentVersion = providers.gradleProperty("agentVersion").getOrElse("2.33.1")
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.opentelemetry:opentelemetry-api:1.67.0")
    implementation("io.opentelemetry:opentelemetry-extension-kotlin:1.67.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:2.33.1")
    otelAgent("io.opentelemetry.javaagent:opentelemetry-javaagent:$agentVersion")
}
fun registerRun(name: String, annotations: Boolean) = tasks.register<JavaExec>(name) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
    systemProperties(mapOf(
        "otel.traces.exporter" to "none",
        "otel.metrics.exporter" to "none",
        "otel.logs.exporter" to "none",
        "otel.instrumentation.opentelemetry-instrumentation-annotations.enabled" to annotations,
        "kotlinx.coroutines.debug" to "off",
    ))
    doFirst { jvmArgs("-javaagent:${otelAgent.singleFile}") }
}
registerRun("reproduce", true)
registerRun("control", false)
