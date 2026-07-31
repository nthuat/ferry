import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.nthuat.ferry"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // A library has no excuse for warnings its consumers will inherit.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
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

tasks.register("checkEmbeddable") {
    group = "verification"
    description = "Fails if Ferry gained a dependency that dictates how a host app is built."
    doLast {
        val offenders = configurations
            .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
            .flatMap { configuration ->
                configuration.incoming.resolutionResult.allDependencies
                    .mapNotNull { it.requested as? org.gradle.api.artifacts.component.ModuleComponentSelector }
                    .map { "${it.group}:${it.module}" }
            }
            .filter { dependency -> architectureDictatingDependencies.any { dependency.startsWith(it) } }
            .distinct()

        require(offenders.isEmpty()) {
            "Ferry must not depend on these — they dictate the host's architecture: $offenders"
        }
    }
}

tasks.named("check") { dependsOn("checkEmbeddable") }
