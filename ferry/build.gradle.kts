plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
    id("signing")
}

// 0.x, deliberately: RepoProgress is sealed and pause is future work (README's "0.x" note) — a
// Paused case would be source-breaking for every exhaustive `when` once this hits 1.0.0. Free at 0.x.
group = "dev.thuat"
version = "0.2.0"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // A library has no excuse for warnings its consumers will inherit.
        allWarningsAsErrors.set(true)
    }

    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            // api: Path, HttpClient and CoroutineDispatcher are in public signatures —
            // same embeddability argument the OkHttp dependency carried before.
            api("com.squareup.okio:okio:3.9.1")
            api("io.ktor:ktor-client-core:3.2.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            // Stays implementation: serialization appears in no public signature, only inside HuggingFace.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:3.2.3")
            implementation("com.squareup.okio:okio-fakefilesystem:3.9.1")
        }
        jvmMain.dependencies {
            // JVM engine.
            api("io.ktor:ktor-client-okhttp:3.2.3")
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
        appleMain.dependencies {
            api("io.ktor:ktor-client-darwin:3.2.3")
        }
    }
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
 * HttpClient and okio's own types must stay on the commonMainApi configuration: they are in the
 * signatures of Ferry.huggingFace, HuggingFace and ResumableDownloader, and on implementation a
 * consumer could not pass its own client or filesystem at all — the whole point of taking them as
 * constructor parameters. See EmbeddabilityTest.
 */
val commonMainApiDependencies = listOf(
    "io.ktor:ktor-client-core",
    "com.squareup.okio:okio",
)

tasks.register("checkEmbeddable") {
    group = "verification"
    description = "Fails if Ferry gained a dependency that dictates how a host app is built."
    doLast {
        val offenders = configurations
            // Android's variant configs are "debugRuntimeClasspath" / "releaseRuntimeClasspath"
            // (capital R); the KMP jvm target's is "jvmRuntimeClasspath" — ignoreCase so neither
            // naming convention quietly empties this filter.
            .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
            .flatMap { configuration ->
                configuration.incoming.resolutionResult.allDependencies
                    .mapNotNull { it.requested as? org.gradle.api.artifacts.component.ModuleComponentSelector }
                    .map { "${it.group}:${it.module}" }
            }
            .filter { dependency -> architectureDictatingDependencies.any { dependency.startsWith(it) } }
            .distinct()

        // EmbeddabilityTest cannot catch a regression that moves these back to implementation: it
        // compiles against this module's source, where both configurations put HttpClient and okio's
        // types on the compile classpath the same way, so it would pass either way. Proving it for
        // real needs a consumer project built against the published artifact, and nothing is
        // published yet — this checks the one thing that is cheap to check from inside the module:
        // that the declared configuration is still api, not just that the types resolve.
        val apiDependencyIds = configurations.getByName("commonMainApi").allDependencies
            .map { "${it.group}:${it.name}" }
        val missingFromApi = commonMainApiDependencies.filter { it !in apiDependencyIds }

        // Both checks are computed above before either can fail here, so an architecture-dictating
        // dependency and an api regression are both reported together — neither's require() aborts
        // the task before the other's problem is even computed, which would otherwise hide one
        // behind the other until the first was cleared on its own.
        val problems = listOfNotNull(
            "Ferry must not depend on these — they dictate the host's architecture: $offenders"
                .takeIf { offenders.isNotEmpty() },
            ("These must be declared with api(...), not implementation(...): $missingFromApi — " +
                "HttpClient and okio's Path/FileSystem are in Ferry's public constructors, and a " +
                "consumer can only supply its own if these types are on its compile classpath too.")
                .takeIf { missingFromApi.isNotEmpty() },
        )

        require(problems.isEmpty()) { problems.joinToString("\n") }
    }
}

tasks.named("check") { dependsOn("checkEmbeddable") }

// Publishing: KMP creates one publication per target automatically. Central still wants a
// javadoc jar on each:
val javadocJar = tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
}
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("ferry")
            description.set(
                "Resumable, verified downloads of AI model repositories from HuggingFace, " +
                    "ModelScope or Ollama — never a partial or corrupt model, never starts a " +
                    "download the device can't finish.",
            )
        }
    }
}

// Credentials, signing and the POM fields shared with :ferry-work live in gradle/publishing.gradle.kts.
apply(from = "$rootDir/gradle/publishing.gradle.kts")
