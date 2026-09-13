plugins {
    kotlin("jvm") version "2.4.10"
}

group = "com.mycompany.myproject"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runCoroutines") {
    group = "benchmark"
    description = "Runs the Kotlin coroutine benchmark (default: 1,000,000 coroutines)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "benchmark.CoroutineBenchmarkKt"
    maxHeapSize = "4g"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            providers.gradleProperty("taskCount").orElse("1000000").get(),
            providers.gradleProperty("ioWaitMillis").orElse("100").get(),
        )
    })
}

tasks.register<JavaExec>("runVirtualThreads") {
    group = "benchmark"
    description = "Runs the Java virtual-thread benchmark (default: 1,000,000 threads)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "benchmark.VirtualThreadBenchmark"
    maxHeapSize = "4g"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            providers.gradleProperty("taskCount").orElse("1000000").get(),
            providers.gradleProperty("ioWaitMillis").orElse("100").get(),
        )
    })
}
