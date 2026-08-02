import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.nthuat.ferry.work"
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
        // Matches :ferry — a library has no excuse for warnings its consumers will inherit.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // api, not implementation: RepoDownloader, RepoProgress and Ferry's exception types are in
    // the signatures of RepoDownloadWorker and RepoDownloadWorkerFactory, so a consumer needs them
    // on its own compile classpath to construct either — the same reasoning EmbeddabilityTest
    // enforces on :ferry's own okhttp/coroutines dependencies.
    api(project(":ferry"))
    api("androidx.work:work-runtime-ktx:2.11.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.work:work-testing:2.11.2")
}
