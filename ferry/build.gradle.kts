import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // A library has no excuse for warnings its consumers will inherit.
        allWarningsAsErrors.set(true)
    }
    // java-library defaults Kotlin sources to src/{main,test}/kotlin. This module's history
    // (git log --follow, eight defect fixes) lives under src/{main,test}/java — add it as a
    // source dir rather than moving 18 files and severing that history.
    sourceSets {
        main {
            kotlin.srcDir("src/main/java")
        }
        test {
            kotlin.srcDir("src/test/java")
        }
    }
}

dependencies {
    // api, not implementation: OkHttpClient is in the signature of Ferry.huggingFace, HuggingFace
    // and ResumableDownloader, and CoroutineDispatcher in three constructors. On implementation
    // those types are absent from a consumer's compile classpath, so the host could not pass its own
    // client — which is the property EmbeddabilityTest exists to guarantee, and which that test
    // cannot observe because it compiles inside this module.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Stays implementation: serialization appears in no public signature, only inside HuggingFace.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

/**
 * Ferry must not force a host into an architecture. MNN backgrounds downloads with a foreground
 * Service and Google's AI Edge Gallery with a CoroutineWorker; a dependency on either rules out the
 * other. Adding one would otherwise be a one-line change with no failing test.
 */
val architectureDictatingDependencies = listOf(
    "androidx.work",
    "androidx.compose",
    "androidx.lifecycle",
    "com.google.dagger",
    "io.insert-koin",
)

/**
 * OkHttpClient and CoroutineDispatcher must stay on the api configuration: they are in the
 * signatures of Ferry.huggingFace, HuggingFace and ResumableDownloader, and on implementation a
 * consumer could not pass its own client or dispatcher at all — the whole point of taking them as
 * constructor parameters. See EmbeddabilityTest.
 */
val okhttpApiDependency = "com.squareup.okhttp3:okhttp"

/**
 * CoroutineDispatcher itself lives in kotlinx-coroutines-core; -android is today's choice of
 * artifact, not the requirement. A future switch to declaring core directly is legitimate and must
 * not false-fail this check, so either satisfies it.
 */
val coroutineDispatcherApiDependencies = listOf(
    "org.jetbrains.kotlinx:kotlinx-coroutines-android",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
)

tasks.register("checkEmbeddable") {
    group = "verification"
    description = "Fails if Ferry gained a dependency that dictates how a host app is built."
    doLast {
        val offenders = configurations
            // Android's variant configs are "debugRuntimeClasspath" / "releaseRuntimeClasspath"
            // (capital R); java-library's plain, unvaried one is "runtimeClasspath" (lowercase r) —
            // ignoreCase so the java-library conversion doesn't quietly empty this filter.
            .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
            .flatMap { configuration ->
                configuration.incoming.resolutionResult.allDependencies
                    .mapNotNull { it.requested as? org.gradle.api.artifacts.component.ModuleComponentSelector }
                    .map { "${it.group}:${it.module}" }
            }
            .filter { dependency -> architectureDictatingDependencies.any { dependency.startsWith(it) } }
            .distinct()

        // EmbeddabilityTest cannot catch a regression that moves these back to implementation: it
        // compiles against this module's source, where both configurations put OkHttpClient and
        // CoroutineDispatcher on the compile classpath the same way, so it would pass either way.
        // Proving it for real needs a consumer project built against the published artifact, and
        // nothing is published yet — this checks the one thing that is cheap to check from inside
        // the module: that the declared configuration is still api, not just that the types resolve.
        val apiDependencyIds = configurations.getByName("api").allDependencies
            .map { "${it.group}:${it.name}" }
        val missingFromApi = listOfNotNull(
            okhttpApiDependency.takeIf { it !in apiDependencyIds },
            coroutineDispatcherApiDependencies.joinToString(" or ")
                .takeIf { coroutineDispatcherApiDependencies.none { dep -> dep in apiDependencyIds } },
        )

        // Both checks are computed above before either can fail here, so an architecture-dictating
        // dependency and an api regression are both reported together — neither's require() aborts
        // the task before the other's problem is even computed, which would otherwise hide one
        // behind the other until the first was cleared on its own.
        val problems = listOfNotNull(
            "Ferry must not depend on these — they dictate the host's architecture: $offenders"
                .takeIf { offenders.isNotEmpty() },
            ("These must be declared with api(...), not implementation(...): $missingFromApi — " +
                "OkHttpClient and CoroutineDispatcher are in Ferry's public constructors, and a " +
                "consumer can only supply its own if these types are on its compile classpath too.")
                .takeIf { missingFromApi.isNotEmpty() },
        )

        require(problems.isEmpty()) { problems.joinToString("\n") }
    }
}

tasks.named("check") { dependsOn("checkEmbeddable") }
